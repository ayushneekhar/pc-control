package com.neekhar.pccontrol.feature.downloads

import com.neekhar.pccontrol.feature.system.ArrQueue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TorrentItem(
    val hash: String = "",
    val id: String = "",
    val name: String = "",
    val torrent: String = "",
    val category: String = "",
    val state: String = "",
    @SerialName("raw_state") val rawState: String = "",
    val paused: Boolean = false,
    val progress: Double = 0.0,
    val speed: Double = 0.0,
    val upspeed: Double = 0.0,
    val eta: Long? = null,
    val size: Long = 0L,
    val done: Long = 0L,
    val seeds: Int = 0,
    val ratio: Double = 0.0,
    val added: Long = 0L,
)

@Serializable
data class DownloadsTotalAll(
    val count: Int = 0,
    val active: Int = 0,
    val downloading: Int = 0,
    val progress: Double? = null,
    val speed: Double = 0.0,
    val upspeed: Double = 0.0,
    val eta: Long? = null,
)

@Serializable
data class DownloadsAllResponse(
    val qbt: String = "ok",
    @SerialName("alt_speed") val altSpeed: Boolean = false,
    val torrents: List<TorrentItem> = emptyList(),
    val total: DownloadsTotalAll = DownloadsTotalAll(),
    val radarr: ArrQueue = ArrQueue(),
    val sonarr: ArrQueue = ArrQueue(),
    val ts: Long = 0L,
)

@Serializable
data class TorrentHashRequest(
    val hash: String,
)

@Serializable
data class TorrentDeleteRequest(
    val hash: String,
    @SerialName("delete_files") val deleteFiles: Boolean,
)

@Serializable
data class AltSpeedRequest(
    val enabled: Boolean,
)

@Serializable
data class AltSpeedResponse(
    val ok: Boolean = false,
    @SerialName("alt_speed") val altSpeed: Boolean = false,
)

@Serializable
data class ArrRetryRequest(
    val app: String,
)

@Serializable
data class OkResponse(
    val ok: Boolean = false,
)
