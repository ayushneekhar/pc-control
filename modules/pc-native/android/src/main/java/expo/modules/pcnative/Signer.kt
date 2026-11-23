package expo.modules.pcnative

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class PcException(code: String, message: String, cause: Throwable? = null) :
  expo.modules.kotlin.exception.CodedException(code, message, cause)

/**
 * The root-approval key: Android Keystore EC P-256, sign-only, every use needs a strong biometric
 * (auth timeout 0 = per-operation, through BiometricPrompt + CryptoObject), invalidated when a new
 * fingerprint is enrolled, StrongBox-backed when the phone has one. The private key never leaves the keystore.
 */
object Signer {
  const val ALIAS = "pc-control-root-v1"

  private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  fun exists(): Boolean = ks().containsAlias(ALIAS)

  fun delete() {
    val k = ks()
    if (k.containsAlias(ALIAS)) k.deleteEntry(ALIAS)
  }

  private fun spec(strongBox: Boolean): KeyGenParameterSpec {
    val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setUserAuthenticationRequired(true)
      .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
      .setInvalidatedByBiometricEnrollment(true)
    if (strongBox) b.setIsStrongBoxBacked(true)
    return b.build()
  }

  /** Creates a fresh key (replacing any old one). Returns the public key info. */
  fun create(): Map<String, Any?> {
    delete()
    var strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
    try {
      gen.initialize(spec(strongBox))
      gen.generateKeyPair()
    } catch (e: Exception) {
      if (!strongBox || !(e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException ||
          e is java.security.ProviderException)) throw e
      strongBox = false
      delete()
      gen.initialize(spec(false))
      gen.generateKeyPair()
    }
    val keyInfo = info()!!
    if (keyInfo["securityLevel"] == "software") {
      delete()
      throw PcException(
        "E_NO_HARDWARE_KEYSTORE",
        "Hardware-backed keystore (TEE/StrongBox) is required; refusing software-only key."
      )
    }
    return keyInfo + mapOf("strongBox" to strongBox)
  }

  /** Public key + where it lives, or null when there is no key. */
  fun info(): Map<String, Any?>? {
    val k = ks()
    if (!k.containsAlias(ALIAS)) return null
    val cert = k.getCertificate(ALIAS) ?: return null
    val der = cert.publicKey.encoded   // X.509 SubjectPublicKeyInfo DER
    val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
    val pem = "-----BEGIN PUBLIC KEY-----\n" +
      Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n") +
      "\n-----END PUBLIC KEY-----\n"
    var level = "unknown"
    try {
      val priv = k.getKey(ALIAS, null) as PrivateKey
      val ki = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore").getKeySpec(priv, KeyInfo::class.java)
      level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when (ki.securityLevel) {
          KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
          KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
          KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
          else -> "unknown"
        }
      } else {
        @Suppress("DEPRECATION")
        if (ki.isInsideSecureHardware) "tee" else "software"
      }
    } catch (_: Exception) {
    }
    return mapOf("publicKeyDer" to b64, "publicKeyPem" to pem, "securityLevel" to level)
  }

  fun biometricStatus(activity: android.content.Context): String =
    when (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG)) {
      BiometricManager.BIOMETRIC_SUCCESS -> "ok"
      BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
      BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
      BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "unavailable"
      BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "security_update_required"
      else -> "unsupported"
    }

  private val ID_RE = Regex("^[0-9a-f]{16}$")
  private val NONCE_RE = Regex("^[0-9a-f]{32}$")
  private val SHA256_RE = Regex("^[0-9a-f]{64}$")
  private val QUOTE_RE = Regex("[\\s\"'$`;&|<>(){}*?!#~]")
  private val LONG_SPACES_RE = Regex(" {4,}")
  private val LONG_TABS_RE = Regex("\t{4,}")
  private val LONG_NEWLINES_RE = Regex("\n{4,}")
  private const val MAX_ARGV_BYTES = 8 * 1024
  private const val LONG_CMD_CHARS = 600

  private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
      val v = b.toInt() and 0xff
      sb.append("0123456789abcdef"[v ushr 4])
      sb.append("0123456789abcdef"[v and 0x0f])
    }
    return sb.toString()
  }

  /** Same rule as rootd.bad_char: reject Cc, Cf, Cs, Zl, Zp (except \t and \n) and lone surrogates. */
  internal fun hasBadChar(s: String): Boolean {
    var i = 0
    while (i < s.length) {
      val ch = s[i]
      if (ch == '\t' || ch == '\n') { i++; continue }
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
        val cp = Character.toCodePoint(ch, s[i + 1])
        val t = Character.getType(cp)
        if (t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() ||
            t == Character.SURROGATE.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
            t == Character.PARAGRAPH_SEPARATOR.toInt()) return true
        i += 2
        continue
      }
      if (Character.isLowSurrogate(ch)) return true
      val t = Character.getType(ch)
      if (t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() ||
          t == Character.SURROGATE.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
          t == Character.PARAGRAPH_SEPARATOR.toInt()) return true
      i++
    }
    return false
  }

  /** Exact match for JSON.stringify(argv) / Python json.dumps(argv, separators=(",", ":"), ensure_ascii=False). */
  internal fun canonArgv(argv: List<String>): String {
    val sb = StringBuilder()
    sb.append('[')
    for (idx in argv.indices) {
      if (idx > 0) sb.append(',')
      sb.append('"')
      for (ch in argv[idx]) {
        when (ch) {
          '"' -> sb.append("\\\"")
          '\\' -> sb.append("\\\\")
          '\t' -> sb.append("\\t")
          '\n' -> sb.append("\\n")
          else -> sb.append(ch)
        }
      }
      sb.append('"')
    }
    sb.append(']')
    return sb.toString()
  }

  private fun showArg(a: String): String {
    val collapsed = a
      .replace(LONG_SPACES_RE) { "[${it.value.length} spaces]" }
      .replace(LONG_TABS_RE) { "[${it.value.length} tabs]" }
      .replace(LONG_NEWLINES_RE) { "[${it.value.length} newlines]" }
    return collapsed.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
  }

  private fun formatCommand(argv: List<String>): String =
    argv.joinToString(" ") { a ->
      val s = showArg(a)
      if (s.isEmpty() || QUOTE_RE.containsMatchIn(s)) "\"${s.replace("\"", "\\\"")}\"" else s
    }

  /**
   * Builds the rootd-v2 payload natively and formats BiometricPrompt from the exact same fields so JS
   * can never decouple what is shown from what is signed. Must be called on the main thread.
   */
  fun approveRoot(
    activity: FragmentActivity,
    decision: String,
    id: String,
    nonce: String,
    expires: Long,
    argv: List<String>,
    reason: String,
    timeout: Int,
    snapSha256: String,
    onDone: (Result<String>) -> Unit
  ) {
    if (decision != "approve" && decision != "deny") {
      return onDone(Result.failure(PcException("E_BAD_REQUEST", "bad decision")))
    }
    if (!ID_RE.matches(id) || !NONCE_RE.matches(nonce)) {
      return onDone(Result.failure(PcException("E_BAD_REQUEST", "bad request id/nonce")))
    }
    if (expires <= 0L) {
      return onDone(Result.failure(PcException("E_BAD_REQUEST", "bad expiry")))
    }
    if (timeout < 1 || timeout > 3600) {
      return onDone(Result.failure(PcException("E_BAD_REQUEST", "bad timeout")))
    }
    if (argv.isEmpty() || argv.size > 512 || argv[0].isEmpty() || argv.any { hasBadChar(it) }) {
      return onDone(Result.failure(PcException("E_BAD_REQUEST", "argv contains characters that cannot be shown faithfully")))
    }
    val canonBytes = canonArgv(argv).toByteArray(Charsets.UTF_8)
    if (canonBytes.size > MAX_ARGV_BYTES) {
      return onDone(Result.failure(PcException("E_BAD_REQUEST", "argv too long")))
    }
    if (snapSha256.isNotEmpty() && !SHA256_RE.matches(snapSha256)) {
      return onDone(Result.failure(PcException("E_BAD_REQUEST", "bad snapshot sha256")))
    }
    val argvHash = sha256Hex(canonBytes)
    val reasonHash = sha256Hex(reason.toByteArray(Charsets.UTF_8))
    val payload = "rootd-v2\n$decision\n$id\n$nonce\n$expires\n$timeout\n$reasonHash\n$snapSha256\n$argvHash"

    val cmd = formatCommand(argv)
    val isLong = cmd.length > LONG_CMD_CHARS
    val title = if (decision == "approve") "Run as root?" else "Deny this command?"
    val subtitle = when {
      isLong -> "LONG COMMAND (${cmd.length} chars): review full command in app"
      cmd.length > 80 -> cmd.take(77) + "…"
      else -> cmd
    }
    val meta = buildList {
      if (reason.isNotEmpty()) add("Reason: $reason")
      if (snapSha256.isNotEmpty()) add("Snapshot: ${snapSha256.take(16)}…")
      add("Timeout: ${timeout}s")
    }.joinToString("\n")
    val cmdBody = when {
      isLong -> "LONG COMMAND (${cmd.length} chars): review full command in app\n\n${cmd.take(350)}\n… [+${cmd.length - 350} chars — review full command in app]"
      cmd.length > 80 -> cmd
      else -> ""
    }
    val description = if (cmdBody.isNotEmpty()) "$meta\n\n$cmdBody" else meta
    sign(activity, payload.toByteArray(Charsets.UTF_8), title, subtitle, description, onDone)
  }

  /** Must be called on the main thread. Calls back exactly once. */
  private fun sign(
    activity: FragmentActivity,
    data: ByteArray,
    title: String,
    subtitle: String,
    description: String,
    onDone: (Result<String>) -> Unit
  ) {
    val sig: Signature
    try {
      val k = ks()
      val priv = k.getKey(ALIAS, null) as? PrivateKey
        ?: return onDone(Result.failure(PcException("E_NO_KEY", "No root-approval key on this phone. Set up root approvals in Settings.")))
      sig = Signature.getInstance("SHA256withECDSA")
      sig.initSign(priv)
    } catch (e: KeyPermanentlyInvalidatedException) {
      return onDone(Result.failure(PcException("E_KEY_INVALIDATED",
        "Your fingerprints changed, so Android destroyed the approval key. Re-enroll this phone in Settings.", e)))
    } catch (e: Exception) {
      return onDone(Result.failure(PcException("E_KEYSTORE", "Keystore error: ${e.message}", e)))
    }
    var finished = false
    fun done(r: Result<String>) {
      if (!finished) { finished = true; onDone(r) }
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          try {
            val s = result.cryptoObject?.signature
              ?: return done(Result.failure(PcException("E_AUTH", "No crypto object returned")))
            s.update(data)
            done(Result.success(Base64.encodeToString(s.sign(), Base64.NO_WRAP)))
          } catch (e: Exception) {
            done(Result.failure(PcException("E_SIGN", "Signing failed: ${e.message}", e)))
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          val code = when (errorCode) {
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED -> "E_CANCELLED"
            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "E_LOCKOUT"
            else -> "E_AUTH"
          }
          done(Result.failure(PcException(code, errString.toString())))
        }
      })
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setDescription(description)
      .setNegativeButtonText("Cancel")
      .setAllowedAuthenticators(BIOMETRIC_STRONG)
      .setConfirmationRequired(true)
      .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
  }
}
