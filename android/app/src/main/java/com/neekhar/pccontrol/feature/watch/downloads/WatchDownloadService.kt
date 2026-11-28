package com.neekhar.pccontrol.feature.watch.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.neekhar.pccontrol.PcControlApp
import com.neekhar.pccontrol.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `dataSync` foreground service running offline Watch video + subtitle downloads over Tailscale,
 * showing live progress + Pause/Cancel notification actions.
 */
class WatchDownloadService : Service() {

    private var worker: ExecutorService? = null
    private val runningLoop = AtomicBoolean(false)

    @Volatile
    private var lastStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "watch-offline-dl").apply { isDaemon = true }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val manager = (application as? PcControlApp)?.container?.watchDownloadManager
        if (manager == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> {
                val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
                if (!itemId.isNullOrBlank()) {
                    manager.pauseDownload(itemId)
                } else {
                    manager.pauseAllActive()
                }
                if (manager.store.nextQueuedItem() == null && !runningLoop.get()) {
                    stopForegroundCleanly()
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
                if (!itemId.isNullOrBlank()) {
                    manager.cancelDownload(itemId)
                }
                if (manager.store.nextQueuedItem() == null && !runningLoop.get()) {
                    stopForegroundCleanly()
                }
                return START_NOT_STICKY
            }
        }

        val initial = buildProgressNotification(
            item = manager.items.value.firstOrNull { it.isActive },
        )
        try {
            ServiceCompat.startForeground(
                this,
                ID_PROGRESS,
                initial,
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

        if (runningLoop.compareAndSet(false, true)) {
            worker?.execute {
                try {
                    manager.drainQueueBlocking(
                        onProgress = { item ->
                            postNotification(ID_PROGRESS, buildProgressNotification(item))
                        },
                        onItemCompleted = { completed ->
                            postCompletedNotification(completed)
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Watch download loop error: ${e.message}")
                } finally {
                    runningLoop.set(false)
                    stopForegroundCleanly()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(TAG, "dataSync time limit reached; pausing active Watch downloads")
        (application as? PcControlApp)?.container?.watchDownloadManager?.pauseAllActive()
        stopForegroundCleanly()
    }

    override fun onTimeout(startId: Int) {
        (application as? PcControlApp)?.container?.watchDownloadManager?.pauseAllActive()
        stopForegroundCleanly()
    }

    override fun onDestroy() {
        worker?.shutdownNow()
        worker = null
        super.onDestroy()
    }

    private fun stopForegroundCleanly() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(ID_PROGRESS)
        }
        stopSelf(lastStartId)
    }

    private fun postNotification(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun postCompletedNotification(item: OfflineWatchItem) {
        val label = if (!item.subtitle.isNullOrBlank()) {
            "${item.title} · ${item.subtitle}"
        } else {
            item.title
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle("Downloaded for offline playback")
            .setContentText(label)
            .setAutoCancel(true)
            .setContentIntent(openWatchIntent())
            .build()
        postNotification(ID_COMPLETE + (item.jellyfinItemId.hashCode() and 0x7FFF), n)
    }

    private fun buildProgressNotification(item: OfflineWatchItem?): Notification {
        val title = item?.let {
            if (!it.subtitle.isNullOrBlank()) "${it.title} · ${it.subtitle}" else it.title
        } ?: "Preparing offline download…"

        val pct = item?.progressPercent ?: 0
        val indeterminate = item == null || item.totalBytes <= 0L
        val detail = if (item != null) {
            buildList {
                if (item.totalBytes > 0L) {
                    add("$pct%")
                    add("${WatchOfflineHelpers.formatBytes(item.downloadedBytes)} / ${WatchOfflineHelpers.formatBytes(item.totalBytes)}")
                } else if (item.downloadedBytes > 0L) {
                    add(WatchOfflineHelpers.formatBytes(item.downloadedBytes))
                }
                val spd = WatchOfflineHelpers.formatSpeed(item.speedBytesPerSec)
                if (spd.isNotEmpty()) add(spd)
            }.joinToString(" · ").ifEmpty { "Starting download…" }
        } else {
            "Connecting over Tailscale…"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, pct.coerceIn(0, 100), indeterminate)
            .setContentIntent(openWatchIntent())

        if (item != null) {
            builder.addAction(0, "Pause", actionIntent(ACTION_PAUSE, item.jellyfinItemId, 51))
            builder.addAction(0, "Cancel", actionIntent(ACTION_CANCEL, item.jellyfinItemId, 52))
        }
        return builder.build()
    }

    private fun openWatchIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("pccontrol://watch"))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            50,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(action: String, itemId: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, WatchDownloadService::class.java)
            .setAction(action)
            .putExtra(EXTRA_ITEM_ID, itemId)
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "WatchDownloadSvc"
        const val CHANNEL_ID = "watch_offline_downloads"
        const val ID_PROGRESS = 50
        const val ID_COMPLETE = 5000
        const val ACTION_START = "com.neekhar.pccontrol.watch.dl.START"
        const val ACTION_PAUSE = "com.neekhar.pccontrol.watch.dl.PAUSE"
        const val ACTION_CANCEL = "com.neekhar.pccontrol.watch.dl.CANCEL"
        const val EXTRA_ITEM_ID = "jellyfinItemId"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Watch offline downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Movies and episodes downloading to this phone for offline playback"
                    setShowBadge(false)
                },
            )
        }
    }
}
