package com.neekhar.pccontrol.core.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class TransferKind { DOWNLOAD, DRIVE_OFFLOAD, DRIVE_RESTORE }

data class ActiveDownload(
  val id: String,
  val name: String,
  val state: String,
  val progress: Double,
  val speed: Long,
  val eta: Long?,
  val kind: TransferKind = TransferKind.DOWNLOAD,
)

data class LiveDownloadsStatus(
  val sdk: Int,
  val supported: Boolean,
  val canPromote: Boolean,
  val channelEnabled: Boolean,
  val running: Boolean,
)

enum class LiveStartOutcome {
  STARTED,
  RUNNING,
  NONE,
  OFF,
  REFUSED,
  UNREACHABLE,
}

@Serializable
private data class DownloadsApiResponse(
  val enabled: Boolean = true,
  val downloads: List<DownloadApiItem> = emptyList(),
  val total: DownloadsApiTotal? = null,
)

@Serializable
private data class DownloadApiItem(
  val id: String = "",
  val name: String = "Download",
  val state: String = "",
  val progress: Double = 0.0,
  val speed: Long = 0L,
  val eta: Long? = null,
)

@Serializable
private data class DownloadsApiTotal(
  val progress: Double? = null,
)

@Serializable
private data class DriveJobsApiResponse(
  val jobs: List<DriveJobApiItem> = emptyList(),
)

@Serializable
private data class DriveJobApiItem(
  val id: String = "",
  val op: String = "offload",
  val title: String = "Drive transfer",
  val status: String = "",
  @SerialName("bytes_done") val bytesDone: Long = 0L,
  @SerialName("bytes_total") val bytesTotal: Long = 0L,
  @SerialName("size_bytes") val sizeBytes: Long = 0L,
  val pct: Double = 0.0,
  @SerialName("speed_bps") val speedBps: Long = 0L,
  @SerialName("eta_s") val etaSeconds: Long? = null,
)

/**
 * Controller for live download & Drive transfer progress notifications
 * (Android 16 promoted "Live Updates" / Samsung Now Bar when supported, ongoing progress
 * notification on Android 11–15).
 */
object LiveDownloadsController {
  const val CHANNEL = "live_downloads"
  internal const val ID_LIVE = 40
  internal const val ID_DONE = 41
  internal const val ACTION_START = "com.neekhar.pccontrol.live.START"
  internal const val ACTION_STOP = "com.neekhar.pccontrol.live.STOP"
  internal const val ACTION_DISMISSED = "com.neekhar.pccontrol.live.DISMISSED"
  private const val TAG = "PcLive"
  private const val ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS =
    "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"

  private val _isRunning = MutableStateFlow(false)
  val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()

  @Volatile
  var running: Boolean = false
    internal set(value) {
      field = value
      _isRunning.value = value
    }

  fun ensureChannel(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "Live download progress", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Progress of downloads and Drive transfers on the laptop"
        setShowBadge(false)
      },
    )
  }

  /** Android 16+: checks whether the user enabled promoted Live Updates for this app. */
  fun canPromote(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    return try {
      val compat = NotificationManagerCompat.from(ctx)
      val method = compat.javaClass.getMethod("canPostPromotedNotifications")
      (method.invoke(compat) as? Boolean) == true
    } catch (_: Exception) {
      false
    }
  }

  fun channelEnabled(ctx: Context): Boolean {
    ensureChannel(ctx)
    val ch = ctx.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
    return ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE
  }

  fun status(ctx: Context): LiveDownloadsStatus = LiveDownloadsStatus(
    sdk = Build.VERSION.SDK_INT,
    supported = Build.VERSION.SDK_INT >= 36,
    canPromote = canPromote(ctx),
    channelEnabled = channelEnabled(ctx),
    running = running,
  )

  /**
   * Starts (or pokes) [LiveDownloadsService].
   * When [fromPush] is true, earlier user dismissals are cleared because a new transfer started.
   */
  fun start(ctx: Context, fromPush: Boolean, activeIds: List<String> = emptyList()): Boolean {
    if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled() || !channelEnabled(ctx)) {
      return false
    }
    if (fromPush) {
      PlatformPrefs.setLiveDismissed(ctx, emptySet())
    } else if (activeIds.isNotEmpty() && PlatformPrefs.liveDismissed(ctx).containsAll(activeIds)) {
      return false
    }
    return try {
      ContextCompat.startForegroundService(
        ctx,
        Intent(ctx, LiveDownloadsService::class.java).setAction(ACTION_START),
      )
      true
    } catch (e: Exception) {
      Log.i(TAG, "start refused: ${e.javaClass.simpleName}: ${e.message}")
      false
    }
  }

  fun stop(ctx: Context) {
    if (running) {
      ctx.startService(Intent(ctx, LiveDownloadsService::class.java).setAction(ACTION_STOP))
    }
  }

  /** Opens Android 16's per-app Live notifications settings, falling back to app notification settings. */
  fun openLiveSettings(ctx: Context): Boolean {
    val intents = buildList {
      if (Build.VERSION.SDK_INT >= 36) add(Intent(ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS))
      add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
    }
    for (intent in intents) {
      try {
        ctx.startActivity(
          intent
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
      } catch (_: Exception) {
      }
    }
    return false
  }

  /** Checks the laptop for active downloads/Drive transfers and starts the foreground service if needed. */
  suspend fun startIfDownloading(
    ctx: Context,
    client: OkHttpClient = PlatformHttp.defaultClient,
  ): LiveStartOutcome {
    if (running) return LiveStartOutcome.RUNNING
    val pollClient = client.newBuilder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(8, TimeUnit.SECONDS)
      .build()
    val snapshot = withContext(Dispatchers.IO) {
      fetchActiveTransfers(ctx, pollClient)
    } ?: return LiveStartOutcome.UNREACHABLE

    if (!snapshot.enabled) return LiveStartOutcome.OFF
    if (snapshot.items.isEmpty()) return LiveStartOutcome.NONE
    return if (start(ctx, fromPush = false, activeIds = snapshot.items.map { it.id })) {
      LiveStartOutcome.STARTED
    } else {
      LiveStartOutcome.REFUSED
    }
  }

  internal data class TransferPollSnapshot(
    val enabled: Boolean,
    val items: List<ActiveDownload>,
    val totalProgress: Double?,
  )

  internal fun fetchActiveTransfers(
    ctx: Context,
    client: OkHttpClient,
  ): TransferPollSnapshot? {
    val server = PlatformPrefs.serverUrl(ctx)
    val dlReq = Request.Builder()
      .url("$server/pc/api/downloads")
      .get()
      .header("Accept", "application/json")
      .build()

    val dlResp = try {
      client.newCall(dlReq).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val body = resp.body?.string().orEmpty()
        PlatformHttp.json.decodeFromString(DownloadsApiResponse.serializer(), body)
      }
    } catch (_: Exception) {
      return null
    }

    val downloads = dlResp.downloads.map { d ->
      ActiveDownload(
        id = d.id,
        name = d.name.ifBlank { "Download" },
        state = d.state,
        progress = d.progress.coerceIn(0.0, 1.0),
        speed = d.speed.coerceAtLeast(0L),
        eta = d.eta,
        kind = TransferKind.DOWNLOAD,
      )
    }

    val driveTransfers = fetchDriveTransfersBestEffort(server, client)
    val combined = downloads + driveTransfers
    val totalProgress = when {
      driveTransfers.isEmpty() -> dlResp.total?.progress
      combined.isEmpty() -> null
      else -> combined.map { it.progress }.average()
    }

    return TransferPollSnapshot(
      enabled = dlResp.enabled,
      items = combined,
      totalProgress = totalProgress,
    )
  }

  private fun fetchDriveTransfersBestEffort(
    serverUrl: String,
    client: OkHttpClient,
  ): List<ActiveDownload> {
    val activeStatuses = setOf("queued", "copying", "verifying", "cleaning")
    val req = Request.Builder()
      .url("$serverUrl/pc/api/space/drive")
      .get()
      .header("Accept", "application/json")
      .build()
    return try {
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return emptyList()
        val body = resp.body?.string().orEmpty()
        val parsed = PlatformHttp.json.decodeFromString(DriveJobsApiResponse.serializer(), body)
        parsed.jobs
          .filter { it.status in activeStatuses }
          .map { job ->
            val total = if (job.bytesTotal > 0L) job.bytesTotal else job.sizeBytes
            val progress = when {
              job.status == "verifying" || job.status == "cleaning" -> 1.0
              total > 0L -> (job.bytesDone.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
              else -> (job.pct / 100.0).coerceIn(0.0, 1.0)
            }
            val kind = if (job.op == "restore") TransferKind.DRIVE_RESTORE else TransferKind.DRIVE_OFFLOAD
            val mappedState = when (job.status) {
              "copying" -> if (kind == TransferKind.DRIVE_RESTORE) "restoring" else "uploading"
              else -> job.status
            }
            ActiveDownload(
              id = "drive:${job.id}",
              name = job.title.ifBlank { "Drive transfer" },
              state = mappedState,
              progress = progress,
              speed = job.speedBps.coerceAtLeast(0L),
              eta = job.etaSeconds,
              kind = kind,
            )
          }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }
}

/**
 * Short-lived `dataSync` foreground service that polls `/pc/api/downloads` (and active Drive
 * transfers on `/pc/api/space/drive`) and keeps a single ongoing progress notification up to date.
 */
class LiveDownloadsService : Service() {
  private companion object {
    const val TAG = "PcLive"
    const val FAST_MS = 3_000L
    const val SLOW_MS = 30_000L
    const val GRACE_MS = 60_000L
    const val UNREACHABLE_MS = 120_000L
    const val STALLED_MS = 15 * 60_000L
  }

  private lateinit var thread: HandlerThread
  private lateinit var handler: Handler
  private val pollClient: OkHttpClient by lazy {
    PlatformHttp.defaultClient.newBuilder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(8, TimeUnit.SECONDS)
      .build()
  }

  private var startedAt = 0L
  private var lastOk = 0L
  private var lastMoving = 0L

  @Volatile
  private var lastIds: List<String> = emptyList()

  @Volatile
  private var lastStartId = 0

  private val seen = linkedMapOf<String, ActiveDownload>()
  private var current: Notification? = null

  @Volatile
  private var finished = false

  private val pollRunnable = Runnable { tick() }
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == Intent.ACTION_SCREEN_ON) {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    LiveDownloadsController.ensureChannel(this)
    thread = HandlerThread("live-downloads").apply { start() }
    handler = Handler(thread.looper)
    ContextCompat.registerReceiver(
      this,
      screenReceiver,
      IntentFilter(Intent.ACTION_SCREEN_ON),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStartId = startId
    when (intent?.action) {
      LiveDownloadsController.ACTION_STOP,
      LiveDownloadsController.ACTION_DISMISSED,
      -> {
        PlatformPrefs.setLiveDismissed(this, lastIds.toSet())
        handler.post { finish(null) }
        return START_NOT_STICKY
      }
    }

    val notification = current ?: buildNotification(emptyList(), null, waiting = true)
    try {
      ServiceCompat.startForeground(
        this,
        LiveDownloadsController.ID_LIVE,
        notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
          0
        },
      )
    } catch (e: Exception) {
      Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
      stopSelf(startId)
      return START_NOT_STICKY
    }

    if (!LiveDownloadsController.running) {
      LiveDownloadsController.running = true
      finished = false
      startedAt = SystemClock.elapsedRealtime()
      lastOk = startedAt
      lastMoving = startedAt
      seen.clear()
      current = notification
    } else {
      startedAt = SystemClock.elapsedRealtime()
    }

    handler.removeCallbacks(pollRunnable)
    handler.post(pollRunnable)
    return START_NOT_STICKY
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.i(TAG, "dataSync time limit reached")
    handler.post { finish(null) }
  }

  override fun onTimeout(startId: Int) {
    handler.post { finish(null) }
  }

  override fun onDestroy() {
    LiveDownloadsController.running = false
    try {
      unregisterReceiver(screenReceiver)
    } catch (_: Exception) {
    }
    handler.removeCallbacksAndMessages(null)
    thread.quitSafely()
    super.onDestroy()
  }

  // ---- Polling (runs on HandlerThread) ----

  private fun tick() {
    if (finished) return
    val now = SystemClock.elapsedRealtime()
    val snapshot = try {
      LiveDownloadsController.fetchActiveTransfers(this, pollClient)
    } catch (e: Exception) {
      Log.i(TAG, "poll failed: ${e.message}")
      null
    }

    if (snapshot == null) {
      if (now - lastOk > UNREACHABLE_MS) return finish(null)
      return scheduleNext()
    }

    lastOk = now
    if (!snapshot.enabled) return finish(null)

    val items = snapshot.items
    if (items.isEmpty()) {
      if (now - startedAt < GRACE_MS && seen.isEmpty()) {
        updateNotification(buildNotification(items, null, waiting = true))
        return scheduleNext()
      }
      val doneNames = seen.values.filter { it.progress >= 0.9 }.map { it.name }
      return finish(doneNames)
    }

    val anyMoving = items.any {
      it.state == "downloading" ||
        it.state == "uploading" ||
        it.state == "restoring" ||
        it.state == "verifying" ||
        it.state == "cleaning"
    }
    if (anyMoving) {
      lastMoving = now
    } else if (now - lastMoving > STALLED_MS) {
      return finish(null)
    }

    items.forEach { seen[it.id] = it }
    lastIds = items.map { it.id }
    updateNotification(buildNotification(items, snapshot.totalProgress))
    scheduleNext()
  }

  private fun scheduleNext() {
    val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
    handler.postDelayed(pollRunnable, if (interactive) FAST_MS else SLOW_MS)
  }

  private fun finish(doneNames: List<String>?) {
    if (finished) return
    finished = true
    handler.removeCallbacks(pollRunnable)
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    NotificationManagerCompat.from(this).cancel(LiveDownloadsController.ID_LIVE)

    if (!doneNames.isNullOrEmpty()) {
      val title = if (doneNames.size == 1) "Transfer finished" else "${doneNames.size} transfers finished"
      val text = if (doneNames.size == 1) doneNames[0] else doneNames.joinToString(", ")
      postNotification(
        LiveDownloadsController.ID_DONE,
        NotificationCompat.Builder(this, LiveDownloadsController.CHANNEL)
          .setSmallIcon(resolveNotificationIcon(this))
          .setContentTitle(title)
          .setContentText(text)
          .setStyle(NotificationCompat.BigTextStyle().bigText(text))
          .setAutoCancel(true)
          .setTimeoutAfter(15 * 60_000L)
          .setContentIntent(openAppIntent())
          .build(),
      )
    }

    LiveDownloadsController.running = false
    stopSelf(lastStartId)
  }

  // ---- Notification building ----

  private fun updateNotification(notification: Notification) {
    current = notification
    if (!finished) postNotification(LiveDownloadsController.ID_LIVE, notification)
  }

  private fun postNotification(id: Int, notification: Notification) {
    try {
      NotificationManagerCompat.from(this).notify(id, notification)
    } catch (_: SecurityException) {
    }
  }

  private fun openAppIntent(): PendingIntent {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("pccontrol://routes"))
      .setPackage(packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
      this,
      40,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun selfIntent(action: String, requestCode: Int): PendingIntent =
    PendingIntent.getService(
      this,
      requestCode,
      Intent(this, LiveDownloadsService::class.java).setAction(action),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun buildNotification(
    items: List<ActiveDownload>,
    totalProgress: Double?,
    waiting: Boolean = false,
  ): Notification {
    val title: String
    val text: String
    val chip: String?
    val pct: Int? // 0..1000, null = indeterminate

    if (waiting || items.isEmpty()) {
      title = "Checking transfers…"
      text = "Waiting for the laptop"
      chip = null
      pct = null
    } else if (items.size == 1) {
      val d = items[0]
      title = d.name
      pct = if (d.state == "metadata" || (d.kind != TransferKind.DOWNLOAD && d.state == "queued")) {
        null
      } else {
        (d.progress * 1000.0).roundToInt().coerceIn(0, 1000)
      }
      val speedPrefix = when (d.kind) {
        TransferKind.DRIVE_OFFLOAD -> "↑ "
        TransferKind.DRIVE_RESTORE -> "↓ "
        TransferKind.DOWNLOAD -> ""
      }
      text = listOfNotNull(
        stateLabel(d.state),
        pct?.let { "${it / 10}%" },
        if (d.speed > 0L) "$speedPrefix${formatSpeed(d.speed)}" else null,
        d.eta?.let { formatEta(it) },
      ).joinToString(" · ")
      chip = pct?.let { "${it / 10}%" }
    } else {
      pct = totalProgress?.let { (it * 1000.0).roundToInt().coerceIn(0, 1000) }
      val allDownloads = items.all { it.kind == TransferKind.DOWNLOAD }
      val noun = if (allDownloads) "downloads" else "transfers"
      title = "${items.size} $noun" + (pct?.let { " · ${it / 10}%" } ?: "")
      val totalSpeed = items.sumOf { it.speed }
      val maxEta = items.mapNotNull { it.eta }.maxOrNull()
      text = listOfNotNull(
        "${items[0].name} +${items.size - 1}",
        if (totalSpeed > 0L) formatSpeed(totalSpeed) else null,
        maxEta?.let { formatEta(it) },
      ).joinToString(" · ")
      chip = pct?.let { "${it / 10}%" }
    }

    val builder = NotificationCompat.Builder(this, LiveDownloadsController.CHANNEL)
      .setSmallIcon(resolveNotificationIcon(this))
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setShowWhen(false)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setContentIntent(openAppIntent())
      .setDeleteIntent(selfIntent(LiveDownloadsController.ACTION_DISMISSED, 42))
      .addAction(0, "Stop", selfIntent(LiveDownloadsController.ACTION_STOP, 43))

    applyProgressAndPromotion(builder, pct, chip)
    return builder.build()
  }

  /**
   * Uses Android 16's promoted Live Update APIs (`setRequestPromotedOngoing`,
   * `setShortCriticalText`, `NotificationCompat.ProgressStyle`) via reflection when available at
   * runtime on API 36+, while remaining 100% compatible with older AndroidX Core and Android 11–15.
   */
  private fun applyProgressAndPromotion(
    builder: NotificationCompat.Builder,
    pct: Int?,
    chip: String?,
  ) {
    try {
      builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
        .invoke(builder, true)
    } catch (_: Exception) {
    }
    if (chip != null) {
      try {
        builder.javaClass.getMethod("setShortCriticalText", String::class.java)
          .invoke(builder, chip)
      } catch (_: Exception) {
      }
    }

    if (Build.VERSION.SDK_INT >= 36 && tryApplyProgressStyle(builder, pct)) {
      return
    }
    builder.setProgress(1000, pct ?: 0, pct == null)
  }

  private fun tryApplyProgressStyle(builder: NotificationCompat.Builder, pct: Int?): Boolean = try {
    val styleClass = Class.forName("androidx.core.app.NotificationCompat\$ProgressStyle")
    val segmentClass = Class.forName("androidx.core.app.NotificationCompat\$ProgressStyle\$Segment")
    val style = styleClass.getConstructor().newInstance()
    val segment = segmentClass.getConstructor(Int::class.javaPrimitiveType).newInstance(1000)
    styleClass.getMethod("setProgressSegments", List::class.java).invoke(style, listOf(segment))
    styleClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, pct ?: 0)
    styleClass.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType)
      .invoke(style, pct == null)
    styleClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType).invoke(style, true)
    builder.setStyle(style as NotificationCompat.Style)
    true
  } catch (_: Exception) {
    false
  }

  private fun stateLabel(state: String): String? = when (state) {
    "stalled" -> "Stalled"
    "queued" -> "Queued"
    "metadata" -> "Fetching metadata"
    "checking" -> "Checking"
    "moving" -> "Moving"
    "uploading" -> "Uploading"
    "restoring" -> "Restoring"
    "verifying" -> "Verifying"
    "cleaning" -> "Finishing"
    else -> null
  }

  private fun formatSpeed(bps: Long): String = when {
    bps >= 1_048_576L -> String.format(Locale.US, "%.1f MB/s", bps / 1_048_576.0)
    bps >= 1024L -> "${bps / 1024L} KB/s"
    else -> "$bps B/s"
  }

  private fun formatEta(seconds: Long): String = when {
    seconds < 60L -> "<1 min left"
    seconds < 3600L -> "${seconds / 60L} min left"
    seconds < 86_400L -> "${seconds / 3600L} h ${(seconds % 3600L) / 60L} min left"
    else -> "${seconds / 86_400L} d left"
  }
}
