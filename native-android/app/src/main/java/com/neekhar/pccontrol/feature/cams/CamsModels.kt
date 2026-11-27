package com.neekhar.pccontrol.feature.cams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- Cat cam master switch

@Serializable
data class CatCamState(
    val enabled: Boolean = true,
    val units: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------- Cat detector

@Serializable
data class DetectorCamInfo(
    val name: String? = null,
    @SerialName("last_seen_ts") val lastSeenTs: Double? = null,
)

@Serializable
data class DetectorLastAlert(
    val cam: String = "",
    val name: String? = null,
    val ts: Double = 0.0,
)

@Serializable
data class DetectorState(
    val enabled: Boolean = false,
    val cams: Map<String, DetectorCamInfo> = emptyMap(),
    @SerialName("last_alert") val lastAlert: DetectorLastAlert? = null,
)

// ---------------------------------------------------------------- Detections gallery

@Serializable
data class DetectionSnap(
    val filename: String,
    val cam: String,
    @SerialName("ts_ms") val tsMs: Long,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val url: String,
    val confidence: Double? = null,
    val label: String? = null,
)

@Serializable
data class DetectionSnapsResponse(
    val snaps: List<DetectionSnap> = emptyList(),
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
)

// ---------------------------------------------------------------- Replay sightings API

@Serializable
data class RawSighting(
    val ts: Double? = null,
    val cam: String? = null,
    val confidence: Double? = null,
)

@Serializable
data class SightingsResponse(
    val enabled: Boolean? = null,
    val sightings: List<RawSighting> = emptyList(),
)

// ---------------------------------------------------------------- Replay index API

@Serializable
data class RawRecIndex(
    val cam: String = "",
    val name: String? = null,
    val codec: String = "avc",
    val recording: Boolean = false,
    val now: Double? = null,
    val a: String? = null,
    val b: String? = null,
    val spans: List<List<Double>> = emptyList(),
) {
    fun toRecIndex(): RecIndex = RecIndex(
        cam = cam,
        name = name,
        codec = codec,
        recording = recording,
        now = now,
        a = a,
        b = b,
        spans = spans.mapNotNull { row ->
            if (row.size >= 3) RecSpan(row[0], row[1], row[2]) else null
        },
    )
}

// ---------------------------------------------------------------- PTZ presets

@Serializable
data class PtzPreset(
    val id: String,
    val label: String,
    val directionSequence: List<PtzStep> = emptyList(),
    val serverPresetId: String? = null,
)

@Serializable
data class PtzStep(
    val dir: String,
    val durationMs: Long,
)

// ---------------------------------------------------------------- Music API

@Serializable
data class MusicStatus(
    val state: String = "idle",
    val title: String? = null,
    val url: String? = null,
    val position: Double? = null,
    val duration: Double? = null,
    val live: Boolean = false,
    val loop: Boolean = false,
    val volume: Int? = null,
    val muted: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    val error: String? = null,
)

@Serializable
data class MusicRecentItem(
    val id: String = "",
    val url: String = "",
    val title: String? = null,
    val live: Boolean = false,
    val ts: Double = 0.0,
)

@Serializable
data class MusicRecentResponse(
    val recent: List<MusicRecentItem> = emptyList(),
)

data class MusicQueueEntry(
    val id: String,
    val url: String,
    val title: String,
)

// ---------------------------------------------------------------- WebRTC Link state

enum class LinkKind {
    VIDEO,
    AUDIO,
    TALK,
}

enum class LinkPhase {
    CONNECTING,
    LIVE,
    STALLED,
    RETRYING,
    ERROR,
    STOPPED,
}

data class LinkState(
    val phase: LinkPhase = LinkPhase.STOPPED,
    val fps: Int = 0,
    val codec: String? = null,
    val decoder: String? = null,
    val size: String? = null,
    val bufferMs: Int? = null,
    val rttMs: Int? = null,
    val everPlayed: Boolean = false,
    val error: String? = null,
    val retryIn: Int? = null,
    val attempt: Int = 0,
)

enum class TalkMode {
    OFF,
    HELD,
    LATCHED,
}
