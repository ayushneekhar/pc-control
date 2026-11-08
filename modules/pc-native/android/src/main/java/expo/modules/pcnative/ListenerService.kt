package expo.modules.pcnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type specialUse) holding an SSE connection to pc-api over the tailnet.
 * Posts a high-importance notification for every new root request; tapping it opens the app on it.
 * No FCM, no third-party push: the only connection is to the user's own server.
 */
class ListenerService : Service() {
  companion object {
    private const val TAG = "PcListener"
    const val CH_LISTENER = "listener"
    const val CH_REQUESTS = "root_requests"
    const val CH_RESULTS = "root_results"
    private const val ONGOING_ID = 1
    @Volatile var running = false
      private set
    @Volatile var connected = false
      private set

    fun start(ctx: Context) {
      val i = Intent(ctx, ListenerService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun stop(ctx: Context) {
      ctx.stopService(Intent(ctx, ListenerService::class.java))
    }

    fun ensureChannels(ctx: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val nm = ctx.getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(NotificationChannel(CH_LISTENER, "Background connection",
        NotificationManager.IMPORTANCE_LOW).apply {
        description = "Ongoing notification while pc-control listens for root requests"
        setShowBadge(false)
      })
      nm.createNotificationChannel(NotificationChannel(CH_REQUESTS, "Root requests",
        NotificationManager.IMPORTANCE_HIGH).apply {
        description = "A command is waiting for your fingerprint approval"
        enableVibration(true)
      })
      nm.createNotificationChannel(NotificationChannel(CH_RESULTS, "Root results",
        NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "An approved command finished"
      })
    }

    fun openIntent(ctx: Context, requestId: String?): PendingIntent {
      val uri = Uri.parse(if (requestId != null) "pccontrol://root/$requestId" else "pccontrol://root")
      val i = Intent(Intent.ACTION_VIEW, uri).setPackage(ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      return PendingIntent.getActivity(ctx, requestId?.hashCode() ?: 0, i,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Same visible form as the app: tabs/newlines/backslashes escaped, args space-joined. */
    fun showArgv(argv: JSONArray): String = (0 until argv.length()).joinToString(" ") {
      val a = argv.optString(it).replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
      if (a.isEmpty() || a.any { c -> c == ' ' || c == '"' || c == '\'' }) "\"" + a.replace("\"", "\\\"") + "\"" else a
    }
  }

  private val alive = AtomicBoolean(false)
  private var worker: Thread? = null
  private val wake = Object()
  private var conn: HttpURLConnection? = null
  private var netCallback: ConnectivityManager.NetworkCallback? = null
  private val shown = HashSet<String>()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannels(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    try {
      ServiceCompat.startForeground(this, ONGOING_ID, ongoing("Connecting…"), type)
    } catch (e: Exception) {
      Log.w(TAG, "startForeground failed", e)
      stopSelf(); return START_NOT_STICKY
    }
    running = true
    if (alive.compareAndSet(false, true)) {
      registerNetwork()
      worker = Thread({ loop() }, "pc-sse").apply { isDaemon = true; start() }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    alive.set(false)
    running = false
    connected = false
    try { conn?.disconnect() } catch (_: Exception) {}
    synchronized(wake) { wake.notifyAll() }
    netCallback?.let {
      try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
    }
    super.onDestroy()
  }

  private fun registerNetwork() {
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        synchronized(wake) { wake.notifyAll() }   // network came back: reconnect now, skip the backoff
      }
    }
    try {
      getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
      netCallback = cb
    } catch (e: Exception) { Log.w(TAG, "network callback", e) }
  }

  private fun ongoing(text: String): Notification =
    NotificationCompat.Builder(this, CH_LISTENER)
      .setSmallIcon(R.drawable.ic_stat_pc)
      .setContentTitle("PC Control")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setContentIntent(openIntent(this, null))
      .build()

  private fun setOngoing(text: String) {
    try { NotificationManagerCompat.from(this).notify(ONGOING_ID, ongoing(text)) } catch (_: SecurityException) {}
  }

  private fun loop() {
    var backoff = 1000L
    while (alive.get()) {
      val ok = try { stream() } catch (e: Exception) {
        Log.i(TAG, "stream ended: ${e.message}"); false
      }
      connected = false
      if (!alive.get()) break
      if (ok) backoff = 1000L
      setOngoing("Reconnecting in ${backoff / 1000}s…")
      synchronized(wake) { wake.wait(backoff) }
      backoff = (backoff * 2).coerceAtMost(60_000L)
    }
  }

  /** Returns true if the stream had connected successfully before it ended. */
  private fun stream(): Boolean {
    val url = URL(Prefs.serverUrl(this) + "/pc/api/events")
    val c = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 75_000            // server pings every ~25 s
      setRequestProperty("Accept", "text/event-stream")
      setRequestProperty("Cache-Control", "no-cache")
      useCaches = false
    }
    conn = c
    var helloSeen = false
    try {
      if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
      val r = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
      var event = "message"
      val data = StringBuilder()
      while (alive.get()) {
        val line = r.readLine() ?: break
        when {
          line.isEmpty() -> {
            if (data.isNotEmpty()) {
              if (event == "hello") helloSeen = true
              handle(event, data.toString())
            }
            event = "message"; data.setLength(0)
          }
          line.startsWith(":") -> {}
          line.startsWith("event:") -> event = line.substring(6).trim()
          line.startsWith("data:") -> {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substring(5).removePrefix(" "))
          }
        }
      }
    } finally {
      try { c.disconnect() } catch (_: Exception) {}
      conn = null
    }
    return helloSeen
  }

  private fun handle(event: String, data: String) {
    val o = try { JSONObject(data) } catch (_: Exception) { return }
    when (event) {
      "hello" -> {
        connected = true
        val state = o.optString("rootd")
        setOngoing(when (state) {
          "ok" -> "Listening for root requests"
          "not_installed" -> "Connected · rootd not installed"
          else -> "Connected · rootd not running"
        })
        val pending = o.optJSONArray("pending") ?: JSONArray()
        val ids = HashSet<String>()
        for (i in 0 until pending.length()) {
          val req = pending.getJSONObject(i)
          ids.add(req.getString("id"))
          if (req.optString("status") == "pending") notifyRequest(req)
        }
        for (id in shown.toList()) if (id !in ids) cancelRequest(id)
      }
      "rootd" -> setOngoing(when (o.optString("state")) {
        "ok" -> "Listening for root requests"
        "not_installed" -> "Connected · rootd not installed"
        else -> "Connected · rootd not running"
      })
      "request" -> notifyRequest(o)
      "update" -> {
        val id = o.optString("id")
        if (o.optString("status") != "pending") cancelRequest(id)
        if (o.optString("status") == "done") notifyResult(o)
      }
    }
  }

  private fun notifyRequest(req: JSONObject) {
    val id = req.optString("id")
    if (id.isEmpty() || id in shown) return
    shown.add(id)
    val cmd = showArgv(req.optJSONArray("argv") ?: JSONArray())
    val reason = req.optString("reason")
    val left = req.optLong("expires") * 1000 - System.currentTimeMillis()
    val n = NotificationCompat.Builder(this, CH_REQUESTS)
      .setSmallIcon(R.drawable.ic_stat_pc)
      .setContentTitle(if (reason.isNotEmpty()) "Root request: $reason" else "Root request")
      .setContentText(cmd)
      .setStyle(NotificationCompat.BigTextStyle().bigText(
        cmd + if (reason.isNotEmpty()) "\n\nReason: $reason" else ""))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setAutoCancel(true)
      .setContentIntent(openIntent(this, id))
      .apply { if (left > 0) setTimeoutAfter(left) }
      .build()
    try { NotificationManagerCompat.from(this).notify(id, 2, n) } catch (_: SecurityException) {}
  }

  private fun cancelRequest(id: String) {
    shown.remove(id)
    NotificationManagerCompat.from(this).cancel(id, 2)
  }

  private fun notifyResult(req: JSONObject) {
    val id = req.optString("id")
    val exit = req.optInt("exit", -1)
    val cmd = showArgv(req.optJSONArray("argv") ?: JSONArray())
    val n = NotificationCompat.Builder(this, CH_RESULTS)
      .setSmallIcon(R.drawable.ic_stat_pc)
      .setContentTitle(if (exit == 0) "Command finished" else "Command failed (exit $exit)")
      .setContentText(cmd)
      .setAutoCancel(true)
      .setTimeoutAfter(10 * 60_000L)
      .setContentIntent(openIntent(this, id))
      .build()
    try { NotificationManagerCompat.from(this).notify(id, 3, n) } catch (_: SecurityException) {}
  }
}
