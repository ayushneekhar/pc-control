package com.neekhar.pccontrol.core.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Structured error thrown or returned by native platform capabilities.
 * Error codes mirror the legacy `PcNative` contract so UI/feature handlers can branch reliably.
 */
class PlatformException(
  val code: String,
  override val message: String,
  override val cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Shared configuration and lightweight SharedPreferences backing store for background services,
 * BroadcastReceivers, and biometric helpers.
 *
 * Uses the `"pc_control"` preferences file (matching existing keys from the previous app) so
 * cold background wakeups (FCM, inline notification reply, live downloads) work immediately
 * without waiting for UI/DI initialization.
 *
 * The application layer may optionally install [serverUrlProvider], [chatBaseUrlProvider],
 * [chatTokenProvider], or [deviceFingerprintProvider] at startup to bridge `AppPrefs` or DataStore.
 */
object PlatformPrefs {
  const val DEFAULT_SERVER = "https://ayushlive-hp-pavilion.tail65e4a4.ts.net"
  const val DEFAULT_API_URL = "https://api.neekhar.dev"
  const val USER_AGENT = "PCControl-Android"
  private const val PREFS_FILE = "pc_control"

  private const val KEY_SERVER_URL = "serverUrl"
  private const val KEY_API_URL = "apiUrl"
  private const val KEY_API_TOKEN = "apiToken"
  private const val KEY_CF_ACCESS_CLIENT_ID = "cfAccessClientId"
  private const val KEY_CF_ACCESS_CLIENT_SECRET = "cfAccessClientSecret"
  private const val KEY_DEVICE_FINGERPRINT = "deviceFingerprint"
  private const val KEY_LIVE_DISMISSED = "liveDismissed"
  private const val KEY_PUSH_TOKEN = "pushToken"
  private const val KEY_PUSH_ERROR = "pushError"
  private const val KEY_KEYRING_CIPHERTEXT = "keyringCiphertext"
  private const val KEY_KEYRING_IV = "keyringIv"
  private const val KEY_CHAT_BASE_URL = "chatBaseUrl"
  private const val KEY_CHAT_TOKEN = "chatToken"

  /** Optional runtime hooks allowing `AppPrefs` / `PcApiClient` to supply values dynamically. */
  @Volatile
  var serverUrlProvider: ((Context) -> String?)? = null

  @Volatile
  var apiTargetProvider: ((Context) -> Pair<String, Map<String, String>>?)? = null

  @Volatile
  var apiUrlProvider: ((Context) -> String?)? = null

  @Volatile
  var apiTokenProvider: ((Context) -> String?)? = null

  @Volatile
  var cfAccessClientIdProvider: ((Context) -> String?)? = null

  @Volatile
  var cfAccessClientSecretProvider: ((Context) -> String?)? = null

  @Volatile
  var chatBaseUrlProvider: ((Context) -> String?)? = null

  @Volatile
  var chatTokenProvider: ((Context) -> String?)? = null

  @Volatile
  var deviceFingerprintProvider: ((Context) -> String?)? = null

  private fun sp(ctx: Context): SharedPreferences =
    ctx.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

  fun serverUrl(ctx: Context): String {
    val provided = serverUrlProvider?.invoke(ctx)?.trim()?.trimEnd('/')
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_SERVER_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
      ?: DEFAULT_SERVER
  }

  fun setServerUrl(ctx: Context, url: String) {
    sp(ctx).edit().putString(KEY_SERVER_URL, url.trim().trimEnd('/')).apply()
  }

  fun deviceFingerprint(ctx: Context): String? {
    val provided = deviceFingerprintProvider?.invoke(ctx)?.trim()
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_DEVICE_FINGERPRINT, null)
  }

  fun setDeviceFingerprint(ctx: Context, fingerprint: String?) {
    sp(ctx).edit().putString(KEY_DEVICE_FINGERPRINT, fingerprint).apply()
  }

  /** Download IDs the user explicitly dismissed from Live Downloads. */
  fun liveDismissed(ctx: Context): Set<String> =
    sp(ctx).getStringSet(KEY_LIVE_DISMISSED, null)?.toSet() ?: emptySet()

  fun setLiveDismissed(ctx: Context, ids: Set<String>) {
    sp(ctx).edit().putStringSet(KEY_LIVE_DISMISSED, ids.toSet()).apply()
  }

  fun pushToken(ctx: Context): String? = sp(ctx).getString(KEY_PUSH_TOKEN, null)

  fun pushError(ctx: Context): String? = sp(ctx).getString(KEY_PUSH_ERROR, null)

  fun setPush(ctx: Context, token: String?, error: String?) {
    sp(ctx).edit()
      .putString(KEY_PUSH_TOKEN, token)
      .putString(KEY_PUSH_ERROR, error)
      .apply()
  }

  /** Encrypted GNOME keyring password (AES-GCM ciphertext + IV, base64; never plaintext). */
  fun keyringCiphertext(ctx: Context): String? = sp(ctx).getString(KEY_KEYRING_CIPHERTEXT, null)

  fun keyringIv(ctx: Context): String? = sp(ctx).getString(KEY_KEYRING_IV, null)

  fun setKeyringBlob(ctx: Context, ciphertextBase64: String, ivBase64: String) {
    sp(ctx).edit()
      .putString(KEY_KEYRING_CIPHERTEXT, ciphertextBase64)
      .putString(KEY_KEYRING_IV, ivBase64)
      .apply()
  }

  fun clearKeyringBlob(ctx: Context) {
    sp(ctx).edit()
      .remove(KEY_KEYRING_CIPHERTEXT)
      .remove(KEY_KEYRING_IV)
      .apply()
  }

  /** Unified Public API (Cloudflare) config mirrored from AppPrefs for cold background wakeups. */
  fun apiUrl(ctx: Context): String {
    val provided = apiUrlProvider?.invoke(ctx)?.trim()?.trimEnd('/')
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_API_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
      ?: DEFAULT_API_URL
  }

  fun apiToken(ctx: Context): String {
    val provided = apiTokenProvider?.invoke(ctx)?.trim()
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_API_TOKEN, null)?.trim().orEmpty()
  }

  fun cfAccessClientId(ctx: Context): String {
    val provided = cfAccessClientIdProvider?.invoke(ctx)?.trim()
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_CF_ACCESS_CLIENT_ID, null)?.trim().orEmpty()
  }

  fun cfAccessClientSecret(ctx: Context): String {
    val provided = cfAccessClientSecretProvider?.invoke(ctx)?.trim()
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_CF_ACCESS_CLIENT_SECRET, null)?.trim().orEmpty()
  }

  fun setPublicApiConfig(
    ctx: Context,
    apiUrl: String,
    apiToken: String,
    cfAccessClientId: String,
    cfAccessClientSecret: String,
  ) {
    sp(ctx).edit()
      .putString(KEY_API_URL, apiUrl.trim().trimEnd('/').ifEmpty { DEFAULT_API_URL })
      .putString(KEY_API_TOKEN, apiToken.trim())
      .putString(KEY_CF_ACCESS_CLIENT_ID, cfAccessClientId.trim())
      .putString(KEY_CF_ACCESS_CLIENT_SECRET, cfAccessClientSecret.trim())
      .apply()
  }

  /**
   * Resolves the active `(baseUrl, scopedAuthHeaders)` for a `/pc/api/*` background call.
   * Uses the live `PcApiClient` transport when the app container is active, or falls back to
   * Cloudflare-first persisted credentials on cold wakeups (or Tailscale `serverUrl` if unconfigured).
   */
  fun resolveApiTarget(ctx: Context): Pair<String, Map<String, String>> {
    val live = apiTargetProvider?.invoke(ctx)
    if (live != null && live.first.isNotBlank()) {
      return live.first.trimEnd('/') to live.second
    }
    val tok = apiToken(ctx)
    val pubUrl = apiUrl(ctx)
    if (tok.isNotEmpty() && pubUrl.isNotEmpty()) {
      val headers = buildMap {
        put("Authorization", "Bearer $tok")
        val cfId = cfAccessClientId(ctx)
        if (cfId.isNotEmpty()) put("CF-Access-Client-Id", cfId)
        val cfSecret = cfAccessClientSecret(ctx)
        if (cfSecret.isNotEmpty()) put("CF-Access-Client-Secret", cfSecret)
      }
      return pubUrl to headers
    }
    return serverUrl(ctx) to emptyMap()
  }

  /** Optional legacy custom chat endpoint URL & bearer token (kept for backward compatibility). */
  fun chatBaseUrl(ctx: Context): String {
    val provided = chatBaseUrlProvider?.invoke(ctx)?.trim()?.trimEnd('/')
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_CHAT_BASE_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
      ?: serverUrl(ctx)
  }

  fun chatToken(ctx: Context): String {
    val provided = chatTokenProvider?.invoke(ctx)?.trim()
    if (!provided.isNullOrEmpty()) return provided
    return sp(ctx).getString(KEY_CHAT_TOKEN, null)?.trim().orEmpty()
  }

  fun setChatConfig(ctx: Context, baseUrl: String, token: String) {
    sp(ctx).edit()
      .putString(KEY_CHAT_BASE_URL, baseUrl.trim().trimEnd('/'))
      .putString(KEY_CHAT_TOKEN, token.trim())
      .apply()
  }

  fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

  /** Installed build identifier "<versionName>+<versionCode>". */
  fun appBinaryTarget(ctx: Context): String = try {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      pi.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      pi.versionCode.toLong()
    }
    "${pi.versionName ?: "0.0.0"}+$code"
  } catch (_: Exception) {
    "0.0.0+0"
  }
}

/** Shared OkHttpClient instances and JSON parser for platform components. */
object PlatformHttp {
  val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
  }

  val defaultClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val orig = chain.request()
        val req = if (orig.header("User-Agent").isNullOrBlank()) {
          orig.newBuilder().header("User-Agent", PlatformPrefs.USER_AGENT).build()
        } else {
          orig
        }
        chain.proceed(req)
      }
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .build()
  }

  val downloadClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val orig = chain.request()
        val req = if (orig.header("User-Agent").isNullOrBlank()) {
          orig.newBuilder().header("User-Agent", PlatformPrefs.USER_AGENT).build()
        } else {
          orig
        }
        chain.proceed(req)
      }
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()
  }

  /**
   * Builds an OkHttp [okhttp3.Request.Builder] targeting the unified API endpoint for [path]
   * with `User-Agent` and strictly origin-scoped Cloudflare auth headers when applicable.
   */
  fun apiRequestBuilder(ctx: Context, path: String): okhttp3.Request.Builder {
    val (origin, headers) = PlatformPrefs.resolveApiTarget(ctx)
    val normalizedPath = if (path.startsWith("/")) path else "/$path"
    val builder = okhttp3.Request.Builder()
      .url("$origin$normalizedPath")
      .header("User-Agent", PlatformPrefs.USER_AGENT)
    headers.forEach { (k, v) -> builder.header(k, v) }
    return builder
  }
}

/** Crisp native haptics using predefined VibrationEffect constants on API 29+. */
object PlatformHaptics {
  enum class Kind { CLICK, HEAVY, TICK }

  fun perform(ctx: Context, kind: Kind = Kind.CLICK): Boolean = try {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      mgr?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null || !vibrator.hasVibrator()) {
      false
    } else {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effectId = when (kind) {
          Kind.HEAVY -> VibrationEffect.EFFECT_HEAVY_CLICK
          Kind.TICK -> VibrationEffect.EFFECT_TICK
          Kind.CLICK -> VibrationEffect.EFFECT_CLICK
        }
        vibrator.vibrate(VibrationEffect.createPredefined(effectId))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
      }
      true
    }
  } catch (_: Exception) {
    false
  }
}
