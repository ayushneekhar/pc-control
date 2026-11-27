package com.neekhar.pccontrol.feature.root

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class RootdState(val wireValue: String) {
    OK("ok"),
    DOWN("down"),
    NOT_INSTALLED("not_installed"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): RootdState = when (value) {
            "ok" -> OK
            "down" -> DOWN
            "not_installed" -> NOT_INSTALLED
            else -> UNKNOWN
        }
    }
}

@Serializable
data class RootRequest(
    val id: String,
    val argv: List<String>,
    val reason: String = "",
    val nonce: String,
    val created: Double,
    val expires: Double,
    val status: String, // pending | running | done | denied | expired | cancelled | lost
    val timeout: Int = 600,
    val uid: Int = 0,
    @SerialName("argv_sha256") val argvSha256: String = "",
    @SerialName("snap_sha256") val snapSha256: String? = null,
    @SerialName("output_expired") val outputExpired: Boolean? = null,
    val exit: Int? = null,
    val device: String? = null,
    val decided: Double? = null,
    val started: Double? = null,
    val finished: Double? = null,
    @SerialName("timed_out") val timedOut: Boolean? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    @SerialName("out_bytes") val outBytes: Int? = null,
    @SerialName("err_bytes") val errBytes: Int? = null,
) {
    fun toSignable(): SignableRequest = SignableRequest(
        id = id,
        nonce = nonce,
        expires = expires.toLong(),
        argv = argv,
        timeout = timeout,
        reason = reason,
        snapSha256 = snapSha256,
    )
}

@Serializable
data class RootPendingResponse(
    val requests: List<RootRequest> = emptyList(),
)

@Serializable
data class RootHistoryResponse(
    val history: List<RootRequest> = emptyList(),
)

@Serializable
data class RootRequestDetailResponse(
    val request: RootRequest,
)

@Serializable
data class RootDecideRequest(
    val id: String,
    val device: String,
    val signature: String,
)

@Serializable
data class RootDecideResponse(
    val ok: Boolean = false,
    val request: RootRequest? = null,
    val error: String? = null,
)

sealed interface SignOutcome {
    data class Signed(val signatureBase64: String) : SignOutcome
    data object Cancelled : SignOutcome
    data class Error(val message: String, val requiresReenroll: Boolean = false) : SignOutcome
}

/**
 * Platform bridge injected into RootViewModel so biometric signing and enrollment state
 * remain decoupled from Android Keystore / BiometricPrompt internals.
 */
interface RootSignerBridge {
    fun deviceFingerprint(): String?
    fun hasKey(): Boolean
    suspend fun signDecision(decision: Decision, request: RootRequest): SignOutcome
}
