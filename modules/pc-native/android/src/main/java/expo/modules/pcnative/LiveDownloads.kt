package expo.modules.pcnative

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Live download progress (Android 16 "Live Updates": status bar chip + Samsung's Now Bar; a normal ongoing
 * progress notification on older Android).
 *
 * No always-on connection: pc-api sends a high-priority FCM data message ("downloads") when a download starts,
 * or the app starts this when it's opened while something downloads. Then a short-lived dataSync foreground
 * service polls GET /pc/api/downloads over the tailnet (every 3 s while the screen is on, 30 s while it's off)
 * and keeps ONE notification up to date. It stops when nothing is downloading, when the laptop can't be reached
 * for 2 minutes, when everything has been stalled for 15 minutes, on Stop / swipe-away, when the category is
 * turned off on the laptop, or when Android's dataSync time limit runs out (onTimeout).
 */
object LiveDownloads {
  const val CHANNEL = "live_downloads"
  internal const val ID_LIVE = 40
  internal const val ID_DONE = 41
  internal const val ACTION_START = "expo.modules.pcnative.live.START"
  internal const val ACTION_STOP = "expo.modules.pcnative.live.STOP"
  internal const val ACTION_DISMISSED = "expo.modules.pcnative.live.DISMISSED"
  private const val TAG = "PcLive"

  @Volatile internal var running = false

  fun ensureChannel(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    // LOW: silent, no heads-up on every update, but (unlike MIN) still eligible for promotion.
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live download progress",
      NotificationManager.IMPORTANCE_LOW).apply {
      description = "Progress of downloads on the laptop, in the status bar and Now Bar"
      setShowBadge(false)
    })
  }

  /** Android 16+: can this app's notifications be promoted (user setting "Live notifications")? */
  fun canPromote(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= 36 && NotificationManagerCompat.from(ctx).canPostPromotedNotifications()

  fun channelEnabled(ctx: Context): Boolean {
    ensureChannel(ctx)
    val ch = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
    return ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE
  }

  /**
   * Start (or poke) the service. fromPush: a new download started on the laptop, so earlier Stop taps no longer
   * apply. Otherwise (app opened) [activeIds] are the downloads the app saw: if the user already stopped live
   * progress for all of them, don't bring it back. -> started?
   */
  fun start(ctx: Context, fromPush: Boolean, activeIds: List<String> = emptyList()): Boolean {
    if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled() || !channelEnabled(ctx)) return false
    if (fromPush) Prefs.setLiveDismissed(ctx, emptySet())
    else if (activeIds.isNotEmpty() && Prefs.liveDismissed(ctx).containsAll(activeIds)) return false
    return try {
      ContextCompat.startForegroundService(ctx, Intent(ctx, LiveDownloadService::class.java).setAction(ACTION_START))
      true
    } catch (e: Exception) {   // ForegroundServiceStartNotAllowedException: not allowed from the background now
      Log.i(TAG, "start refused: ${e.javaClass.simpleName}: ${e.message}")
      false
    }
  }

  fun stop(ctx: Context) {
    if (running) ctx.startService(Intent(ctx, LiveDownloadService::class.java).setAction(ACTION_STOP))
  }
}

data class Dl(val id: String, val name: String, val state: String, val progress: Double, val speed: Long, val eta: Long?)

class LiveDownloadService : Service() {
  private companion object {
    const val TAG = "PcLive"
    const val FAST_MS = 3_000L
    const val SLOW_MS = 30_000L
    const val GRACE_MS = 60_000L          // after a push the torrent may not be in qBittorrent yet
    const val UNREACHABLE_MS = 120_000L
    const val STALLED_MS = 15 * 60_000L
  }

  private lateinit var thread: HandlerThread
  private lateinit var handler: Handler
  private var startedAt = 0L
  private var lastOk = 0L
  private var lastMoving = 0L
  @Volatile private var lastIds: List<String> = emptyList()
  @Volatile private var lastStartId = 0
  private val seen = linkedMapOf<String, Dl>()     // every download shown this run, as last seen
  private var current: Notification? = null
  @Volatile private var finished = false

  private val poll = Runnable { tick() }
  private val screen = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      if (i.action == Intent.ACTION_SCREEN_ON) { handler.removeCallbacks(poll); handler.post(poll) }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    LiveDownloads.ensureChannel(this)
    thread = HandlerThread("live-downloads").apply { start() }
    handler = Handler(thread.looper)
    ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStartId = startId
    when (intent?.action) {
      LiveDownloads.ACTION_STOP, LiveDownloads.ACTION_DISMISSED -> {
        // The user doesn't want these on screen: don't bring them back on app open (a new download's push does).
        Prefs.setLiveDismissed(this, lastIds.toSet())
        handler.post { finish(null) }
        return START_NOT_STICKY
      }
    }
    // Every startForegroundService() needs a startForeground() within a few seconds, even when already running.
    val n = current ?: build(listOf(), null, waiting = true)
    try {
      ServiceCompat.startForeground(this, LiveDownloads.ID_LIVE, n,
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    } catch (e: Exception) {   // e.g. ForegroundServiceStartNotAllowedException (started from the background)
      Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
      stopSelf(startId)
      return START_NOT_STICKY
    }
    if (!LiveDownloads.running) {
      LiveDownloads.running = true
      finished = false
      startedAt = SystemClock.elapsedRealtime()
      lastOk = startedAt
      lastMoving = startedAt
      seen.clear()
      current = n
    } else {
      startedAt = SystemClock.elapsedRealtime()        // poked again (another download started): new grace period
    }
    handler.removeCallbacks(poll)
    handler.post(poll)
    return START_NOT_STICKY
  }

  // Android 15+: dataSync foreground services get ~6 h per day. Stop cleanly when it runs out.
  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.i(TAG, "dataSync time limit reached")
    handler.post { finish(null) }
  }

  override fun onTimeout(startId: Int) {
    handler.post { finish(null) }
  }

  override fun onDestroy() {
    LiveDownloads.running = false
    try { unregisterReceiver(screen) } catch (_: Exception) {}
    handler.removeCallbacksAndMessages(null)
    thread.quitSafely()
    super.onDestroy()
  }

  // ---- polling (on the HandlerThread)

  private fun tick() {
    if (finished) return
    val now = SystemClock.elapsedRealtime()
    val r = try { fetch() } catch (e: Exception) { Log.i(TAG, "poll failed: ${e.message}"); null }
    if (r == null) {
      if (now - lastOk > UNREACHABLE_MS) return finish(null)
      return schedule()
    }
    lastOk = now
    val (enabled, dls, total) = r
    if (!enabled) return finish(null)
    if (dls.isEmpty()) {
      if (now - startedAt < GRACE_MS && seen.isEmpty()) { update(build(dls, null, waiting = true)); return schedule() }
      // Gone from the active list: finished (or removed/paused). Only say "finished" for ones that were nearly done.
      return finish(seen.values.filter { it.progress >= 0.9 }.map { it.name })
    }
    if (dls.any { it.state == "downloading" }) lastMoving = now
    else if (now - lastMoving > STALLED_MS) return finish(null)
    dls.forEach { seen[it.id] = it }
    lastIds = dls.map { it.id }
    update(build(dls, total))
    schedule()
  }

  private fun schedule() {
    val on = getSystemService(PowerManager::class.java).isInteractive
    handler.postDelayed(poll, if (on) FAST_MS else SLOW_MS)
  }

  private fun fetch(): Triple<Boolean, List<Dl>, Double?>? {
    val c = URL(Prefs.serverUrl(this) + "/pc/api/downloads").openConnection() as HttpURLConnection
    c.connectTimeout = 8_000
    c.readTimeout = 8_000
    c.setRequestProperty("Accept", "application/json")
    try {
      if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
      val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
      val arr = j.optJSONArray("downloads")
      val list = (0 until (arr?.length() ?: 0)).map { i ->
        val d = arr!!.getJSONObject(i)
        Dl(d.optString("id"), d.optString("name", "Download"), d.optString("state"), d.optDouble("progress", 0.0),
          d.optLong("speed", 0), if (d.isNull("eta")) null else d.optLong("eta"))
      }
      val t = j.optJSONObject("total")
      val p = if (t == null || t.isNull("progress")) null else t.optDouble("progress")
      return Triple(j.optBoolean("enabled", true), list, p)
    } finally { c.disconnect() }
  }

  /** Stop the service and remove the live notification; [doneNames] non-null = show a quiet "finished" note. */
  private fun finish(doneNames: List<String>?) {
    if (finished) return
    finished = true
    handler.removeCallbacks(poll)
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    NotificationManagerCompat.from(this).cancel(LiveDownloads.ID_LIVE)
    if (!doneNames.isNullOrEmpty()) {
      val title = if (doneNames.size == 1) "Download finished" else "${doneNames.size} downloads finished"
      val text = if (doneNames.size == 1) doneNames[0] else doneNames.joinToString(", ")
      post(LiveDownloads.ID_DONE, NotificationCompat.Builder(this, LiveDownloads.CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_pc)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setTimeoutAfter(15 * 60_000L)
        .setContentIntent(openApp())
        .build())
    }
    LiveDownloads.running = false
    stopSelf(lastStartId)      // a start that arrived after this one keeps the service (and re-runs onStartCommand)
  }

  // ---- notification

  private fun update(n: Notification) {
    current = n
    if (!finished) post(LiveDownloads.ID_LIVE, n)
  }

  private fun post(id: Int, n: Notification) {
    try { NotificationManagerCompat.from(this).notify(id, n) } catch (_: SecurityException) {}
  }

  private fun openApp(): PendingIntent {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("pccontrol://routes")).setPackage(packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(this, 40, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private fun self(action: String, code: Int): PendingIntent =
    PendingIntent.getService(this, code, Intent(this, LiveDownloadService::class.java).setAction(action),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  /**
   * One notification for everything. Promotion rules (Android 16): ongoing, has a title, ProgressStyle, no custom
   * views, not colorized, not a group summary, channel not IMPORTANCE_MIN, setRequestPromotedOngoing, and the
   * POST_PROMOTED_NOTIFICATIONS permission. The chip shows the short critical text ("42%", under 7 chars).
   */
  private fun build(dls: List<Dl>, totalProgress: Double?, waiting: Boolean = false): Notification {
    val title: String
    val text: String
    val chip: String?
    val pct: Int?          // 0..1000, null = indeterminate
    if (waiting || dls.isEmpty()) {
      title = "Checking downloads…"
      text = "Waiting for the laptop"
      chip = null
      pct = null
    } else if (dls.size == 1) {
      val d = dls[0]
      title = d.name
      pct = if (d.state == "metadata") null else (d.progress * 1000).roundToInt().coerceIn(0, 1000)
      text = listOfNotNull(
        when (d.state) {
          "stalled" -> "Stalled"; "queued" -> "Queued"; "metadata" -> "Fetching metadata"
          "checking" -> "Checking"; "moving" -> "Moving"; else -> null
        },
        if (pct != null) "${pct / 10}%" else null,
        if (d.speed > 0) speed(d.speed) else null,
        d.eta?.let { eta(it) },
      ).joinToString(" · ")
      chip = pct?.let { "${it / 10}%" }
    } else {
      pct = totalProgress?.let { (it * 1000).roundToInt().coerceIn(0, 1000) }
      title = "${dls.size} downloads" + (pct?.let { " · ${it / 10}%" } ?: "")
      val spd = dls.sumOf { it.speed }
      val etaMax = dls.mapNotNull { it.eta }.maxOrNull()
      text = listOfNotNull(
        "${dls[0].name} +${dls.size - 1}",
        if (spd > 0) speed(spd) else null,
        etaMax?.let { eta(it) },
      ).joinToString(" · ")
      chip = pct?.let { "${it / 10}%" }
    }
    val b = NotificationCompat.Builder(this, LiveDownloads.CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_pc)
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
      .setRequestPromotedOngoing(true)
      .setContentIntent(openApp())
      .setDeleteIntent(self(LiveDownloads.ACTION_DISMISSED, 42))
      .addAction(0, "Stop", self(LiveDownloads.ACTION_STOP, 43))
    if (chip != null) b.setShortCriticalText(chip)
    if (Build.VERSION.SDK_INT >= 36) {
      b.setStyle(NotificationCompat.ProgressStyle()
        .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(1000)))
        .setProgress(pct ?: 0)
        .setProgressIndeterminate(pct == null)
        .setStyledByProgress(true))
    } else {
      b.setProgress(1000, pct ?: 0, pct == null)
    }
    return b.build()
  }

  private fun speed(bps: Long): String = when {
    bps >= 1_048_576 -> String.format("%.1f MB/s", bps / 1_048_576.0)
    bps >= 1024 -> "${bps / 1024} KB/s"
    else -> "$bps B/s"
  }

  private fun eta(s: Long): String = when {
    s < 60 -> "<1 min left"
    s < 3600 -> "${s / 60} min left"
    s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min left"
    else -> "${s / 86_400} d left"
  }
}
