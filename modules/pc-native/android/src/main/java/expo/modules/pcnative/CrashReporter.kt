package expo.modules.pcnative

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash reports, uploaded by the app on its next start (src/lib/crash.ts: POST /pc/api/crash, then ack()):
 * - Java: a default uncaught-exception handler writes the stack to filesDir/crashes/<ts>-java.json, then hands
 *   over to the previous handler (Android's dialog still shows). A fatal JS error in a release build ends up here
 *   too (React Native rethrows it as a JavascriptException with the JS stack in its message).
 * - Native crashes (SIGABRT from a libwebrtc CHECK, SIGSEGV, …) and ANRs can't be caught in-process: they come
 *   from Android's ApplicationExitInfo on the next start (API 30+), with the tombstone (protobuf; `strings` shows
 *   the backtrace and abort message) or the ANR trace when Android kept one.
 * Every report says whether Cat cams was opening (CamsWebrtc's marker) when it happened.
 */
object CrashReporter {
  private const val TAG = "PcCrash"
  private const val FILE = "pc_crash"
  private const val MAX_TRACE = 768 * 1024

  private fun dir(ctx: Context) = File(ctx.filesDir, "crashes").apply { mkdirs() }
  private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  fun install(ctx: Context) = try {
    installHandler(ctx.applicationContext)
  } catch (t: Throwable) {
    Log.w(TAG, "crash handler not installed", t)
  }

  private fun installHandler(app: Context) {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
      try {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        write(app, "java", "thread ${thread.name}: ${e.javaClass.name}: ${e.message}".take(200), sw.toString())
      } catch (_: Throwable) {
      }
      prev?.uncaughtException(thread, e)
    }
  }

  private fun where(ctx: Context) = try {
    if (CamsWebrtc.isOpening(ctx)) "cams-open" else ""
  } catch (_: Throwable) {
    ""
  }

  private fun write(ctx: Context, kind: String, reason: String, text: String) {
    val ts = System.currentTimeMillis()
    val o = JSONObject()
      .put("kind", kind)
      .put("ts", ts / 1000.0)
      .put("reason", reason)
      .put("where", where(ctx))
      .put("text", text.take(200_000))
    File(dir(ctx), "$ts-$kind.json").writeText(o.toString())
  }

  /** Reports not uploaded yet: our own files + Android's exit records since the last ack. */
  fun pending(ctx: Context): List<Map<String, Any?>> {
    val out = mutableListOf<Map<String, Any?>>()
    for (f in dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()) {
      try {
        val o = JSONObject(f.readText())
        out += mapOf(
          "id" to "file:${f.name}", "kind" to o.optString("kind"), "ts" to o.optDouble("ts"),
          "reason" to o.optString("reason"), "where" to o.optString("where"), "text" to o.optString("text"),
        )
      } catch (e: Exception) {
        f.delete()
      }
    }
    out += exits(ctx)
    return out
  }

  private fun exits(ctx: Context): List<Map<String, Any?>> {
    val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
    val since = sp(ctx).getLong("exitTs", 0)
    val infos = try {
      am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
    } catch (e: Exception) {
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
        info.traceInputStream?.use { s -> readUpTo(s, MAX_TRACE) }
      } catch (e: Exception) {
        null
      }
      mapOf(
        "id" to "exit:${info.timestamp}",
        "kind" to kind,
        "ts" to info.timestamp / 1000.0,
        "reason" to "${reasonName(info.reason)} status ${info.status}${if (info.description != null) ": ${info.description}" else ""}".take(200),
        // the marker read at this start belongs to the run that died
        "where" to if (CamsWebrtc.crashedWhileOpening != 0L && info.timestamp >= CamsWebrtc.crashedWhileOpening) "cams-open" else "",
        "text" to "ApplicationExitInfo: ${info}",
        "trace_b64" to trace?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
      )
    }
  }

  // InputStream.readNBytes is API 33+, minSdk is 30
  private fun readUpTo(s: java.io.InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
      val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
      if (n < 0) break
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  private fun reasonName(r: Int) = when (r) {
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    else -> "reason $r"
  }

  /** The last exit of the previous run was a crash (any kind), ms timestamp, or 0. */
  fun lastExitWasCrash(ctx: Context): Long {
    val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0
    val info = try {
      am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
    } catch (e: Exception) {
      null
    } ?: return 0
    return if (info.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) info.timestamp else 0
  }

  /** Uploaded: forget these (file ids are deleted, exit ids move the high-water mark). */
  fun ack(ctx: Context, ids: List<String>) {
    var exitTs = sp(ctx).getLong("exitTs", 0)
    for (id in ids) {
      when {
        id.startsWith("file:") -> File(dir(ctx), id.removePrefix("file:").replace("/", "")).delete()
        id.startsWith("exit:") -> id.removePrefix("exit:").toLongOrNull()?.let { if (it > exitTs) exitTs = it }
      }
    }
    sp(ctx).edit().putLong("exitTs", exitTs).apply()
    Log.i(TAG, "acked ${ids.size} crash report(s)")
  }
}
