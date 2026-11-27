package com.neekhar.pccontrol.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class SemanticVersion(
  val major: Int,
  val minor: Int,
  val patch: Int,
  val preRelease: String? = null,
) : Comparable<SemanticVersion> {
  override fun compareTo(other: SemanticVersion): Int {
    if (major != other.major) return major.compareTo(other.major)
    if (minor != other.minor) return minor.compareTo(other.minor)
    if (patch != other.patch) return patch.compareTo(other.patch)
    val a = preRelease
    val b = other.preRelease
    if (a == b) return 0
    if (a == null) return 1 // stable > pre-release
    if (b == null) return -1
    val aParts = a.split('.')
    val bParts = b.split('.')
    val len = maxOf(aParts.size, bParts.size)
    for (i in 0 until len) {
      val ap = aParts.getOrNull(i) ?: return -1
      val bp = bParts.getOrNull(i) ?: return 1
      val an = ap.toIntOrNull()
      val bn = bp.toIntOrNull()
      val cmp = when {
        an != null && bn != null -> an.compareTo(bn)
        an != null -> -1
        bn != null -> 1
        else -> ap.compareTo(bp)
      }
      if (cmp != 0) return cmp
    }
    return 0
  }
}

@Serializable
data class ApkRelease(
  val versionName: String,
  val versionCode: Int,
  val sha256: String = "",
  val size: Long = 0L,
  val downloadUrl: String? = null,
  val releaseNotes: String? = null,
)

data class ApkFetchResult(
  val release: ApkRelease,
  val fromMirror: Boolean,
)

data class ApkCheckResult(
  val release: ApkRelease,
  val fromMirror: Boolean,
  val hasUpdate: Boolean,
  val downloadUrl: String,
)

sealed interface ApkUpdateState {
  data object Idle : ApkUpdateState
  data object Checking : ApkUpdateState
  data class UpToDate(
    val currentVersionName: String,
    val currentVersionCode: Long,
    val latest: ApkRelease,
  ) : ApkUpdateState

  data class UpdateAvailable(
    val release: ApkRelease,
    val fromMirror: Boolean,
    val downloadUrl: String,
  ) : ApkUpdateState

  data class Downloading(
    val release: ApkRelease,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progress: Float,
  ) : ApkUpdateState

  data class Verifying(val release: ApkRelease) : ApkUpdateState

  data class ReadyToInstall(
    val release: ApkRelease,
    val apkFile: File,
  ) : ApkUpdateState

  data class Error(val message: String) : ApkUpdateState
}

sealed interface InstallLaunchResult {
  data object Launched : InstallLaunchResult
  data class PermissionRequired(val settingsIntent: Intent) : InstallLaunchResult
  data class Failed(val message: String) : InstallLaunchResult
}

/**
 * Pure Kotlin APK release parsing, version comparison, and GitHub-primary / Tailnet-mirror-fallback
 * selection logic ported from `src/lib/apkUpdateLogic.ts` and `src/lib/otaManifest.ts`.
 * Contains zero Android framework dependencies so JVM unit tests can exercise it directly.
 */
object ApkUpdateLogic {
  const val DEFAULT_GITHUB_REPO = "ayushneekhar/pc-control"
  private val SEMVER_RE = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")
  private val SHA256_HEX_RE = Regex("^[0-9a-fA-F]{64}$")

  fun githubVersionUrl(repo: String = DEFAULT_GITHUB_REPO): String =
    "https://github.com/$repo/releases/latest/download/version.json"

  fun githubApkUrl(repo: String = DEFAULT_GITHUB_REPO): String =
    "https://github.com/$repo/releases/latest/download/pc-control.apk"

  fun githubReleasesApiUrl(repo: String = DEFAULT_GITHUB_REPO): String =
    "https://api.github.com/repos/$repo/releases/latest"

  fun githubLatestPageUrl(repo: String = DEFAULT_GITHUB_REPO): String =
    "https://github.com/$repo/releases/latest"

  fun mirrorVersionUrl(serverUrl: String): String =
    "${serverUrl.trimEnd('/')}/pc/api/ota/apk/version.json"

  fun mirrorApkUrl(serverUrl: String): String =
    "${serverUrl.trimEnd('/')}/pc/api/ota/apk/pc-control.apk"

  fun parseSemanticVersion(raw: String): SemanticVersion? {
    val m = SEMVER_RE.matchEntire(raw.trim()) ?: return null
    val major = m.groupValues[1].toIntOrNull() ?: return null
    val minor = m.groupValues[2].toIntOrNull() ?: return null
    val patch = m.groupValues[3].toIntOrNull() ?: return null
    val pre = m.groupValues[4].takeIf { it.isNotEmpty() }
    return SemanticVersion(major, minor, patch, pre)
  }

  /**
   * Compares two semver strings (`< 0` if [a] < [b], `0` if equal, `> 0` if [a] > [b]).
   * Falls back to lexicographic comparison if either string is non-semver.
   */
  fun compareVersions(a: String, b: String): Int {
    val va = parseSemanticVersion(a)
    val vb = parseSemanticVersion(b)
    if (va != null && vb != null) return va.compareTo(vb)
    return a.trim().removePrefix("v").compareTo(b.trim().removePrefix("v"))
  }

  /**
   * Returns true if [remote] is strictly newer than the installed build.
   * Compares `versionCode` first when both sides have a positive code, breaking ties with semver.
   * When `remote.versionCode <= 0` (e.g., parsed from a GitHub Release tag without `version.json`),
   * compares semantic versions directly.
   */
  fun isNewerRelease(
    currentVersionName: String,
    currentVersionCode: Long,
    remote: ApkRelease,
  ): Boolean {
    if (remote.versionCode > 0 && currentVersionCode > 0L) {
      if (remote.versionCode.toLong() > currentVersionCode) return true
      if (remote.versionCode.toLong() < currentVersionCode) return false
    }
    val curVer = parseSemanticVersion(currentVersionName)
    val remVer = parseSemanticVersion(remote.versionName)
    if (curVer != null && remVer != null) {
      return remVer > curVer
    }
    return false
  }

  /** Selects GitHub Releases by default; uses the laptop Tailnet mirror only if the version check fell back to it. */
  fun resolveApkDownloadUrl(
    githubApkUrl: String,
    mirrorApkUrl: String?,
    lastApkFromMirror: Boolean,
  ): String = if (lastApkFromMirror && !mirrorApkUrl.isNullOrBlank()) mirrorApkUrl else githubApkUrl

  fun httpProblem(what: String, status: Int): String = "Couldn't check for $what (HTTP $status)"

  /** Parses `version.json` matching `scripts/release-apk.sh` and `src/lib/otaManifest.ts`. */
  fun parseApkVersionJson(status: Int, body: String): Result<ApkRelease> {
    if (status !in 200..299) {
      return Result.failure(IllegalStateException(httpProblem("app releases", status)))
    }
    val root = try {
      PlatformHttp.json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
      null
    } ?: return Result.failure(
      IllegalStateException("Couldn't check for app releases (not a valid version file)"),
    )

    val versionName = (root["versionName"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.contentOrNull
      ?.trim()
    val versionCodePrim = root["versionCode"] as? JsonPrimitive
    val versionCode = versionCodePrim
      ?.takeIf { !it.isString }
      ?.intOrNull

    if (versionName == null || parseSemanticVersion(versionName) == null || versionCode == null || versionCode < 1) {
      return Result.failure(
        IllegalStateException("Couldn't check for app releases (version file has no valid version)"),
      )
    }

    val sha256 = (root["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
    val size = (root["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
    val downloadUrl = (root["downloadUrl"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.contentOrNull
      ?.trim()
      ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val releaseNotes = (root["releaseNotes"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.contentOrNull
      ?.trim()
      ?.takeIf { it.isNotEmpty() }

    return Result.success(
      ApkRelease(
        versionName = versionName.removePrefix("v"),
        versionCode = versionCode,
        sha256 = sha256,
        size = size,
        downloadUrl = downloadUrl,
        releaseNotes = releaseNotes,
      ),
    )
  }

  /**
   * Parses GitHub's `/repos/<owner>/<repo>/releases/latest` JSON payload when `version.json`
   * is not attached to a release.
   */
  fun parseGithubReleaseApiJson(status: Int, body: String): Result<ApkRelease> {
    if (status !in 200..299) {
      return Result.failure(IllegalStateException(httpProblem("app releases", status)))
    }
    val root = try {
      PlatformHttp.json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
      null
    } ?: return Result.failure(
      IllegalStateException("Couldn't check for app releases (not a valid GitHub release)"),
    )

    val tagName = (root["tag_name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val parsedVer = parseSemanticVersion(tagName)
      ?: return Result.failure(IllegalStateException("Couldn't check for app releases (invalid release tag)"))

    val assets = try {
      root["assets"]?.jsonArray.orEmpty()
    } catch (_: Exception) {
      emptyList()
    }
    val apkAsset = assets.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
      .firstOrNull { obj ->
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
        name.endsWith(".apk")
      }
      ?: return Result.failure(IllegalStateException("Latest release has no APK asset"))

    val downloadUrl = (apkAsset["browser_download_url"] as? JsonPrimitive)?.contentOrNull?.trim()
      ?.takeIf { it.startsWith("https://") }
      ?: return Result.failure(IllegalStateException("Latest APK asset has no download URL"))
    val size = (apkAsset["size"] as? JsonPrimitive)?.longOrNull ?: 0L
    val rawDigest = (apkAsset["digest"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val sha256 = rawDigest.removePrefix("sha256:").takeIf { SHA256_HEX_RE.matches(it) }.orEmpty()
    val notes = (root["body"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    val normalizedName = buildString {
      append("${parsedVer.major}.${parsedVer.minor}.${parsedVer.patch}")
      if (parsedVer.preRelease != null) append("-${parsedVer.preRelease}")
    }

    return Result.success(
      ApkRelease(
        versionName = normalizedName,
        versionCode = 0,
        sha256 = sha256,
        size = size,
        downloadUrl = downloadUrl,
        releaseNotes = notes,
      ),
    )
  }

  /**
   * Fetches latest APK metadata: GitHub Releases primary, falling back to the laptop Tailnet
   * mirror only when GitHub fails or returns an unusable response.
   */
  suspend fun fetchLatestApk(
    githubVersionUrl: String,
    mirrorVersionUrl: String?,
    fetchGithub: suspend (String) -> Pair<Int, String>,
    fetchMirror: suspend (String) -> Pair<Int, String>,
    warn: ((String) -> Unit)? = null,
  ): ApkFetchResult {
    var ghErr: String
    try {
      val (status, body) = fetchGithub(githubVersionUrl)
      val parsed = parseApkVersionJson(status, body)
      if (parsed.isSuccess) {
        return ApkFetchResult(release = parsed.getOrThrow(), fromMirror = false)
      }
      ghErr = parsed.exceptionOrNull()?.message ?: "invalid version file"
      if (!mirrorVersionUrl.isNullOrBlank()) {
        warn?.invoke("[apk-update] GitHub APK version unusable ($ghErr); falling back to laptop mirror")
      }
    } catch (e: Exception) {
      ghErr = e.message ?: e.javaClass.simpleName
      if (!mirrorVersionUrl.isNullOrBlank()) {
        warn?.invoke("[apk-update] GitHub APK check failed ($ghErr); falling back to laptop mirror")
      }
    }

    var mirrorErr: String? = null
    if (!mirrorVersionUrl.isNullOrBlank()) {
      try {
        val (status, body) = fetchMirror(mirrorVersionUrl)
        val parsed = parseApkVersionJson(status, body)
        if (parsed.isSuccess) {
          return ApkFetchResult(release = parsed.getOrThrow(), fromMirror = true)
        }
        mirrorErr = parsed.exceptionOrNull()?.message ?: "invalid version file"
      } catch (e: Exception) {
        mirrorErr = e.message ?: e.javaClass.simpleName
      }
    }

    val combined = if (mirrorErr != null) "$ghErr (laptop mirror: $mirrorErr)" else ghErr
    throw IllegalStateException(combined)
  }

  fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    DigestInputStream(file.inputStream().buffered(), md).use { stream ->
      val buf = ByteArray(64 * 1024)
      while (stream.read(buf) != -1) {
        // streaming hash
      }
    }
    val hex = "0123456789abcdef"
    val digest = md.digest()
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
      val v = b.toInt() and 0xff
      sb.append(hex[v ushr 4])
      sb.append(hex[v and 0x0f])
    }
    return sb.toString()
  }

  fun verifySha256(file: File, expectedSha256: String): Boolean {
    val clean = expectedSha256.trim().removePrefix("sha256:").lowercase()
    if (!SHA256_HEX_RE.matches(clean)) return false
    if (!file.isFile) return false
    return sha256Hex(file).equals(clean, ignoreCase = true)
  }
}

/**
 * Native Android APK updater replacing Nitro OTA.
 *
 * Checks GitHub Releases (`ayushneekhar/pc-control`) with automatic Tailnet mirror fallback,
 * downloads the APK while emitting progress over [state], verifies SHA-256 when provided, and
 * launches the system installer via `FileProvider` (`ACTION_VIEW`) while handling
 * `canRequestPackageInstalls()` cleanly.
 */
class ApkUpdater(
  private val context: Context,
  private val client: OkHttpClient = PlatformHttp.downloadClient,
  private val repo: String = ApkUpdateLogic.DEFAULT_GITHUB_REPO,
  private val fileProviderAuthority: String = "${context.packageName}.fileprovider",
) {
  companion object {
    private const val TAG = "PcApkUpdater"
    private const val UPDATES_SUBDIR = "apk-updates"
  }

  private val appCtx = context.applicationContext
  private val _state = MutableStateFlow<ApkUpdateState>(ApkUpdateState.Idle)
  val state: StateFlow<ApkUpdateState> = _state.asStateFlow()

  @Volatile
  private var lastFromMirror: Boolean = false

  fun installedVersion(): Pair<String, Long> = try {
    val pi = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      pi.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      pi.versionCode.toLong()
    }
    (pi.versionName ?: "0.0.0") to code
  } catch (_: Exception) {
    "0.0.0" to 0L
  }

  fun reset() {
    _state.value = ApkUpdateState.Idle
  }

  suspend fun checkForUpdate(
    serverUrl: String? = PlatformPrefs.serverUrl(appCtx),
  ): Result<ApkCheckResult> {
    _state.value = ApkUpdateState.Checking
    val (curName, curCode) = installedVersion()
    val ghVersionUrl = ApkUpdateLogic.githubVersionUrl(repo)
    val ghApkUrl = ApkUpdateLogic.githubApkUrl(repo)
    val cleanServer = serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val mirrorVersion = cleanServer?.let { ApkUpdateLogic.mirrorVersionUrl(it) }
    val mirrorApk = cleanServer?.let { ApkUpdateLogic.mirrorApkUrl(it) }

    return withContext(Dispatchers.IO) {
      try {
        val fetched = try {
          ApkUpdateLogic.fetchLatestApk(
            githubVersionUrl = ghVersionUrl,
            mirrorVersionUrl = mirrorVersion,
            fetchGithub = { url -> fetchText(url, timeoutSec = 10) },
            fetchMirror = { url -> fetchText(url, timeoutSec = 5) },
            warn = { msg -> Log.w(TAG, msg) },
          )
        } catch (primaryErr: Exception) {
          // Fallback to GitHub Releases API if a release only attached an APK without version.json.
          val (apiStatus, apiBody) = try {
            fetchText(ApkUpdateLogic.githubReleasesApiUrl(repo), timeoutSec = 10)
          } catch (_: Exception) {
            throw primaryErr
          }
          val apiParsed = ApkUpdateLogic.parseGithubReleaseApiJson(apiStatus, apiBody)
          if (apiParsed.isSuccess) {
            ApkFetchResult(release = apiParsed.getOrThrow(), fromMirror = false)
          } else {
            throw primaryErr
          }
        }
        lastFromMirror = fetched.fromMirror
        val release = fetched.release
        val resolvedUrl = release.downloadUrl
          ?: ApkUpdateLogic.resolveApkDownloadUrl(ghApkUrl, mirrorApk, fetched.fromMirror)
        val hasUpdate = ApkUpdateLogic.isNewerRelease(curName, curCode, release)
        val result = ApkCheckResult(
          release = release,
          fromMirror = fetched.fromMirror,
          hasUpdate = hasUpdate,
          downloadUrl = resolvedUrl,
        )
        _state.value = if (hasUpdate) {
          ApkUpdateState.UpdateAvailable(release, fetched.fromMirror, resolvedUrl)
        } else {
          ApkUpdateState.UpToDate(curName, curCode, release)
        }
        Result.success(result)
      } catch (e: Exception) {
        val msg = e.message ?: "Couldn't check for app updates"
        _state.value = ApkUpdateState.Error(msg)
        Result.failure(e)
      }
    }
  }

  suspend fun downloadUpdate(
    release: ApkRelease,
    downloadUrl: String,
  ): Result<File> = withContext(Dispatchers.IO) {
    val updatesDir = File(appCtx.cacheDir, UPDATES_SUBDIR).apply { mkdirs() }
    val safeName = "pc-control-${release.versionName}-${release.versionCode}.apk"
      .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val targetFile = File(updatesDir, safeName)
    val partFile = File(updatesDir, "$safeName.part")

    try {
      // Clean up stale APKs from previous versions to keep cache lean.
      updatesDir.listFiles()?.forEach { f ->
        if (f.name != targetFile.name && f.name != partFile.name) {
          f.delete()
        }
      }

      // Reuse already-downloaded APK if its SHA-256 already matches.
      if (targetFile.isFile && release.sha256.isNotBlank() && ApkUpdateLogic.verifySha256(targetFile, release.sha256)) {
        _state.value = ApkUpdateState.ReadyToInstall(release, targetFile)
        return@withContext Result.success(targetFile)
      }

      _state.value = ApkUpdateState.Downloading(
        release = release,
        bytesDownloaded = 0L,
        totalBytes = release.size.coerceAtLeast(0L),
        progress = 0f,
      )

      val req = Request.Builder()
        .url(downloadUrl)
        .get()
        .header("Cache-Control", "no-cache")
        .build()

      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IllegalStateException("APK download failed (HTTP ${resp.code})")
        }
        val body = resp.body ?: throw IllegalStateException("Empty APK response body")
        val contentLen = body.contentLength().takeIf { it > 0L } ?: release.size.coerceAtLeast(0L)

        var downloaded = 0L
        var lastEmitBytes = 0L
        val buf = ByteArray(32 * 1024)

        body.byteStream().use { input ->
          FileOutputStream(partFile).use { out ->
            while (true) {
              val read = input.read(buf)
              if (read < 0) break
              out.write(buf, 0, read)
              downloaded += read
              if (downloaded - lastEmitBytes >= 64 * 1024 || (contentLen > 0L && downloaded >= contentLen)) {
                lastEmitBytes = downloaded
                val progress = if (contentLen > 0L) {
                  (downloaded.toFloat() / contentLen.toFloat()).coerceIn(0f, 1f)
                } else {
                  0f
                }
                _state.value = ApkUpdateState.Downloading(
                  release = release,
                  bytesDownloaded = downloaded,
                  totalBytes = contentLen,
                  progress = progress,
                )
              }
            }
            out.flush()
          }
        }
      }

      if (release.sha256.isNotBlank()) {
        _state.value = ApkUpdateState.Verifying(release)
        if (!ApkUpdateLogic.verifySha256(partFile, release.sha256)) {
          partFile.delete()
          val err = IllegalStateException("APK SHA-256 verification failed; refusing to install.")
          _state.value = ApkUpdateState.Error(err.message!!)
          return@withContext Result.failure(err)
        }
      }

      if (targetFile.exists()) targetFile.delete()
      if (!partFile.renameTo(targetFile)) {
        partFile.copyTo(targetFile, overwrite = true)
        partFile.delete()
      }

      _state.value = ApkUpdateState.ReadyToInstall(release, targetFile)
      Result.success(targetFile)
    } catch (e: Exception) {
      partFile.delete()
      val msg = e.message ?: "APK download failed"
      _state.value = ApkUpdateState.Error(msg)
      Result.failure(e)
    }
  }

  fun canRequestPackageInstalls(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      appCtx.packageManager.canRequestPackageInstalls()
    } else {
      true
    }

  fun createUnknownSourcesSettingsIntent(): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appCtx.packageName}"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  fun createInstallIntent(apkFile: File): Intent {
    val apkUri = FileProvider.getUriForFile(appCtx, fileProviderAuthority, apkFile)
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(apkUri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  /**
   * Launches the Android package installer for [apkFile], cleanly handling missing
   * "Install unknown apps" permission by returning [InstallLaunchResult.PermissionRequired].
   */
  fun launchInstall(apkFile: File): InstallLaunchResult {
    if (!apkFile.isFile) {
      return InstallLaunchResult.Failed("Downloaded APK file is missing.")
    }
    if (!canRequestPackageInstalls()) {
      return InstallLaunchResult.PermissionRequired(createUnknownSourcesSettingsIntent())
    }
    return try {
      appCtx.startActivity(createInstallIntent(apkFile))
      InstallLaunchResult.Launched
    } catch (e: Exception) {
      InstallLaunchResult.Failed(e.message ?: "Could not launch APK installer.")
    }
  }

  private fun fetchText(url: String, timeoutSec: Long): Pair<Int, String> {
    val reqClient = client.newBuilder()
      .connectTimeout(timeoutSec, TimeUnit.SECONDS)
      .readTimeout(timeoutSec, TimeUnit.SECONDS)
      .build()
    val req = Request.Builder()
      .url(url)
      .get()
      .header("Accept", "application/json")
      .header("Cache-Control", "no-cache")
      .build()
    reqClient.newCall(req).execute().use { resp ->
      return resp.code to resp.body?.string().orEmpty()
    }
  }
}
