package com.neekhar.pccontrol.core.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.coroutines.resume

enum class BiometricStatus(val wireName: String) {
  OK("ok"),
  NONE_ENROLLED("none_enrolled"),
  NO_HARDWARE("no_hardware"),
  UNAVAILABLE("unavailable"),
  SECURITY_UPDATE_REQUIRED("security_update_required"),
  UNSUPPORTED("unsupported"),
}

enum class KeySecurityLevel(val wireName: String) {
  STRONGBOX("strongbox"),
  TEE("tee"),
  SOFTWARE("software"),
  UNKNOWN("unknown"),
}

data class PublicKeyInfo(
  val publicKeyDer: String,
  val publicKeyPem: String,
  val fingerprint: String,
  val securityLevel: KeySecurityLevel,
  val strongBox: Boolean = securityLevel == KeySecurityLevel.STRONGBOX,
)

enum class RootDecision(val wireValue: String) {
  APPROVE("approve"),
  DENY("deny"),
}

data class RootApprovalRequest(
  val id: String,
  val nonce: String,
  val expires: Long,
  val argv: List<String>,
  val reason: String = "",
  val timeout: Int = 600,
  val snapSha256: String = "",
)

data class RootPromptCopy(
  val title: String,
  val subtitle: String,
  val description: String,
  val formattedCommand: String,
  val isLongCommand: Boolean,
)

sealed interface RootSignOutcome {
  data class Signed(val signatureBase64: String, val payload: String) : RootSignOutcome
  data class Cancelled(val message: String = "Cancelled") : RootSignOutcome
  data class NeedsReenroll(val code: String, val message: String) : RootSignOutcome
  data class Lockout(val message: String) : RootSignOutcome
  data class Failure(val code: String, val message: String) : RootSignOutcome
}

/**
 * Root-approval hardware-backed EC P-256 signer compatible with `rootd` `sig_version=2`.
 *
 * The private key lives exclusively inside Android Keystore (StrongBox when available, else TEE),
 * requires a strong biometric on every single signature (`timeout = 0` via `BiometricPrompt` +
 * `CryptoObject`), and is permanently invalidated if new biometrics are enrolled.
 *
 * The canonical payload and BiometricPrompt copy are both derived directly from the same
 * [RootApprovalRequest] fields so UI callers can never decouple what is shown from what is signed.
 */
object BiometricSigner {
  const val ALIAS = "pc-control-root-v1"
  const val LONG_COMMAND_CHARS = 600
  private const val MAX_ARGV_BYTES = 8 * 1024

  private val ID_RE = Regex("^[0-9a-f]{16}$")
  private val NONCE_RE = Regex("^[0-9a-f]{32}$")
  private val SHA256_RE = Regex("^[0-9a-f]{64}$")
  private val QUOTE_RE = Regex("[\\s\"'$`;&|<>(){}*?!#~]")
  private val LONG_SPACES_RE = Regex(" {4,}")
  private val LONG_TABS_RE = Regex("\t{4,}")
  private val LONG_NEWLINES_RE = Regex("\n{4,}")
  private val USER_WRITABLE_RE = Regex("(?:^|[\\s\"'=:])(/(?:home|tmp)(?:/|$))")

  private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  fun exists(): Boolean = try {
    keyStore().containsAlias(ALIAS)
  } catch (_: Exception) {
    false
  }

  /** Deletes the hardware key and optionally clears the saved device fingerprint. */
  fun delete(ctx: Context? = null) {
    try {
      val ks = keyStore()
      if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    } catch (_: Exception) {
    }
    if (ctx != null) {
      PlatformPrefs.setDeviceFingerprint(ctx, null)
    }
  }

  private fun keyGenSpec(strongBox: Boolean): KeyGenParameterSpec {
    val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setUserAuthenticationRequired(true)
      .setInvalidatedByBiometricEnrollment(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
    } else {
      @Suppress("DEPRECATION")
      builder.setUserAuthenticationValidityDurationSeconds(-1)
    }
    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      builder.setIsStrongBoxBacked(true)
    }
    return builder.build()
  }

  /**
   * Generates a fresh EC P-256 keypair in Android Keystore, replacing any previous key.
   * Refuses software-only keystores.
   */
  fun create(): PublicKeyInfo {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      throw PlatformException("E_OLD_ANDROID", "Android 11 or newer is required")
    }
    delete()
    var strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
    try {
      gen.initialize(keyGenSpec(strongBox))
      gen.generateKeyPair()
    } catch (e: Exception) {
      if (
        !strongBox ||
        !(e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException || e is ProviderException)
      ) {
        throw e
      }
      strongBox = false
      delete()
      gen.initialize(keyGenSpec(false))
      gen.generateKeyPair()
    }
    val keyInfo = info()
      ?: throw PlatformException("E_KEYSTORE", "Generated key could not be read back from AndroidKeyStore")
    if (keyInfo.securityLevel == KeySecurityLevel.SOFTWARE) {
      delete()
      throw PlatformException(
        "E_NO_HARDWARE_KEYSTORE",
        "Hardware-backed keystore (TEE/StrongBox) is required; refusing software-only key.",
      )
    }
    return keyInfo.copy(strongBox = strongBox)
  }

  /** Returns the public key (X.509 SPKI DER base64 + PEM + fingerprint) and security level, or null if absent. */
  fun info(): PublicKeyInfo? {
    val ks = keyStore()
    if (!ks.containsAlias(ALIAS)) return null
    val cert = ks.getCertificate(ALIAS) ?: return null
    val der = cert.publicKey.encoded
    val b64 = Base64.getEncoder().encodeToString(der)
    val pem = formatSpkiPem(der)
    val fingerprint = keyFingerprintFromDer(der)
    var level = KeySecurityLevel.UNKNOWN
    try {
      val priv = ks.getKey(ALIAS, null) as PrivateKey
      val ki = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore")
        .getKeySpec(priv, KeyInfo::class.java)
      level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when (ki.securityLevel) {
          KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
          KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TEE
          KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
          else -> KeySecurityLevel.UNKNOWN
        }
      } else {
        @Suppress("DEPRECATION")
        if (ki.isInsideSecureHardware) KeySecurityLevel.TEE else KeySecurityLevel.SOFTWARE
      }
    } catch (_: Exception) {
    }
    return PublicKeyInfo(
      publicKeyDer = b64,
      publicKeyPem = pem,
      fingerprint = fingerprint,
      securityLevel = level,
    )
  }

  fun biometricStatus(context: Context): BiometricStatus =
    when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
      BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.OK
      BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
      BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
      BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.UNAVAILABLE
      BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SECURITY_UPDATE_REQUIRED
      else -> BiometricStatus.UNSUPPORTED
    }

  // ---- Pure payload, formatting, and fingerprinting helpers (JVM unit-testable) ----

  fun formatSpkiPem(derBytes: ByteArray): String {
    val body = Base64.getEncoder().encodeToString(derBytes).chunked(64).joinToString("\n")
    return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
  }

  fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = "0123456789abcdef"
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
      val v = b.toInt() and 0xff
      sb.append(hex[v ushr 4])
      sb.append(hex[v and 0x0f])
    }
    return sb.toString()
  }

  /** Short enrollment code: first 64 bits of SHA-256(SPKI DER), formatted as XXXX-XXXX-XXXX-XXXX. */
  fun keyFingerprintFromDer(derBytes: ByteArray): String {
    val h = sha256Hex(derBytes).uppercase()
    return listOf(0, 4, 8, 12).joinToString("-") { i -> h.substring(i, i + 4) }
  }

  fun keyFingerprint(spkiDerBase64: String): String {
    val cleaned = spkiDerBase64.replace(Regex("[^A-Za-z0-9+/=]"), "")
    return keyFingerprintFromDer(Base64.getDecoder().decode(cleaned))
  }

  /** Same rule as `rootd.bad_char`: reject Cc, Cf, Cs, Zl, Zp (except `\t` and `\n`) and lone surrogates. */
  fun hasBadChar(s: String): Boolean {
    var i = 0
    while (i < s.length) {
      val ch = s[i]
      if (ch == '\t' || ch == '\n') {
        i++
        continue
      }
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
        val cp = Character.toCodePoint(ch, s[i + 1])
        if (isDisallowedUnicodeCategory(Character.getType(cp))) return true
        i += 2
        continue
      }
      if (Character.isLowSurrogate(ch)) return true
      if (isDisallowedUnicodeCategory(Character.getType(ch))) return true
      i++
    }
    return false
  }

  private fun isDisallowedUnicodeCategory(type: Int): Boolean =
    type == Character.CONTROL.toInt() ||
      type == Character.FORMAT.toInt() ||
      type == Character.SURROGATE.toInt() ||
      type == Character.LINE_SEPARATOR.toInt() ||
      type == Character.PARAGRAPH_SEPARATOR.toInt()

  fun argvLooksSafe(argv: List<String>): Boolean =
    argv.isNotEmpty() && argv.none { hasBadChar(it) }

  /** Exact match for `JSON.stringify(argv)` / Python `json.dumps(argv, separators=(",", ":"), ensure_ascii=False)`. */
  fun canonArgv(argv: List<String>): String {
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

  fun argvHash(argv: List<String>): String = sha256Hex(canonArgv(argv).toByteArray(Charsets.UTF_8))

  /** Visible rendering of one argv element: collapses long whitespace runs and escapes `\`, `\n`, `\t`. */
  fun showArg(a: String): String {
    val collapsed = a
      .replace(LONG_SPACES_RE) { "[${it.value.length} spaces]" }
      .replace(LONG_TABS_RE) { "[${it.value.length} tabs]" }
      .replace(LONG_NEWLINES_RE) { "[${it.value.length} newlines]" }
    return collapsed.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
  }

  fun formatCommand(argv: List<String>): String =
    argv.joinToString(" ") { a ->
      val s = showArg(a)
      if (s.isEmpty() || QUOTE_RE.containsMatchIn(s)) "\"${s.replace("\"", "\\\"")}\"" else s
    }

  fun isLongCommand(argv: List<String>): Boolean = formatCommand(argv).length > LONG_COMMAND_CHARS

  /** True when any argv element references a user-writable path under `/home` or `/tmp`. */
  fun hasUserWritablePath(argv: List<String>): Boolean = argv.any { a ->
    a == "/home" ||
      a == "/tmp" ||
      a.startsWith("/home/") ||
      a.startsWith("/tmp/") ||
      USER_WRITABLE_RE.containsMatchIn(a)
  }

  /**
   * Validates [request] and builds the exact `rootd-v2` signing payload.
   * Throws [PlatformException] (`E_BAD_REQUEST`) on any validation failure.
   */
  fun buildPayloadV2(decision: RootDecision, request: RootApprovalRequest): String {
    if (!ID_RE.matches(request.id) || !NONCE_RE.matches(request.nonce)) {
      throw PlatformException("E_BAD_REQUEST", "bad request id/nonce")
    }
    if (request.expires <= 0L) {
      throw PlatformException("E_BAD_REQUEST", "bad expiry")
    }
    if (request.timeout < 1 || request.timeout > 3600) {
      throw PlatformException("E_BAD_REQUEST", "bad timeout")
    }
    if (
      request.argv.isEmpty() ||
      request.argv.size > 512 ||
      request.argv[0].isEmpty() ||
      request.argv.any { hasBadChar(it) }
    ) {
      throw PlatformException("E_BAD_REQUEST", "argv contains characters that cannot be shown faithfully")
    }
    val canonBytes = canonArgv(request.argv).toByteArray(Charsets.UTF_8)
    if (canonBytes.size > MAX_ARGV_BYTES) {
      throw PlatformException("E_BAD_REQUEST", "argv too long")
    }
    if (request.snapSha256.isNotEmpty() && !SHA256_RE.matches(request.snapSha256)) {
      throw PlatformException("E_BAD_REQUEST", "bad snapshot sha256")
    }
    val argvDigest = sha256Hex(canonBytes)
    val reasonDigest = sha256Hex(request.reason.toByteArray(Charsets.UTF_8))
    return "rootd-v2\n${decision.wireValue}\n${request.id}\n${request.nonce}\n${request.expires}\n" +
      "${request.timeout}\n$reasonDigest\n${request.snapSha256}\n$argvDigest"
  }

  fun buildPromptCopy(decision: RootDecision, request: RootApprovalRequest): RootPromptCopy {
    val cmd = formatCommand(request.argv)
    val isLong = cmd.length > LONG_COMMAND_CHARS
    val title = if (decision == RootDecision.APPROVE) "Run as root?" else "Deny this command?"
    val subtitle = when {
      isLong -> "LONG COMMAND (${cmd.length} chars): review full command in app"
      cmd.length > 80 -> cmd.take(77) + "…"
      else -> cmd
    }
    val meta = buildList {
      if (request.reason.isNotEmpty()) add("Reason: ${request.reason}")
      if (request.snapSha256.isNotEmpty()) add("Snapshot: ${request.snapSha256.take(16)}…")
      add("Timeout: ${request.timeout}s")
    }.joinToString("\n")
    val cmdBody = when {
      isLong -> "LONG COMMAND (${cmd.length} chars): review full command in app\n\n${cmd.take(350)}\n… [+${cmd.length - 350} chars — review full command in app]"
      cmd.length > 80 -> cmd
      else -> ""
    }
    val description = if (cmdBody.isNotEmpty()) "$meta\n\n$cmdBody" else meta
    return RootPromptCopy(
      title = title,
      subtitle = subtitle,
      description = description,
      formattedCommand = cmd,
      isLongCommand = isLong,
    )
  }

  // ---- BiometricPrompt signing ----

  /**
   * Coroutine-friendly root approval signer. Builds the `rootd-v2` payload and BiometricPrompt copy
   * natively, prompts the user on the main thread, and returns a typed [RootSignOutcome].
   */
  suspend fun approveRoot(
    activity: FragmentActivity,
    decision: RootDecision,
    request: RootApprovalRequest,
    checkExpiry: Boolean = true,
  ): RootSignOutcome {
    if (checkExpiry && request.expires * 1000L <= System.currentTimeMillis()) {
      return RootSignOutcome.Failure("E_EXPIRED", "This request already expired.")
    }
    val payload = try {
      buildPayloadV2(decision, request)
    } catch (e: PlatformException) {
      return RootSignOutcome.Failure(e.code, e.message)
    }
    val copy = buildPromptCopy(decision, request)
    val result = withContext(Dispatchers.Main) {
      suspendCancellableCoroutine<Result<String>> { cont ->
        signBytes(
          activity = activity,
          data = payload.toByteArray(Charsets.UTF_8),
          title = copy.title,
          subtitle = copy.subtitle,
          description = copy.description,
        ) { r ->
          if (cont.isActive) cont.resume(r)
        }
      }
    }
    return result.fold(
      onSuccess = { sig -> RootSignOutcome.Signed(signatureBase64 = sig, payload = payload) },
      onFailure = { err ->
        val pe = err as? PlatformException
        val code = pe?.code ?: "E_SIGN"
        val msg = pe?.message ?: err.message ?: "Signing failed"
        when (code) {
          "E_CANCELLED" -> RootSignOutcome.Cancelled("Cancelled")
          "E_KEY_INVALIDATED", "E_NO_KEY" -> RootSignOutcome.NeedsReenroll(code, msg)
          "E_LOCKOUT" -> RootSignOutcome.Lockout("Too many attempts. Unlock your phone with your PIN, then try again.")
          else -> RootSignOutcome.Failure(code, msg)
        }
      },
    )
  }

  /** Callback-based API mirroring `Signer.approveRoot`. Must be called on the main thread. */
  fun approveRootCallback(
    activity: FragmentActivity,
    decision: RootDecision,
    request: RootApprovalRequest,
    onDone: (Result<String>) -> Unit,
  ) {
    val payload = try {
      buildPayloadV2(decision, request)
    } catch (e: PlatformException) {
      return onDone(Result.failure(e))
    }
    val copy = buildPromptCopy(decision, request)
    signBytes(
      activity = activity,
      data = payload.toByteArray(Charsets.UTF_8),
      title = copy.title,
      subtitle = copy.subtitle,
      description = copy.description,
      onDone = onDone,
    )
  }

  /** Signs an arbitrary challenge with the root-approval key via BiometricPrompt. Main thread only. */
  fun signBytes(
    activity: FragmentActivity,
    data: ByteArray,
    title: String,
    subtitle: String,
    description: String,
    onDone: (Result<String>) -> Unit,
  ) {
    val sig: Signature
    try {
      val ks = keyStore()
      val priv = ks.getKey(ALIAS, null) as? PrivateKey
        ?: return onDone(
          Result.failure(
            PlatformException(
              "E_NO_KEY",
              "No root-approval key on this phone. Set up root approvals in Settings.",
            ),
          ),
        )
      sig = Signature.getInstance("SHA256withECDSA")
      sig.initSign(priv)
    } catch (e: KeyPermanentlyInvalidatedException) {
      return onDone(
        Result.failure(
          PlatformException(
            "E_KEY_INVALIDATED",
            "Your fingerprints changed, so Android destroyed the approval key. Re-enroll this phone in Settings.",
            e,
          ),
        ),
      )
    } catch (e: Exception) {
      return onDone(Result.failure(PlatformException("E_KEYSTORE", "Keystore error: ${e.message}", e)))
    }

    var finished = false
    fun done(r: Result<String>) {
      if (!finished) {
        finished = true
        onDone(r)
      }
    }

    val prompt = BiometricPrompt(
      activity,
      ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          try {
            val s = result.cryptoObject?.signature
              ?: return done(Result.failure(PlatformException("E_AUTH", "No crypto object returned")))
            s.update(data)
            done(Result.success(Base64.getEncoder().encodeToString(s.sign())))
          } catch (e: Exception) {
            done(Result.failure(PlatformException("E_SIGN", "Signing failed: ${e.message}", e)))
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          val code = when (errorCode) {
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
            -> "E_CANCELLED"
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            -> "E_LOCKOUT"
            else -> "E_AUTH"
          }
          done(Result.failure(PlatformException(code, errString.toString())))
        }
      },
    )

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
