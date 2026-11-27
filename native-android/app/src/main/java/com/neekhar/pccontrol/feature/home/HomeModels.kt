package com.neekhar.pccontrol.feature.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RootdInfo(
    val state: String = "unknown",
    val version: Int? = null,
    val devices: Int? = null,
)

@Serializable
data class ApkInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val sha256: String = "",
    val size: Long = 0L,
    val built: Long = 0L,
)

@Serializable
data class PushInfo(
    val configured: Boolean = false,
    val devices: Int = 0,
)

@Serializable
data class InfoResponse(
    val host: String = "",
    val version: Int = 0,
    val rootd: RootdInfo = RootdInfo(),
    val apk: ApkInfo? = null,
    val push: PushInfo? = null,
)

@Serializable
data class RouteItem(
    val id: String = "",
    val name: String = "",
    val icon: String = "web",
    val subtitle: String = "",
    val url: String = "",
    val path: String = "",
    val port: Int = 443,
    val target: String = "",
    val kind: String? = null,
    val hidden: Boolean = false,
    val app: String? = null,
)

@Serializable
data class RoutesResponse(
    val routes: List<RouteItem> = emptyList(),
)

@Serializable
data class CatCamState(
    val enabled: Boolean = true,
    val units: Map<String, String> = emptyMap(),
)

@Serializable
data class CatCamToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class KeyringStatus(
    val locked: Boolean = false,
    val rdp: String = "",
)

@Serializable
data class DetectCamState(
    val name: String? = null,
    @SerialName("last_seen_ts") val lastSeenTs: Double? = null,
)

@Serializable
data class DetectLastAlert(
    val cam: String = "",
    val name: String? = null,
    val ts: Double = 0.0,
)

@Serializable
data class DetectState(
    val enabled: Boolean = false,
    val cams: Map<String, DetectCamState> = emptyMap(),
    @SerialName("last_alert") val lastAlert: DetectLastAlert? = null,
)

@Serializable
data class DownloadItem(
    val id: String = "",
    val name: String = "",
    val torrent: String = "",
    val category: String = "",
    val state: String = "",
    val progress: Double = 0.0,
    val speed: Double = 0.0,
    val eta: Long? = null,
    val size: Long = 0L,
    val done: Long = 0L,
)

@Serializable
data class DownloadsTotal(
    val count: Int = 0,
    val downloading: Int = 0,
    val progress: Double? = null,
    val speed: Double = 0.0,
    val eta: Long? = null,
)

@Serializable
data class DownloadsReply(
    val enabled: Boolean = true,
    val qbt: String = "ok",
    val downloads: List<DownloadItem> = emptyList(),
    val total: DownloadsTotal = DownloadsTotal(),
)

@Serializable
data class TransferJob(
    val id: String = "",
    val op: String = "offload", // "offload" | "restore"
    @SerialName("item_id") val itemId: String = "",
    val kind: String = "movie", // "movie" | "season"
    val title: String = "",
    val subtitle: String? = null,
    @SerialName("rel_path") val relPath: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val status: String = "queued", // queued | copying | verifying | cleaning | done | failed | cancelled
    @SerialName("bytes_done") val bytesDone: Long = 0L,
    @SerialName("bytes_total") val bytesTotal: Long = 0L,
    val pct: Double = 0.0,
    @SerialName("speed_bps") val speedBps: Double = 0.0,
    @SerialName("eta_s") val etaS: Long? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
)

@Serializable
data class DriveStatusResponse(
    val configured: Boolean = false,
    val mounted: Boolean = false,
    @SerialName("mount_point") val mountPoint: String = "",
    val jobs: List<TransferJob> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val ts: Long = 0L,
)

@Serializable
data class SpaceJobsSsePayload(
    val jobs: List<TransferJob> = emptyList(),
    val ts: Long? = null,
)

@Serializable
data class CancelSpaceJobResponse(
    val ok: Boolean = false,
    val job: TransferJob? = null,
)

data class CamDefinition(
    val id: String,
    val name: String,
)

val HOME_CAMS: List<CamDefinition> = listOf(
    CamDefinition(id = "cat", name = "Living room"),
    CamDefinition(id = "cat2", name = "Hall"),
    CamDefinition(id = "cat3", name = "Food bowl"),
)

val CAM_NAMES: Map<String, String> = mapOf(
    "cat" to "Living room",
    "cat2" to "Hall",
    "cat3" to "Food bowl",
    "cat3hd" to "Food bowl",
)

data class CatSighting(
    val ts: Double,
    val name: String,
)
