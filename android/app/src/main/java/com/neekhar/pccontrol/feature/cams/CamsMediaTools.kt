package com.neekhar.pccontrol.feature.cams

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Rational
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native media utilities for Cams: fullscreen immersive landscape, screen wake lock,
 * Picture-in-Picture, gallery snapshots, clip download/share.
 * Ported and expanded from `modules/pc-native/.../CamsTools.kt`.
 */
object CamsMediaTools {
    private val STREAM_REGEX = Regex("^[a-z0-9_]{1,32}$")

    fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** Landscape + hidden transient system bars, or back to portrait/unspecified. Call on main thread. */
    fun setFullscreen(activity: Activity, on: Boolean) {
        activity.requestedOrientation = if (on) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun keepAwake(activity: Activity, on: Boolean) {
        if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Enter Android Picture-in-Picture mode for live camera playback. Returns true if entered. */
    fun enterPictureInPicture(
        activity: Activity,
        aspectWidth: Int = 16,
        aspectHeight: Int = 9,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
        val w = aspectWidth.coerceAtLeast(1)
        val h = aspectHeight.coerceAtLeast(1)
        // Android clamps PiP aspect ratio to [1/2.39, 2.39/1]
        val ratio = w.toFloat() / h.toFloat()
        val safeRational = when {
            ratio > 2.35f -> Rational(235, 100)
            ratio < 0.42f -> Rational(42, 100)
            else -> Rational(w, h)
        }
        return runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(safeRational)
                .build()
            activity.enterPictureInPictureMode(params)
        }.getOrDefault(false)
    }

    /** Configure Android 12+ automatic Picture-in-Picture transition when navigating home. */
    fun setAutoPictureInPicture(
        activity: Activity,
        enabled: Boolean,
        aspectWidth: Int = 16,
        aspectHeight: Int = 9,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return
        }
        val w = aspectWidth.coerceAtLeast(1)
        val h = aspectHeight.coerceAtLeast(1)
        val ratio = w.toFloat() / h.toFloat()
        val safeRational = when {
            ratio > 2.35f -> Rational(235, 100)
            ratio < 0.42f -> Rational(42, 100)
            else -> Rational(w, h)
        }
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAutoEnterEnabled(enabled)
                .setAspectRatio(safeRational)
                .build()
            activity.setPictureInPictureParams(params)
        }
    }

    /**
     * Fetch go2rtc's current JPEG frame for [stream] and save to `Pictures/PC Control`.
     * Returns the MediaStore `content://` URI string.
     */
    suspend fun saveLiveSnapshot(
        context: Context,
        client: OkHttpClient,
        baseUrl: String,
        stream: String,
        label: String,
    ): String = withContext(Dispatchers.IO) {
        if (!STREAM_REGEX.matches(stream)) {
            throw IllegalArgumentException("Invalid stream name: $stream")
        }
        val url = "${baseUrl.trimEnd('/')}/api/frame.jpeg?src=$stream"
        val req = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-store")
            .get()
            .build()
        val bytes = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Camera server answered HTTP ${resp.code}")
            }
            resp.body?.bytes() ?: throw IOException("Empty frame response")
        }
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            throw IOException("Response was not a JPEG image")
        }
        saveJpeg(context, bytes, label, stream)
    }

    /** Save JPEG bytes to `Pictures/PC Control` via MediaStore and return the `content://` URI string. */
    fun saveJpeg(
        context: Context,
        bytes: ByteArray,
        label: String,
        fallbackName: String,
    ): String {
        val safe = label.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { fallbackName }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(Date())
        val name = "$safe-$stamp.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PC Control")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gallery refused the snapshot")
        try {
            cr.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Cannot write snapshot to gallery")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    /**
     * Download a replay clip from camrec (e.g. 30 s around the current playhead) and save to
     * `Movies/PC Control` via MediaStore. Returns the `content://` URI string.
     */
    suspend fun saveReplayClip(
        context: Context,
        client: OkHttpClient,
        baseUrl: String,
        cam: RecCam,
        startEpochSec: Long,
        durationSec: Int = 30,
    ): String = withContext(Dispatchers.IO) {
        val endEpochSec = startEpochSec + durationSec
        val cleanBase = baseUrl.trimEnd('/')
        val candidates = listOf(
            "$cleanBase/rec/${cam.id}/clip.mp4?start=$startEpochSec&end=$endEpochSec",
            "$cleanBase/rec/${cam.id}/clip?start=$startEpochSec&end=$endEpochSec",
        )
        var bytes: ByteArray? = null
        var lastError: String? = null
        for (candidate in candidates) {
            val req = Request.Builder()
                .url(candidate)
                .header("Cache-Control", "no-store")
                .get()
                .build()
            val result = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                    } else {
                        lastError = "HTTP ${resp.code}"
                        null
                    }
                }
            }.getOrElse {
                lastError = it.message
                null
            }
            if (result != null) {
                bytes = result
                break
            }
        }
        val videoBytes = bytes ?: throw IOException("Clip download unavailable (${lastError ?: "no clip endpoint"})")

        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(Date(startEpochSec * 1000L))
        val name = "${cam.id}-clip-$stamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PC Control")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gallery refused the video clip")
        try {
            cr.openOutputStream(uri)?.use { it.write(videoBytes) }
                ?: throw IOException("Cannot write clip to gallery")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        uri.toString()
    }

    /** Launch Android share sheet for a saved snapshot image (`content://` MediaStore URI). */
    fun shareImage(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share snapshot").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Launch Android share sheet for a saved replay video clip (`content://` MediaStore URI). */
    fun shareVideo(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share clip").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
