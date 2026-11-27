package com.neekhar.pccontrol.core.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

@Serializable
data class KeyringStatus(
  val locked: Boolean,
  val rdp: String = "",
)

@Serializable
data class KeyringUnlockResponse(
  val ok: Boolean = false,
  val locked: Boolean = true,
  val rdp: String = "",
  val error: String? = null,
)

@Serializable
private data class KeyringUnlockBody(
  val password: String,
)

sealed interface KeyringActionResult {
  data class Success(val status: KeyringStatus, val message: String) : KeyringActionResult
  data class Failure(
    val message: String,
    val cancelled: Boolean = false,
    val invalidated: Boolean = false,
  ) : KeyringActionResult
}

sealed interface KeyringSaveResult {
  data object Saved : KeyringSaveResult
  data class Failure(
    val message: String,
    val cancelled: Boolean = false,
    val invalidated: Boolean = false,
  ) : KeyringSaveResult
}

data class KeyringUiState(
  val status: KeyringStatus? = null,
  val hasSaved: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  val notice: String? = null,
)

/**
 * Biometric-gated AES-256-GCM vault for the laptop's GNOME login keyring password,
 * plus the `/pc/api/keyring` status & unlock flow matching `src/lib/keyring.ts`.
 *
 * The AES key lives in Android Keystore (StrongBox when available, else TEE), requires a strong
 * biometric on every encrypt and decrypt via `BiometricPrompt` + `CryptoObject`, and is
 * invalidated when new biometrics are enrolled. Only ciphertext + IV are persisted; plaintext
 * byte buffers are zeroed immediately after use and never logged or cached.
 */
object KeyringVault {
  const val ALIAS = "pc-control-keyring-v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128
  private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

  private val _state = MutableStateFlow(KeyringUiState())
  val state: StateFlow<KeyringUiState> = _state.asStateFlow()

  private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  private fun keyExists(): Boolean = try {
    keyStore().containsAlias(ALIAS)
  } catch (_: Exception) {
    false
  }

  fun hasSaved(ctx: Context): Boolean =
    PlatformPrefs.keyringCiphertext(ctx) != null &&
      PlatformPrefs.keyringIv(ctx) != null &&
      keyExists()

  fun clearFeedback() {
    _state.update { it.copy(error = null, notice = null) }
  }

  fun forget(ctx: Context) {
    PlatformPrefs.clearKeyringBlob(ctx)
    try {
      val ks = keyStore()
      if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    } catch (_: Exception) {
    }
    _state.update {
      it.copy(
        hasSaved = hasSaved(ctx),
        error = null,
        notice = "Saved keyring password removed.",
      )
    }
  }

  private fun keyGenSpec(strongBox: Boolean): KeyGenParameterSpec {
    val builder = KeyGenParameterSpec.Builder(
      ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setKeySize(256)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true)
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

  private fun createKey(): SecretKey {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      throw PlatformException("E_OLD_ANDROID", "Android 11 or newer is required")
    }
    val ks = keyStore()
    if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    var strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    return try {
      gen.init(keyGenSpec(strongBox))
      gen.generateKey()
    } catch (e: Exception) {
      if (
        !strongBox ||
        !(e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException || e is ProviderException)
      ) {
        throw e
      }
      strongBox = false
      if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
      gen.init(keyGenSpec(false))
      gen.generateKey()
    }
  }

  private fun initEncryptCipher(): Cipher {
    val ks = keyStore()
    val existing = if (ks.containsAlias(ALIAS)) ks.getKey(ALIAS, null) as? SecretKey else null
    if (existing != null) {
      try {
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, existing) }
      } catch (_: KeyPermanentlyInvalidatedException) {
        // Key invalidated by new biometric enrollment; replace with a fresh key below.
      }
    }
    val fresh = createKey()
    return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, fresh) }
  }

  private fun mapBiometricErrorCode(errorCode: Int): String = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    -> "E_CANCELLED"
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    -> "E_LOCKOUT"
    else -> "E_AUTH"
  }

  private fun checkBiometricsError(ctx: Context): String? {
    val status = BiometricSigner.biometricStatus(ctx)
    return when (status) {
      BiometricStatus.OK -> null
      BiometricStatus.NONE_ENROLLED ->
        "Add a fingerprint in Android settings first (Security > Fingerprint)."
      else ->
        "Strong biometrics are not available on this phone (${status.wireName})."
    }
  }

  private fun mapBiometricFailure(err: Throwable): Triple<Boolean, Boolean, String> {
    val pe = err as? PlatformException
    return when (pe?.code) {
      "E_CANCELLED" -> Triple(true, false, "Cancelled")
      "E_KEY_INVALIDATED", "E_NO_KEY" -> Triple(
        false,
        true,
        pe.message.ifBlank { "Saved keyring password is no longer usable. Save it again in Settings." },
      )
      "E_LOCKOUT" -> Triple(
        false,
        false,
        "Too many biometric attempts. Unlock your phone with your PIN, then try again.",
      )
      else -> Triple(false, false, pe?.message ?: err.message ?: "Biometric authentication failed.")
    }
  }

  // ---- Biometric encrypt / decrypt primitives ----

  /** Must be called on the main thread. Calls back exactly once. */
  fun saveCallback(
    activity: FragmentActivity,
    ctx: Context,
    password: String,
    title: String = "Save keyring password",
    subtitle: String = "Verify your fingerprint to encrypt and store the password on this phone",
    onDone: (Result<Unit>) -> Unit,
  ) {
    val plain = password.toByteArray(Charsets.UTF_8)
    val cipher: Cipher
    try {
      cipher = initEncryptCipher()
    } catch (e: PlatformException) {
      plain.fill(0)
      return onDone(Result.failure(e))
    } catch (e: Exception) {
      plain.fill(0)
      return onDone(Result.failure(PlatformException("E_KEYSTORE", "Keystore error: ${e.message}", e)))
    }

    var finished = false
    fun done(r: Result<Unit>) {
      if (!finished) {
        finished = true
        plain.fill(0)
        onDone(r)
      }
    }

    val prompt = BiometricPrompt(
      activity,
      ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          try {
            val c = result.cryptoObject?.cipher
              ?: return done(Result.failure(PlatformException("E_AUTH", "No crypto object returned")))
            val ct = c.doFinal(plain)
            val iv = c.iv
            PlatformPrefs.setKeyringBlob(
              ctx,
              Base64.getEncoder().encodeToString(ct),
              Base64.getEncoder().encodeToString(iv),
            )
            done(Result.success(Unit))
          } catch (e: Exception) {
            done(Result.failure(PlatformException("E_ENCRYPT", "Encryption failed: ${e.message}", e)))
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          done(Result.failure(PlatformException(mapBiometricErrorCode(errorCode), errString.toString())))
        }
      },
    )

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
  fun decryptCallback(
    activity: FragmentActivity,
    ctx: Context,
    title: String = "Unlock laptop keyring",
    subtitle: String = "Verify your fingerprint to unlock the GNOME keyring and restart RDP",
    onDone: (Result<String>) -> Unit,
  ) {
    val ctB64 = PlatformPrefs.keyringCiphertext(ctx)
    val ivB64 = PlatformPrefs.keyringIv(ctx)
    if (ctB64 == null || ivB64 == null) {
      return onDone(Result.failure(PlatformException("E_NO_KEY", "No keyring password saved on this phone.")))
    }

    val ct: ByteArray
    val iv: ByteArray
    val cipher: Cipher
    try {
      ct = Base64.getDecoder().decode(ctB64)
      iv = Base64.getDecoder().decode(ivB64)
      val ks = keyStore()
      val key = ks.getKey(ALIAS, null) as? SecretKey
        ?: return onDone(
          Result.failure(
            PlatformException(
              "E_NO_KEY",
              "Saved keyring key is missing. Re-save the password in Settings.",
            ),
          ),
        )
      cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    } catch (e: KeyPermanentlyInvalidatedException) {
      return onDone(
        Result.failure(
          PlatformException(
            "E_KEY_INVALIDATED",
            "Your fingerprints changed, so Android destroyed the saved keyring key. Re-save the password in Settings.",
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
          var plain: ByteArray? = null
          try {
            val c = result.cryptoObject?.cipher
              ?: return done(Result.failure(PlatformException("E_AUTH", "No crypto object returned")))
            plain = c.doFinal(ct)
            done(Result.success(String(plain, Charsets.UTF_8)))
          } catch (e: Exception) {
            done(Result.failure(PlatformException("E_DECRYPT", "Decryption failed: ${e.message}", e)))
          } finally {
            plain?.fill(0)
          }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          done(Result.failure(PlatformException(mapBiometricErrorCode(errorCode), errString.toString())))
        }
      },
    )

    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setNegativeButtonText("Cancel")
      .setAllowedAuthenticators(BIOMETRIC_STRONG)
      .setConfirmationRequired(true)
      .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
  }

  suspend fun save(
    activity: FragmentActivity,
    ctx: Context,
    password: String,
    title: String = "Save keyring password",
    subtitle: String = "Verify your fingerprint to encrypt and store the password on this phone",
  ): KeyringSaveResult {
    if (password.isEmpty()) {
      return KeyringSaveResult.Failure("Enter your keyring password.")
    }
    val bioErr = checkBiometricsError(ctx)
    if (bioErr != null) {
      _state.update { it.copy(error = bioErr, notice = null) }
      return KeyringSaveResult.Failure(bioErr)
    }
    _state.update { it.copy(busy = true, error = null, notice = null) }
    val result = withContext(Dispatchers.Main) {
      suspendCancellableCoroutine<Result<Unit>> { cont ->
        saveCallback(activity, ctx, password, title, subtitle) { r ->
          if (cont.isActive) cont.resume(r)
        }
      }
    }
    return result.fold(
      onSuccess = {
        _state.update {
          it.copy(
            busy = false,
            hasSaved = hasSaved(ctx),
            error = null,
            notice = "Keyring password saved on this phone.",
          )
        }
        KeyringSaveResult.Saved
      },
      onFailure = { e ->
        val (cancelled, invalidated, msg) = mapBiometricFailure(e)
        _state.update {
          it.copy(
            busy = false,
            hasSaved = hasSaved(ctx),
            error = if (cancelled) null else msg,
            notice = null,
          )
        }
        KeyringSaveResult.Failure(msg, cancelled = cancelled, invalidated = invalidated)
      },
    )
  }

  suspend fun decrypt(
    activity: FragmentActivity,
    ctx: Context,
    title: String = "Unlock laptop keyring",
    subtitle: String = "Verify your fingerprint to unlock the GNOME keyring and restart RDP",
  ): Result<String> = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { cont ->
      decryptCallback(activity, ctx, title, subtitle) { r ->
        if (cont.isActive) cont.resume(r)
      }
    }
  }

  // ---- Server challenge/response helpers (matching src/lib/keyring.ts) ----

  suspend fun refreshStatus(
    ctx: Context,
    client: OkHttpClient = PlatformHttp.defaultClient,
  ): KeyringStatus? {
    val saved = hasSaved(ctx)
    return withContext(Dispatchers.IO) {
      try {
        val req = Request.Builder()
          .url("${PlatformPrefs.serverUrl(ctx)}/pc/api/keyring")
          .get()
          .header("Accept", "application/json")
          .build()
        client.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
          val body = resp.body?.string().orEmpty()
          val status = PlatformHttp.json.decodeFromString(KeyringStatus.serializer(), body)
          _state.update { it.copy(status = status, hasSaved = saved) }
          status
        }
      } catch (_: Exception) {
        _state.update { it.copy(hasSaved = saved) }
        null
      }
    }
  }

  suspend fun postUnlock(
    ctx: Context,
    password: String,
    client: OkHttpClient = PlatformHttp.defaultClient,
  ): Result<KeyringUnlockResponse> = withContext(Dispatchers.IO) {
    try {
      val unlockClient = client.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
      val payload = PlatformHttp.json.encodeToString(
        KeyringUnlockBody.serializer(),
        KeyringUnlockBody(password),
      )
      val req = Request.Builder()
        .url("${PlatformPrefs.serverUrl(ctx)}/pc/api/keyring/unlock")
        .post(payload.toRequestBody(JSON_MEDIA))
        .header("Accept", "application/json")
        .build()
      unlockClient.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        val parsed = runCatching {
          PlatformHttp.json.decodeFromString(KeyringUnlockResponse.serializer(), body)
        }.getOrNull()
        if (!resp.isSuccessful) {
          val msg = when (resp.code) {
            401 -> "Wrong keyring password."
            429 -> parsed?.error?.takeIf { it.isNotBlank() }
              ?: "Too many failed unlock attempts. Try again later."
            else -> parsed?.error?.takeIf { it.isNotBlank() } ?: "Unlock failed (HTTP ${resp.code})."
          }
          Result.failure(PlatformException("E_HTTP_${resp.code}", msg))
        } else {
          Result.success(parsed ?: KeyringUnlockResponse(ok = true, locked = false))
        }
      }
    } catch (e: Exception) {
      Result.failure(PlatformException("E_NETWORK", e.message ?: "Unlock failed.", e))
    }
  }

  /**
   * Decrypts the saved keyring password via BiometricPrompt and unlocks the laptop keyring
   * via `POST /pc/api/keyring/unlock`.
   */
  suspend fun unlockWithSaved(
    activity: FragmentActivity,
    ctx: Context,
    client: OkHttpClient = PlatformHttp.defaultClient,
  ): KeyringActionResult {
    if (_state.value.busy) {
      return KeyringActionResult.Failure("Unlock already in progress.")
    }
    if (!hasSaved(ctx)) {
      return KeyringActionResult.Failure(
        message = "No keyring password saved on this phone.",
        invalidated = true,
      )
    }
    _state.update { it.copy(busy = true, error = null, notice = null) }

    val decrypted = decrypt(activity, ctx)
    val password = decrypted.getOrElse { e ->
      val (cancelled, invalidated, msg) = mapBiometricFailure(e)
      _state.update {
        it.copy(
          busy = false,
          hasSaved = hasSaved(ctx),
          error = if (cancelled) null else msg,
          notice = null,
        )
      }
      return KeyringActionResult.Failure(msg, cancelled = cancelled, invalidated = invalidated)
    }

    return postUnlock(ctx, password, client).fold(
      onSuccess = { res ->
        val next = KeyringStatus(locked = res.locked, rdp = res.rdp)
        val msg = if (next.locked) "Keyring is still locked." else "Keyring unlocked · RDP ${next.rdp}"
        _state.update { it.copy(status = next, busy = false, error = null, notice = msg) }
        KeyringActionResult.Success(next, msg)
      },
      onFailure = { e ->
        val msg = e.message ?: "Unlock failed."
        _state.update { it.copy(busy = false, error = msg, notice = null) }
        KeyringActionResult.Failure(msg)
      },
    )
  }

  /**
   * Unlocks the laptop keyring with a manually entered password, optionally saving it to the
   * biometric vault afterwards if the unlock succeeded.
   */
  suspend fun unlockManual(
    activity: FragmentActivity?,
    ctx: Context,
    password: String,
    saveAfter: Boolean,
    client: OkHttpClient = PlatformHttp.defaultClient,
  ): KeyringActionResult {
    if (_state.value.busy) {
      return KeyringActionResult.Failure("Unlock already in progress.")
    }
    if (password.isEmpty()) {
      return KeyringActionResult.Failure("Enter your keyring password.")
    }
    _state.update { it.copy(busy = true, error = null, notice = null) }

    return postUnlock(ctx, password, client).fold(
      onSuccess = { res ->
        val next = KeyringStatus(locked = res.locked, rdp = res.rdp)
        var msg = if (next.locked) "Keyring is still locked." else "Keyring unlocked · RDP ${next.rdp}"
        if (saveAfter && !next.locked && activity != null) {
          val bioErr = checkBiometricsError(ctx)
          if (bioErr != null) {
            msg = "$msg (not saved: $bioErr)"
          } else {
            val saveOutcome = withContext(Dispatchers.Main) {
              suspendCancellableCoroutine<Result<Unit>> { cont ->
                saveCallback(
                  activity = activity,
                  ctx = ctx,
                  password = password,
                  title = "Save keyring password",
                  subtitle = "Verify your fingerprint to save this password for next time",
                ) { r -> if (cont.isActive) cont.resume(r) }
              }
            }
            saveOutcome.fold(
              onSuccess = { msg = "$msg · saved on phone" },
              onFailure = { err ->
                val (cancelled, _, errMsg) = mapBiometricFailure(err)
                if (!cancelled) msg = "$msg (not saved: $errMsg)"
              },
            )
          }
        }
        _state.update {
          it.copy(
            status = next,
            hasSaved = hasSaved(ctx),
            busy = false,
            error = null,
            notice = msg,
          )
        }
        KeyringActionResult.Success(next, msg)
      },
      onFailure = { e ->
        val msg = e.message ?: "Unlock failed."
        _state.update { it.copy(busy = false, error = msg, notice = null) }
        KeyringActionResult.Failure(msg)
      },
    )
  }
}
