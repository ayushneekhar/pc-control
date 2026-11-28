package com.neekhar.pccontrol.core.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class CrashReport(
  val id: String = "",
  val kind: String = "java",
  val ts: Double = 0.0,
  val reason: String = "",
  val where: String = "",
  val text: String = "",
  @SerialName("trace_b64") val traceB64: String? = null,
)

@Serializable
private data class CrashUploadPayload(
  val id: String,
  val kind: String,
  val ts: Double,
  val reason: String,
  val where: String,
  val text: String,
  @SerialName("trace_b64") val traceB64: String? = null,
  val app: String,
  val device: String,
  val android: String,
)

/**
 * Uncaught exception reporter and Android `ApplicationExitInfo` collector.
 *
 * - Captures uncaught JVM/Kotlin exceptions into `filesDir/crashes/<ts>-java.json` before
 *   delegating to the previous default handler.
 * - Reads native crashes (`SIGABRT`, `SIGSEGV`) and ANRs from `ApplicationExitInfo` (API 30+).
 * - Provides APIs for Settings to list, read, clear, and upload pending reports to `/pc/api/crash`.
 */
object CrashReporter {
  private const val TAG = "PcCrash"
  private const val PREFS_FILE = "pc_crash"
  private const val KEY_EXIT_TS = "exitTs"
  private const val KEY_CONTEXT_TAG = "contextTag"
  private const val KEY_CONTEXT_TAG_TS = "contextTagTs"
  private const val MAX_TRACE_BYTES = 768 * 1024
  private const val MAX_TEXT_CHARS = 200_000
  private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

  private val installed = AtomicBoolean(false)
  private val uploadStarted = AtomicBoolean(false)

  /** Optional dynamic context provider (e.g., `"cams-open"` while opening WebRTC video). */
  @Volatile
  var contextTagProvider: ((Context) -> String)? = null

  private fun crashDir(ctx: Context): File =
    File(ctx.applicationContext.filesDir, "crashes").apply { mkdirs() }

  private fun sp(ctx: Context) =
    ctx.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

  /** Installs the global UncaughtExceptionHandler (idempotent). Call from `Application.onCreate()`. */
  fun install(ctx: Context) {
    if (!installed.compareAndSet(false, true)) return
    val app = ctx.applicationContext
    try {
      val prev = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
          val sw = StringWriter()
          throwable.printStackTrace(PrintWriter(sw))
          val reason = "thread ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}".take(200)
          writeReportFile(app, kind = "java", reason = reason, text = sw.toString())
        } catch (_: Throwable) {
        }
        prev?.uncaughtException(thread, throwable)
      }
    } catch (t: Throwable) {
      installed.set(false)
      Log.w(TAG, "crash handler not installed", t)
    }
  }

  /** Persists a breadcrumb tag (e.g. `"cams-open"`) so even native crashes record where the app died. */
  fun setContextTag(ctx: Context, tag: String) {
    sp(ctx).edit()
      .putString(KEY_CONTEXT_TAG, tag)
      .putLong(KEY_CONTEXT_TAG_TS, System.currentTimeMillis())
      .apply()
  }

  fun clearContextTag(ctx: Context) {
    sp(ctx).edit()
      .remove(KEY_CONTEXT_TAG)
      .remove(KEY_CONTEXT_TAG_TS)
      .apply()
  }

  private fun currentWhere(ctx: Context): String {
    val dynamic = try {
      contextTagProvider?.invoke(ctx)?.trim().orEmpty()
    } catch (_: Throwable) {
      ""
    }
    if (dynamic.isNotEmpty()) return dynamic
    return sp(ctx).getString(KEY_CONTEXT_TAG, null).orEmpty()
  }

  private fun writeReportFile(ctx: Context, kind: String, reason: String, text: String) {
    val ts = System.currentTimeMillis()
    val fileName = "$ts-$kind.json"
    val report = CrashReport(
      id = "file:$fileName",
      kind = kind,
      ts = ts / 1000.0,
      reason = reason,
      where = currentWhere(ctx),
      text = text.take(MAX_TEXT_CHARS),
    )
    File(crashDir(ctx), fileName).writeText(
      PlatformHttp.json.encodeToString(CrashReport.serializer(), report),
    )
  }

  /** Lists crash files saved under `filesDir/crashes/`, newest first for Settings display. */
  fun listSavedFiles(ctx: Context): List<CrashReport> {
    val files = crashDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
      ?.sortedByDescending { it.name }
      ?: return emptyList()
    val out = mutableListOf<CrashReport>()
    for (f in files) {
      try {
        val parsed = PlatformHttp.json.decodeFromString(CrashReport.serializer(), f.readText())
        out += parsed.copy(id = "file:${f.name}")
      } catch (_: Exception) {
        f.delete()
      }
    }
    return out
  }

  /** Returns all pending crash reports (local files + unacked `ApplicationExitInfo` records). */
  fun pending(ctx: Context): List<CrashReport> {
    val files = listSavedFiles(ctx).reversed() // oldest first for upload ordering
    val exits = readProcessExits(ctx)
    return files + exits
  }

  /** Reads a single crash report by ID (`file:...` or `exit:...`), or null if not found. */
  fun readReport(ctx: Context, id: String): CrashReport? {
    if (id.startsWith("file:")) {
      val safeName = id.removePrefix("file:").replace("/", "").replace("\\", "")
      val f = File(crashDir(ctx), safeName)
      if (!f.isFile) return null
      return try {
        PlatformHttp.json.decodeFromString(CrashReport.serializer(), f.readText())
          .copy(id = "file:${f.name}")
      } catch (_: Exception) {
        null
      }
    }
    if (id.startsWith("exit:")) {
      return readProcessExits(ctx).firstOrNull { it.id == id }
    }
    return null
  }

  /** Deletes all local crash files and advances the `ApplicationExitInfo` high-water mark. */
  fun clearAll(ctx: Context) {
    crashDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { it.delete() }
    val latestExitTs = latestHistoricalExitTimestamp(ctx)
    val prev = sp(ctx).getLong(KEY_EXIT_TS, 0L)
    val nextMark = maxOf(prev, latestExitTs, System.currentTimeMillis())
    sp(ctx).edit().putLong(KEY_EXIT_TS, nextMark).apply()
  }

  /** Marks uploaded crash reports as acknowledged (deletes file entries, advances exit timestamp). */
  fun ack(ctx: Context, ids: List<String>) {
    var exitTs = sp(ctx).getLong(KEY_EXIT_TS, 0L)
    for (id in ids) {
      when {
        id.startsWith("file:") -> {
          val safeName = id.removePrefix("file:").replace("/", "").replace("\\", "")
          File(crashDir(ctx), safeName).delete()
        }
        id.startsWith("exit:") -> {
          id.removePrefix("exit:").toLongOrNull()?.let { if (it > exitTs) exitTs = it }
        }
      }
    }
    sp(ctx).edit().putLong(KEY_EXIT_TS, exitTs).apply()
    Log.i(TAG, "acked ${ids.size} crash report(s)")
  }

  /** Returns the timestamp (ms) of the previous process exit if it was a crash/ANR, else 0. */
  fun lastExitWasCrash(ctx: Context): Long {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0L
    val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0L
    val info = try {
      am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
    } catch (_: Exception) {
      null
    } ?: return 0L
    return if (
      info.reason == ApplicationExitInfo.REASON_CRASH ||
      info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
      info.reason == ApplicationExitInfo.REASON_ANR
    ) {
      info.timestamp
    } else {
      0L
    }
  }

  /**
   * Best-effort upload of pending crash reports to `POST /pc/api/crash`, acknowledging each
   * uploaded report so it is sent only once. Never throws.
   */
  suspend fun uploadPending(
    ctx: Context,
    client: OkHttpClient = PlatformHttp.defaultClient,
    onlyOncePerProcess: Boolean = true,
  ): Int {
    if (onlyOncePerProcess && !uploadStarted.compareAndSet(false, true)) return 0
    return withContext(Dispatchers.IO) {
      try {
        val reports = pending(ctx)
        if (reports.isEmpty()) return@withContext 0
        val uploadClient = client.newBuilder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .build()
        val appTarget = PlatformPrefs.appBinaryTarget(ctx)
        val device = PlatformPrefs.deviceName()
        val androidSdk = Build.VERSION.SDK_INT.toString()

        val done = mutableListOf<String>()
        for (report in reports) {
          try {
            val payload = PlatformHttp.json.encodeToString(
              CrashUploadPayload.serializer(),
              CrashUploadPayload(
                id = report.id,
                kind = report.kind,
                ts = report.ts,
                reason = report.reason,
                where = report.where,
                text = report.text,
                traceB64 = report.traceB64,
                app = appTarget,
                device = device,
                android = androidSdk,
              ),
            )
            val req = PlatformHttp.apiRequestBuilder(ctx, "/pc/api/crash")
              .post(payload.toRequestBody(JSON_MEDIA))
              .header("Accept", "application/json")
              .build()
            uploadClient.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            }
            done += report.id
          } catch (_: Exception) {
            // Stop on first network failure and retry remaining reports next launch.
            break
          }
        }
        if (done.isNotEmpty()) {
          ack(ctx, done)
        }
        done.size
      } catch (_: Exception) {
        0
      }
    }
  }

  private fun latestHistoricalExitTimestamp(ctx: Context): Long {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0L
    return latestHistoricalExitTimestampApi30(ctx)
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun latestHistoricalExitTimestampApi30(ctx: Context): Long {
    val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0L
    return try {
      am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()?.timestamp ?: 0L
    } catch (_: Exception) {
      0L
    }
  }

  private fun readProcessExits(ctx: Context): List<CrashReport> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return readProcessExitsApi30(ctx)
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun readProcessExitsApi30(ctx: Context): List<CrashReport> {
    val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
    val prefs = sp(ctx)
    val since = prefs.getLong(KEY_EXIT_TS, 0L)
    val savedTag = prefs.getString(KEY_CONTEXT_TAG, null).orEmpty()
    val savedTagTs = prefs.getLong(KEY_CONTEXT_TAG_TS, 0L)

    val infos = try {
      am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
    } catch (_: Exception) {
      return emptyList()
    }

    return infos.filter { it.timestamp > since }.mapNotNull { info ->
      val kind = when (info.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "java"
        else -> return@mapNotNull null
      }
      val trace = try {
        info.traceInputStream?.use { s -> readUpTo(s, MAX_TRACE_BYTES) }
      } catch (_: Exception) {
        null
      }
      val descPart = if (info.description != null) ": ${info.description}" else ""
      val where = if (savedTag.isNotEmpty() && savedTagTs > 0L && info.timestamp >= savedTagTs) {
        savedTag
      } else {
        ""
      }
      CrashReport(
        id = "exit:${info.timestamp}",
        kind = kind,
        ts = info.timestamp / 1000.0,
        reason = "${reasonName(info.reason)} status ${info.status}$descPart".take(200),
        where = where,
        text = "ApplicationExitInfo: $info",
        traceB64 = trace?.let { Base64.getEncoder().encodeToString(it) },
      )
    }
  }

  private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < maxBytes) {
      val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun reasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    else -> "reason $reason"
  }
}
