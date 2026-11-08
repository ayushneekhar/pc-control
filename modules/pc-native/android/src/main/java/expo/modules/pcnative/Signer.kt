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
    return info()!! + mapOf("strongBox" to strongBox)
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

  /** Must be called on the main thread. Calls back exactly once. */
  fun sign(
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
