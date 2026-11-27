package com.neekhar.pccontrol.core.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ApkUpdaterTest {

  private companion object {
    const val GITHUB_VERSION_URL =
      "https://github.com/ayushneekhar/pc-control/releases/latest/download/version.json"
    const val GITHUB_APK_URL =
      "https://github.com/ayushneekhar/pc-control/releases/latest/download/pc-control.apk"
    const val MIRROR_VERSION_URL =
      "https://laptop.tailnet.ts.net/pc/api/ota/apk/version.json"
    const val MIRROR_APK_URL =
      "https://laptop.tailnet.ts.net/pc/api/ota/apk/pc-control.apk"

    const val VALID_APK_BODY = """
      {
        "versionName": "1.6.8",
        "versionCode": 28,
        "sha256": "abc123",
        "size": 42000000,
        "otaVersion": 61
      }
    """
  }

  @Test
  fun parseSemanticVersion_handlesStandardAndPreRelease() {
    assertEquals(SemanticVersion(1, 6, 9, null), ApkUpdateLogic.parseSemanticVersion("1.6.9"))
    assertEquals(SemanticVersion(2, 0, 0, null), ApkUpdateLogic.parseSemanticVersion("v2.0.0"))
    assertEquals(
      SemanticVersion(2, 1, 0, "beta.2"),
      ApkUpdateLogic.parseSemanticVersion("v2.1.0-beta.2"),
    )
    assertNull(ApkUpdateLogic.parseSemanticVersion("1.6"))
    assertNull(ApkUpdateLogic.parseSemanticVersion("not-a-version"))
    assertNull(ApkUpdateLogic.parseSemanticVersion(""))
  }

  @Test
  fun compareVersions_ordersMajorMinorPatchAndPreRelease() {
    assertTrue(ApkUpdateLogic.compareVersions("1.6.9", "1.6.10") < 0)
    assertTrue(ApkUpdateLogic.compareVersions("2.0.0", "1.99.99") > 0)
    assertEquals(0, ApkUpdateLogic.compareVersions("v1.6.9", "1.6.9"))
    assertTrue(ApkUpdateLogic.compareVersions("2.0.0-beta.1", "2.0.0") < 0)
    assertTrue(ApkUpdateLogic.compareVersions("2.0.0-beta.2", "2.0.0-beta.10") < 0)
    assertTrue(ApkUpdateLogic.compareVersions("2.0.0-rc.1", "2.0.0-beta.9") > 0)
  }

  @Test
  fun isNewerRelease_prefersVersionCodeAndFallsBackToSemver() {
    // Higher versionCode wins even with same versionName
    assertTrue(
      ApkUpdateLogic.isNewerRelease(
        currentVersionName = "1.6.9",
        currentVersionCode = 25,
        remote = ApkRelease(versionName = "1.6.9", versionCode = 26),
      ),
    )
    // Lower versionCode is never newer
    assertFalse(
      ApkUpdateLogic.isNewerRelease(
        currentVersionName = "1.6.9",
        currentVersionCode = 25,
        remote = ApkRelease(versionName = "1.7.0", versionCode = 24),
      ),
    )
    // Equal versionCode & equal versionName -> not newer
    assertFalse(
      ApkUpdateLogic.isNewerRelease(
        currentVersionName = "1.6.9",
        currentVersionCode = 25,
        remote = ApkRelease(versionName = "1.6.9", versionCode = 25),
      ),
    )
    // Equal versionCode but higher semver -> newer
    assertTrue(
      ApkUpdateLogic.isNewerRelease(
        currentVersionName = "1.6.9",
        currentVersionCode = 25,
        remote = ApkRelease(versionName = "1.7.0", versionCode = 25),
      ),
    )
    // Tag-only release (versionCode == 0) compares semver
    assertTrue(
      ApkUpdateLogic.isNewerRelease(
        currentVersionName = "1.6.9",
        currentVersionCode = 25,
        remote = ApkRelease(versionName = "2.0.0", versionCode = 0),
      ),
    )
    assertFalse(
      ApkUpdateLogic.isNewerRelease(
        currentVersionName = "2.0.0",
        currentVersionCode = 30,
        remote = ApkRelease(versionName = "1.9.0", versionCode = 0),
      ),
    )
  }

  @Test
  fun parseApkVersionJson_validatesStatusAndFields() {
    val ok = ApkUpdateLogic.parseApkVersionJson(200, VALID_APK_BODY)
    assertTrue(ok.isSuccess)
    val release = ok.getOrThrow()
    assertEquals("1.6.8", release.versionName)
    assertEquals(28, release.versionCode)
    assertEquals("abc123", release.sha256)
    assertEquals(42000000L, release.size)

    val httpErr = ApkUpdateLogic.parseApkVersionJson(503, "Service Unavailable")
    assertTrue(httpErr.isFailure)
    assertTrue(httpErr.exceptionOrNull()!!.message!!.contains("HTTP 503"))

    val badJson = ApkUpdateLogic.parseApkVersionJson(200, "<html>504</html>")
    assertTrue(badJson.isFailure)

    val badCode = ApkUpdateLogic.parseApkVersionJson(
      200,
      """{"versionName":"1.6.8","versionCode":0}""",
    )
    assertTrue(badCode.isFailure)

    val badName = ApkUpdateLogic.parseApkVersionJson(
      200,
      """{"versionName":"invalid","versionCode":28}""",
    )
    assertTrue(badName.isFailure)
  }

  @Test
  fun parseGithubReleaseApiJson_extractsApkAssetAndDigest() {
    val digest = "a".repeat(64)
    val body = """
      {
        "tag_name": "v2.0.1",
        "body": "Native Compose rewrite",
        "assets": [
          {
            "name": "version.json",
            "browser_download_url": "https://github.com/ayushneekhar/pc-control/releases/download/v2.0.1/version.json",
            "size": 128
          },
          {
            "name": "pc-control.apk",
            "browser_download_url": "https://github.com/ayushneekhar/pc-control/releases/download/v2.0.1/pc-control.apk",
            "size": 18500000,
            "digest": "sha256:$digest"
          }
        ]
      }
    """.trimIndent()
    val parsed = ApkUpdateLogic.parseGithubReleaseApiJson(200, body).getOrThrow()
    assertEquals("2.0.1", parsed.versionName)
    assertEquals(0, parsed.versionCode)
    assertEquals(digest, parsed.sha256)
    assertEquals(18500000L, parsed.size)
    assertEquals(
      "https://github.com/ayushneekhar/pc-control/releases/download/v2.0.1/pc-control.apk",
      parsed.downloadUrl,
    )
    assertEquals("Native Compose rewrite", parsed.releaseNotes)
  }

  @Test
  fun resolveApkDownloadUrl_defaultsToGithubAndOnlyUsesMirrorOnFallback() {
    assertEquals(
      GITHUB_APK_URL,
      ApkUpdateLogic.resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, false),
    )
    assertEquals(
      GITHUB_APK_URL,
      ApkUpdateLogic.resolveApkDownloadUrl(GITHUB_APK_URL, null, false),
    )
    assertEquals(
      MIRROR_APK_URL,
      ApkUpdateLogic.resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, true),
    )
    assertEquals(
      GITHUB_APK_URL,
      ApkUpdateLogic.resolveApkDownloadUrl(GITHUB_APK_URL, null, true),
    )
  }

  @Test
  fun fetchLatestApk_triesGithubFirstAndSkipsMirrorOnSuccess() = runBlocking {
    val calls = mutableListOf<String>()
    val res = ApkUpdateLogic.fetchLatestApk(
      githubVersionUrl = GITHUB_VERSION_URL,
      mirrorVersionUrl = MIRROR_VERSION_URL,
      fetchGithub = { url ->
        calls += "github:$url"
        200 to VALID_APK_BODY
      },
      fetchMirror = { url ->
        calls += "mirror:$url"
        error("mirror should not be called")
      },
    )
    assertEquals(listOf("github:$GITHUB_VERSION_URL"), calls)
    assertFalse(res.fromMirror)
    assertEquals("1.6.8", res.release.versionName)
    assertEquals(28, res.release.versionCode)
    assertEquals(
      GITHUB_APK_URL,
      ApkUpdateLogic.resolveApkDownloadUrl(GITHUB_APK_URL, MIRROR_APK_URL, res.fromMirror),
    )
  }

  @Test
  fun fetchLatestApk_fallsBackToMirrorWhenGithubThrows() = runBlocking {
    val calls = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val res = ApkUpdateLogic.fetchLatestApk(
      githubVersionUrl = GITHUB_VERSION_URL,
      mirrorVersionUrl = MIRROR_VERSION_URL,
      fetchGithub = { url ->
        calls += "github:$url"
        throw RuntimeException("network timeout")
      },
      fetchMirror = { url ->
        calls += "mirror:$url"
        200 to VALID_APK_BODY
      },
      warn = { warnings += it },
    )
    assertEquals(listOf("github:$GITHUB_VERSION_URL", "mirror:$MIRROR_VERSION_URL"), calls)
    assertTrue(res.fromMirror)
    assertEquals("1.6.8", res.release.versionName)
    assertEquals(1, warnings.size)
    assertTrue(warnings[0].contains("falling back to laptop mirror", ignoreCase = true))
  }

  @Test
  fun fetchLatestApk_fallsBackToMirrorWhenGithubReturns503() = runBlocking {
    val calls = mutableListOf<String>()
    val res = ApkUpdateLogic.fetchLatestApk(
      githubVersionUrl = GITHUB_VERSION_URL,
      mirrorVersionUrl = MIRROR_VERSION_URL,
      fetchGithub = { url ->
        calls += "github:$url"
        503 to "Service Unavailable"
      },
      fetchMirror = { url ->
        calls += "mirror:$url"
        200 to VALID_APK_BODY
      },
    )
    assertEquals(listOf("github:$GITHUB_VERSION_URL", "mirror:$MIRROR_VERSION_URL"), calls)
    assertTrue(res.fromMirror)
  }

  @Test
  fun fetchLatestApk_throwsCombinedErrorWhenBothFail() = runBlocking {
    var thrown: Throwable? = null
    try {
      ApkUpdateLogic.fetchLatestApk(
        githubVersionUrl = GITHUB_VERSION_URL,
        mirrorVersionUrl = MIRROR_VERSION_URL,
        fetchGithub = { throw RuntimeException("GitHub unreachable") },
        fetchMirror = { throw RuntimeException("Tailnet offline") },
      )
    } catch (e: Throwable) {
      thrown = e
    }
    assertNotNull(thrown)
    assertEquals("GitHub unreachable (laptop mirror: Tailnet offline)", thrown!!.message)
  }

  @Test
  fun fetchLatestApk_throwsGithubErrorAloneWhenNoMirrorConfigured() = runBlocking {
    var thrown: Throwable? = null
    try {
      ApkUpdateLogic.fetchLatestApk(
        githubVersionUrl = GITHUB_VERSION_URL,
        mirrorVersionUrl = null,
        fetchGithub = { throw RuntimeException("GitHub unreachable") },
        fetchMirror = { error("should not be called") },
      )
    } catch (e: Throwable) {
      thrown = e
    }
    assertNotNull(thrown)
    assertEquals("GitHub unreachable", thrown!!.message)
  }

  @Test
  fun verifySha256_validatesMatchingAndMismatchedHashes() {
    val tmp = File.createTempFile("pc-control-test-", ".apk")
    try {
      tmp.writeBytes("hello apk payload".toByteArray(Charsets.UTF_8))
      val actual = ApkUpdateLogic.sha256Hex(tmp)
      assertEquals(64, actual.length)
      assertTrue(ApkUpdateLogic.verifySha256(tmp, actual))
      assertTrue(ApkUpdateLogic.verifySha256(tmp, actual.uppercase()))
      assertTrue(ApkUpdateLogic.verifySha256(tmp, "sha256:$actual"))
      assertFalse(ApkUpdateLogic.verifySha256(tmp, "0".repeat(64)))
      assertFalse(ApkUpdateLogic.verifySha256(tmp, "not-a-sha256"))
    } finally {
      tmp.delete()
    }
  }

  @Test
  fun isDownloadBusy_trueOnlyForDownloadingAndVerifying() {
    val rel = ApkRelease(versionName = "2.1.1", versionCode = 28)
    assertTrue(ApkUpdateLogic.isDownloadBusy(ApkUpdateState.Downloading(rel, 100L, 1000L, 0.1f)))
    assertTrue(ApkUpdateLogic.isDownloadBusy(ApkUpdateState.Verifying(rel)))
    assertFalse(ApkUpdateLogic.isDownloadBusy(ApkUpdateState.Idle))
    assertFalse(ApkUpdateLogic.isDownloadBusy(ApkUpdateState.Checking))
    assertFalse(ApkUpdateLogic.isDownloadBusy(ApkUpdateState.UpdateAvailable(rel, false, GITHUB_APK_URL)))
    assertFalse(ApkUpdateLogic.isDownloadBusy(ApkUpdateState.ReadyToInstall(rel, File("/tmp/a.apk"))))
    assertFalse(ApkUpdateLogic.isDownloadBusy(ApkUpdateState.Error("boom")))
  }

  @Test
  fun installCoordinator_singleFlightIgnoresDuplicateTapsDuringDownloadAndVerify() = runBlocking {
    val rel = ApkRelease(versionName = "2.1.1", versionCode = 28)
    val state = MutableStateFlow<ApkUpdateState>(ApkUpdateState.Idle)
    val downloadStarted = CompletableDeferred<Unit>()
    val allowFinish = CompletableDeferred<Unit>()
    val downloadCalls = AtomicInteger(0)
    val installCalls = AtomicInteger(0)
    val fakeApk = File("/tmp/pc-control-2.1.1.apk")

    val coordinator = ApkInstallCoordinator(
      currentState = { state.value },
      setError = { state.value = ApkUpdateState.Error(it) },
      resolveRelease = { url -> rel to url },
      download = { r, _ ->
        downloadCalls.incrementAndGet()
        state.value = ApkUpdateState.Downloading(r, 1024L, 2048L, 0.5f)
        downloadStarted.complete(Unit)
        allowFinish.await()
        state.value = ApkUpdateState.ReadyToInstall(r, fakeApk)
        Result.success(fakeApk)
      },
      launchInstall = {
        installCalls.incrementAndGet()
        InstallActionResult.Launched
      },
      isFilePresent = { true },
    )

    val first = async { coordinator.startOrInstall(GITHUB_APK_URL) }
    downloadStarted.await()

    // Duplicate tap while Downloading must be ignored.
    assertFalse(coordinator.startOrInstall(GITHUB_APK_URL))
    // Duplicate tap while Verifying must also be ignored.
    state.value = ApkUpdateState.Verifying(rel)
    assertFalse(coordinator.startOrInstall(GITHUB_APK_URL))

    allowFinish.complete(Unit)
    assertTrue(first.await())
    assertEquals(1, downloadCalls.get())
    assertEquals(1, installCalls.get())
  }

  @Test
  fun installCoordinator_readyToInstallLaunchesDirectlyWithoutRedownloading() = runBlocking {
    val rel = ApkRelease(versionName = "2.1.1", versionCode = 28)
    val fakeApk = File("/tmp/pc-control-2.1.1.apk")
    val state = MutableStateFlow<ApkUpdateState>(ApkUpdateState.ReadyToInstall(rel, fakeApk))
    var downloadCalled = false
    var installedFile: File? = null

    val coordinator = ApkInstallCoordinator(
      currentState = { state.value },
      setError = { state.value = ApkUpdateState.Error(it) },
      resolveRelease = { error("should not re-resolve") },
      download = { _, _ ->
        downloadCalled = true
        Result.success(fakeApk)
      },
      launchInstall = { file ->
        installedFile = file
        InstallActionResult.Launched
      },
      isFilePresent = { true },
    )

    assertTrue(coordinator.startOrInstall(GITHUB_APK_URL))
    assertFalse(downloadCalled)
    assertEquals(fakeApk, installedFile)
  }

  @Test
  fun installCoordinator_readyToInstallWithMissingFileRedownloads() = runBlocking {
    val rel = ApkRelease(versionName = "2.1.1", versionCode = 28)
    val staleApk = File("/tmp/missing.apk")
    val freshApk = File("/tmp/fresh.apk")
    val state = MutableStateFlow<ApkUpdateState>(ApkUpdateState.ReadyToInstall(rel, staleApk))
    var downloadCalls = 0
    var installedFile: File? = null

    val coordinator = ApkInstallCoordinator(
      currentState = { state.value },
      setError = { state.value = ApkUpdateState.Error(it) },
      resolveRelease = { url -> rel to url },
      download = { r, _ ->
        downloadCalls++
        state.value = ApkUpdateState.ReadyToInstall(r, freshApk)
        Result.success(freshApk)
      },
      launchInstall = { file ->
        installedFile = file
        InstallActionResult.Launched
      },
      isFilePresent = { it == freshApk },
    )

    assertTrue(coordinator.startOrInstall(GITHUB_APK_URL))
    assertEquals(1, downloadCalls)
    assertEquals(freshApk, installedFile)
  }

  @Test
  fun installCoordinator_setsErrorOnInstallLaunchFailureAndAllowsRetry() = runBlocking {
    val rel = ApkRelease(versionName = "2.1.1", versionCode = 28)
    val fakeApk = File("/tmp/pc-control-2.1.1.apk")
    val state = MutableStateFlow<ApkUpdateState>(ApkUpdateState.Idle)
    var attempt = 0

    val coordinator = ApkInstallCoordinator(
      currentState = { state.value },
      setError = { state.value = ApkUpdateState.Error(it) },
      resolveRelease = { url -> rel to url },
      download = { r, _ ->
        attempt++
        state.value = ApkUpdateState.ReadyToInstall(r, fakeApk)
        Result.success(fakeApk)
      },
      launchInstall = {
        if (attempt == 1) {
          InstallActionResult.Failed("Package installer not found")
        } else {
          InstallActionResult.Launched
        }
      },
      isFilePresent = { true },
    )

    assertTrue(coordinator.startOrInstall(GITHUB_APK_URL))
    assertEquals(ApkUpdateState.Error("Package installer not found"), state.value)

    // Retry after error succeeds
    assertTrue(coordinator.startOrInstall(GITHUB_APK_URL))
    assertEquals(2, attempt)
    assertEquals(ApkUpdateState.ReadyToInstall(rel, fakeApk), state.value)
  }

  @Test
  fun installCoordinator_permissionRequiredPreservesReadyStateAndSurfacesIntentErrors() = runBlocking {
    val rel = ApkRelease(versionName = "2.1.1", versionCode = 28)
    val fakeApk = File("/tmp/pc-control-2.1.1.apk")
    val state = MutableStateFlow<ApkUpdateState>(ApkUpdateState.ReadyToInstall(rel, fakeApk))
    var settingsOpened = false
    var shouldThrow = false

    val coordinator = ApkInstallCoordinator(
      currentState = { state.value },
      setError = { state.value = ApkUpdateState.Error(it) },
      resolveRelease = { rel to GITHUB_APK_URL },
      download = { _, _ -> Result.success(fakeApk) },
      launchInstall = {
        InstallActionResult.PermissionRequired {
          if (shouldThrow) throw IllegalStateException("No settings activity")
          settingsOpened = true
        }
      },
      isFilePresent = { true },
    )

    assertTrue(coordinator.startOrInstall(GITHUB_APK_URL))
    assertTrue(settingsOpened)
    assertEquals(ApkUpdateState.ReadyToInstall(rel, fakeApk), state.value)

    shouldThrow = true
    assertTrue(coordinator.startOrInstall(GITHUB_APK_URL))
    assertEquals(ApkUpdateState.Error("No settings activity"), state.value)
  }
}
