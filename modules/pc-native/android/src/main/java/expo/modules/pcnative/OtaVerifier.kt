package expo.modules.pcnative

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.File
import java.security.DigestInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * OTA bundle signature check. react-native-nitro-ota has no signing of its own, so every bundle zip that
 * scripts/ota-publish.sh builds carries:
 *   ota.manifest  "pc-control-ota-v1\nversion <ota>\ntarget <versionName>+<versionCode>\n<sha256> <path>\n..."
 *   ota.sig       base64 DER ECDSA-P256-SHA256 signature over ota.manifest (key: ~/.config/pc-control on the laptop)
 * MainApplication calls check() on every cold start before handing the bundle to React Native (see
 * plugins/withNitroOta.js); the app also calls it right after a download, before reloading. A bundle that is
 * unsigned, signed by another key, built for another native build, or has any listed file changed is refused,
 * and the app falls back to the JS embedded in the APK.
 */
object OtaVerifier {
  // Public half of the laptop's OTA signing key (SubjectPublicKeyInfo, base64 DER). Safe to publish.
  private const val PUBLIC_KEY =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKHEFld1ZnFFwJSuT0o0imyWLO7upLuRTp1AvayM9P+3HCi0B9EOTZTnRJtydBUR6nokbWKOmGrZsFSrDn3DDSA=="
  private const val HEADER = "pc-control-ota-v1"

  /** "<versionName>+<versionCode>", the exact native build. Must match scripts/ota/build-nitro-ota.mjs. */
  fun binaryTarget(ctx: Context): String {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else {
      @Suppress("DEPRECATION") pi.versionCode.toLong()
    }
    return "${pi.versionName}+$code"
  }

  /** null if the bundle at [bundlePath] is good to load, else why not. */
  fun check(ctx: Context, bundlePath: String): String? = try {
    verify(ctx, File(bundlePath))
  } catch (e: Exception) {
    "verification error: ${e.javaClass.simpleName}"
  }

  private fun verify(ctx: Context, bundle: File): String? {
    val dir = bundle.parentFile ?: return "bundle has no folder"
    val manifestFile = File(dir, "ota.manifest")
    val sigFile = File(dir, "ota.sig")
    if (!bundle.isFile) return "bundle file missing"
    if (!manifestFile.isFile || !sigFile.isFile) return "bundle is not signed"
    val manifest = manifestFile.readBytes()
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
    val sig = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(manifest) }
    if (!sig.verify(Base64.decode(sigFile.readText().trim(), Base64.DEFAULT))) return "bad signature"

    val lines = String(manifest, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    if (lines.size < 4 || lines[0] != HEADER) return "unknown manifest format"
    val target = lines[2].removePrefix("target ")
    if (!lines[2].startsWith("target ") || target != binaryTarget(ctx)) return "built for $target, not this app build"
    val root = dir.canonicalPath + File.separator
    var hasBundle = false
    for (line in lines.drop(3)) {
      val sp = line.indexOf(' ')
      if (sp != 64) return "bad manifest line"
      val want = line.substring(0, sp)
      val rel = line.substring(sp + 1)
      val f = File(dir, rel)
      if (!f.canonicalPath.startsWith(root)) return "bad path in manifest"
      if (!f.isFile) return "missing file $rel"
      if (sha256(f) != want) return "changed file $rel"
      if (rel == bundle.name) hasBundle = true
    }
    return if (hasBundle) null else "bundle not covered by the signature"
  }

  private fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    DigestInputStream(f.inputStream().buffered(), md).use { s ->
      val buf = ByteArray(64 * 1024)
      while (s.read(buf) != -1) Unit
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  /** Written by MainApplication when it refused or lost an OTA bundle at startup; shown once in Settings. */
  fun recoveryFile(ctx: Context) = File(ctx.filesDir, "nitro-ota-startup-recovery.json")
}
