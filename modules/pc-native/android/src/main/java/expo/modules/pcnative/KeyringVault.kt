package expo.modules.pcnative

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric-gated AES-256-GCM vault for the laptop's GNOME login keyring password.
 * The key lives in Android Keystore (StrongBox when available, else TEE), requires a strong biometric
 * on every encrypt and decrypt via BiometricPrompt + CryptoObject, and is invalidated when a new
 * biometric is enrolled. Only the ciphertext and IV are stored in private SharedPreferences; plaintext
 * is never persisted or logged.
 */
object KeyringVault {
  const val ALIAS = "pc-control-keyring-v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128

  private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  private fun keyExists(): Boolean = try {
    ks().containsAlias(ALIAS)
  } catch (_: Exception) {
    false
  }

  fun hasSaved(ctx: Context): Boolean =
    Prefs.keyringCiphertext(ctx) != null && Prefs.keyringIv(ctx) != null && keyExists()

  fun forget(ctx: Context) {
    Prefs.clearKeyringBlob(ctx)
    try {
      val k = ks()
      if (k.containsAlias(ALIAS)) k.deleteEntry(ALIAS)
    } catch (_: Exception) {
    }
  }

  private fun spec(strongBox: Boolean): KeyGenParameterSpec {
    val b = KeyGenParameterSpec.Builder(
      ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setKeySize(256)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true)
      .setUserAuthenticationRequired(true)
      .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
      .setInvalidatedByBiometricEnrollment(true)
    if (strongBox) b.setIsStrongBoxBacked(true)
    return b.build()
  }

  private fun createKey(): SecretKey {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      throw PcException("E_OLD_ANDROID", "Android 11 or newer is required")
    }
    val k = ks()
    if (k.containsAlias(ALIAS)) k.deleteEntry(ALIAS)
    var strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    return try {
      gen.init(spec(strongBox))
      gen.generateKey()
    } catch (e: Exception) {
      if (!strongBox || !(e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException ||
          e is ProviderException)) throw e
      strongBox = false
      if (k.containsAlias(ALIAS)) k.deleteEntry(ALIAS)
      gen.init(spec(false))
      gen.generateKey()
    }
  }

  private fun initEncryptCipher(): Cipher {
    val k = ks()
    val existing = if (k.containsAlias(ALIAS)) k.getKey(ALIAS, null) as? SecretKey else null
    if (existing != null) {
      try {
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, existing) }
      } catch (_: KeyPermanentlyInvalidatedException) {
        // Old key was invalidated by a biometric change; fall through to create a fresh key.
      }
    }
    val fresh = createKey()
    return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, fresh) }
  }

  private fun mapBiometricError(errorCode: Int): String = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED -> "E_CANCELLED"
    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "E_LOCKOUT"
    else -> "E_AUTH"
  }

  /** Must be called on the main thread. Calls back exactly once. */
  fun save(
    activity: FragmentActivity,
    ctx: Context,
    password: String,
    title: String,
    subtitle: String,
    onDone: (Result<Unit>) -> Unit
  ) {
    val plain = password.toByteArray(Charsets.UTF_8)
    val cipher: Cipher
    try {
      cipher = initEncryptCipher()
    } catch (e: PcException) {
      plain.fill(0)
      return onDone(Result.failure(e))
    } catch (e: Exception) {
      plain.fill(0)
      return onDone(Result.failure(PcException("E_KEYSTORE", "Keystore error: ${e.message}", e)))
    }

    var finished = false
    fun done(r: Result<Unit>) {
      if (!finished) {
        finished = true
        plain.fill(0)
        onDone(r)
      }
    }

    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          try {
            val c = result.cryptoObject?.cipher
              ?: return done(Result.failure(PcException("E_AUTH", "No crypto object returned")))
            val ct = c.doFinal(plain)
            val iv = c.iv
            Prefs.setKeyringBlob(
              ctx,
              Base64.encodeToString(ct, Base64.NO_WRAP),
              Base64.encodeToString(iv, Base64.NO_WRAP)
            )
            done(Result.success(Unit))
          } catch (e: Exception) {
            done(Result.failure(PcException("E_ENCRYPT", "Encryption failed: ${e.message}", e)))
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          done(Result.failure(PcException(mapBiometricError(errorCode), errString.toString())))
        }
      })

    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setNegativeButtonText("Cancel")
      .setAllowedAuthenticators(BIOMETRIC_STRONG)
      .setConfirmationRequired(true)
      .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
  }

  /** Must be called on the main thread. Calls back exactly once. */
  fun decrypt(
    activity: FragmentActivity,
    ctx: Context,
    title: String,
    subtitle: String,
    onDone: (Result<String>) -> Unit
  ) {
    val ctB64 = Prefs.keyringCiphertext(ctx)
    val ivB64 = Prefs.keyringIv(ctx)
    if (ctB64 == null || ivB64 == null) {
      return onDone(Result.failure(PcException("E_NO_KEY", "No keyring password saved on this phone.")))
    }

    val ct: ByteArray
    val iv: ByteArray
    val cipher: Cipher
    try {
      ct = Base64.decode(ctB64, Base64.NO_WRAP)
      iv = Base64.decode(ivB64, Base64.NO_WRAP)
      val k = ks()
      val key = k.getKey(ALIAS, null) as? SecretKey
        ?: return onDone(Result.failure(PcException("E_NO_KEY", "Saved keyring key is missing. Re-save the password in Settings.")))
      cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    } catch (e: KeyPermanentlyInvalidatedException) {
      return onDone(Result.failure(PcException(
        "E_KEY_INVALIDATED",
        "Your fingerprints changed, so Android destroyed the saved keyring key. Re-save the password in Settings.",
        e
      )))
    } catch (e: Exception) {
      return onDone(Result.failure(PcException("E_KEYSTORE", "Keystore error: ${e.message}", e)))
    }

    var finished = false
    fun done(r: Result<String>) {
      if (!finished) {
        finished = true
        onDone(r)
      }
    }

    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          var plain: ByteArray? = null
          try {
            val c = result.cryptoObject?.cipher
              ?: return done(Result.failure(PcException("E_AUTH", "No crypto object returned")))
            plain = c.doFinal(ct)
            done(Result.success(String(plain, Charsets.UTF_8)))
          } catch (e: Exception) {
            done(Result.failure(PcException("E_DECRYPT", "Decryption failed: ${e.message}", e)))
          } finally {
            plain?.fill(0)
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          done(Result.failure(PcException(mapBiometricError(errorCode), errString.toString())))
        }
      })

    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setNegativeButtonText("Cancel")
      .setAllowedAuthenticators(BIOMETRIC_STRONG)
      .setConfirmationRequired(true)
      .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
  }
}
