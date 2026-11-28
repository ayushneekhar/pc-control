package com.neekhar.pccontrol.core.platform

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PushStatus(
  val available: Boolean,
  val token: String?,
  val error: String?,
  val notificationsEnabled: Boolean,
)

sealed interface PushRegisterResult {
  data class Success(val token: String) : PushRegisterResult
  data class Failure(val error: String) : PushRegisterResult
}

sealed interface PushRouteEvent {
  data class RootRequest(
    val id: String,
    val title: String,
    val body: String,
    val expires: Long,
  ) : PushRouteEvent

  data class RootResult(
    val id: String,
    val title: String,
    val body: String,
  ) : PushRouteEvent

  data class RootDismiss(val id: String) : PushRouteEvent

  data class DownloadsStarted(val id: String) : PushRouteEvent

  data class Notify(
    val id: String,
    val title: String,
    val body: String,
    val category: String?,
    val imagePath: String?,
    val link: String?,
    val cam: String?,
    val ts: Long?,
  ) : PushRouteEvent
}

@Serializable
private data class PushRegisterBody(
  val token: String,
  val name: String,
)

/**
 * FCM token registration, notification permission helpers, and an in-process push event stream.
 * Registers the device token with `POST /pc/api/push/register` over the tailnet.
 */
object PushManager {
  private const val TAG = "PcPush"
  private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

  private val _incomingEvents = MutableSharedFlow<PushRouteEvent>(extraBufferCapacity = 32)
  val incomingEvents: SharedFlow<PushRouteEvent> = _incomingEvents.asSharedFlow()

  /** Optional callback invoked when FCM rotates the registration token. */
  @Volatile
  var onTokenRefreshed: ((token: String) -> Unit)? = null

  internal fun emitIncoming(event: PushRouteEvent) {
    _incomingEvents.tryEmit(event)
  }

  fun isFirebaseAvailable(ctx: Context): Boolean = try {
    FirebaseApp.getApps(ctx).isNotEmpty()
  } catch (_: Exception) {
    false
  }

  fun areNotificationsEnabled(ctx: Context): Boolean =
    NotificationManagerCompat.from(ctx).areNotificationsEnabled()

  fun openNotificationSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
  }

  fun status(ctx: Context): PushStatus = PushStatus(
    available = isFirebaseAvailable(ctx),
    token = PlatformPrefs.pushToken(ctx),
    error = PlatformPrefs.pushError(ctx),
    notificationsEnabled = areNotificationsEnabled(ctx),
  )

  /** Blocking FCM token fetch; must be called off the main thread. */
  fun fetchTokenBlocking(ctx: Context): String {
    if (!isFirebaseAvailable(ctx)) {
      throw PlatformException("E_NO_FIREBASE", "This build has no Firebase config")
    }
    return try {
      Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
    } catch (e: Exception) {
      val msg = e.message ?: "could not get an FCM token"
      PlatformPrefs.setPush(ctx, null, msg)
      throw PlatformException("E_FCM_TOKEN", msg, e)
    }
  }

  suspend fun fetchToken(ctx: Context): String = withContext(Dispatchers.IO) {
    fetchTokenBlocking(ctx)
  }

  /**
   * Blocking `POST /pc/api/push/register`. Records the result in [PlatformPrefs] for Settings.
   * Retries once on transient transport or gateway errors (the endpoint is an idempotent upsert).
   * Safe to invoke from [PcFirebaseMessagingService.onNewToken].
   */
  fun registerTokenBlocking(
    ctx: Context,
    token: String,
    client: OkHttpClient = PlatformHttp.defaultClient,
  ): Boolean {
    val payload = PlatformHttp.json.encodeToString(
      PushRegisterBody.serializer(),
      PushRegisterBody(token = token, name = PlatformPrefs.deviceName()),
    )
    var lastError: String? = null
    for (attempt in 0..1) {
      var retryable = false
      try {
        val req = PlatformHttp.apiRequestBuilder(ctx, "/pc/api/push/register")
          .post(payload.toRequestBody(JSON_MEDIA))
          .header("Accept", "application/json")
          .build()
        client.newCall(req).execute().use { resp ->
          if (resp.isSuccessful) {
            PlatformPrefs.setPush(ctx, token, null)
            return true
          }
          lastError = "HTTP ${resp.code}"
          retryable = resp.code == 502 || resp.code == 503 || resp.code == 504
        }
      } catch (e: IOException) {
        lastError = e.message ?: e.javaClass.simpleName
        retryable = true
      } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        retryable = false
      }
      if (!retryable || attempt == 1) break
      try {
        Thread.sleep(400L)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        break
      }
    }
    val msg = lastError ?: "Registration failed"
    Log.i(TAG, "register failed: $msg")
    // Preserve any previously registered token so transient cold-start glitches don't wipe it.
    PlatformPrefs.setPush(ctx, PlatformPrefs.pushToken(ctx), msg)
    return false
  }

  /** Fetches the current FCM token and registers it with pc-api. */
  suspend fun register(
    ctx: Context,
    client: OkHttpClient = PlatformHttp.defaultClient,
  ): PushRegisterResult = withContext(Dispatchers.IO) {
    val token = try {
      fetchTokenBlocking(ctx)
    } catch (e: Exception) {
      return@withContext PushRegisterResult.Failure(e.message ?: "Could not fetch FCM token")
    }
    val ok = registerTokenBlocking(ctx, token, client)
    if (ok) {
      PushRegisterResult.Success(token)
    } else {
      PushRegisterResult.Failure(PlatformPrefs.pushError(ctx) ?: "The laptop did not accept the token")
    }
  }
}
