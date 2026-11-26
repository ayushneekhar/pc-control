package com.neekhar.pccontrol.feature.system

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MemInfo(
    val used: Long = 0L,
    val total: Long = 0L,
    val pct: Double = 0.0,
)

@Serializable
data class GpuInfo(
    val name: String = "",
    val util: Double = 0.0,
    @SerialName("mem_used") val memUsed: Double = 0.0,
    @SerialName("mem_total") val memTotal: Double = 0.0,
    val temp: Double = 0.0,
    val power: Double = 0.0,
)

@Serializable
data class NicStats(
    val rx: Double = 0.0,
    val tx: Double = 0.0,
)

@Serializable
data class DiskIo(
    val r: Double = 0.0,
    val w: Double = 0.0,
)

@Serializable
data class DiskUsage(
    val mount: String = "",
    val used: Long = 0L,
    val total: Long = 0L,
    val pct: Double = 0.0,
)

@Serializable
data class BatteryInfo(
    val pct: Double = 0.0,
    val plugged: Boolean = false,
)

@Serializable
data class ProcessInfo(
    val pid: Int = 0,
    val name: String = "",
    val user: String = "",
    val cpu: Double = 0.0,
    val rss: Long = 0L,
    val cmd: String = "",
    val thr: Int? = null,
    val status: String? = null,
    val gpu: Double? = null,
)

@Serializable
data class VitalsHistPoint(
    val t: Double = 0.0,
    val cpu: Double = 0.0,
    val mem: Double = 0.0,
    val temp: Double? = null,
    val gpu: Double? = null,
    val rx: Double = 0.0,
    val tx: Double = 0.0,
)

@Serializable
data class VitalsResponse(
    val t: Double = 0.0,
    val cpu: Double = 0.0,
    val percpu: List<Double> = emptyList(),
    val freq: Double? = null,
    val load: List<Double> = emptyList(),
    val mem: MemInfo = MemInfo(),
    val swap: MemInfo = MemInfo(),
    val temps: Map<String, Double?> = emptyMap(),
    val gpu: GpuInfo? = null,
    val nics: Map<String, NicStats> = emptyMap(),
    @SerialName("disk_io") val diskIo: DiskIo = DiskIo(),
    val disks: List<DiskUsage> = emptyList(),
    val bat: BatteryInfo? = null,
    val uptime: Double = 0.0,
    val procs: List<ProcessInfo>? = null,
    val hist: List<VitalsHistPoint> = emptyList(),
)

@Serializable
data class ServiceUnit(
    val unit: String = "",
    val description: String = "",
    val active: String = "",
    val sub: String = "",
    val enabled: String = "",
    val since: String = "",
    val pid: Int = 0,
    val memory: Long? = null,
    val restarts: Int? = null,
    val type: String = "",
    val result: String = "",
    val timer: Boolean = false,
)

@Serializable
data class ServiceCounts(
    val running: Int = 0,
    val failed: Int = 0,
    val inactive: Int = 0,
    val other: Int = 0,
)

@Serializable
data class HealthServices(
    val available: Boolean = false,
    val error: String? = null,
    val units: List<ServiceUnit> = emptyList(),
    val counts: ServiceCounts? = null,
)

@Serializable
data class HealthProblem(
    val kind: String = "",
    val severity: String = "warn", // "bad" | "warn"
    val title: String = "",
    val detail: String? = null,
    val unit: String? = null,
)

@Serializable
data class HealthDownloadItem(
    val id: String = "",
    val name: String = "",
    val torrent: String = "",
    val category: String = "",
    val state: String = "",
    val progress: Double = 0.0,
    val speed: Double = 0.0,
    val upspeed: Double = 0.0,
    val eta: Long? = null,
    val size: Long = 0L,
    val done: Long = 0L,
)

@Serializable
data class HealthDownloadTotal(
    val count: Int = 0,
    val downloading: Int = 0,
    val progress: Double? = null,
    val speed: Double = 0.0,
    val upspeed: Double = 0.0,
    val eta: Long? = null,
    val size: Long = 0L,
    val done: Long = 0L,
)

@Serializable
data class HealthDownloads(
    val available: Boolean = false,
    val error: String? = null,
    val qbt: String? = null,
    val total: HealthDownloadTotal? = null,
    val items: List<HealthDownloadItem> = emptyList(),
    val stalled: Int = 0,
    @SerialName("stalled_items") val stalledItems: List<String> = emptyList(),
)

@Serializable
data class ArrQueueItem(
    val title: String = "",
    val state: String = "",
    val reason: String = "",
)

@Serializable
data class ArrQueue(
    val available: Boolean = false,
    val error: String? = null,
    val queue: Int = 0,
    @SerialName("needs_action") val needsAction: Int = 0,
    val items: List<ArrQueueItem> = emptyList(),
)

@Serializable
data class JellyfinStream(
    val user: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val method: String = "direct",
    val paused: Boolean = false,
    val device: String? = null,
    val progress: Double? = null,
)

@Serializable
data class HealthJellyfin(
    val available: Boolean = false,
    val error: String? = null,
    val streams: List<JellyfinStream> = emptyList(),
)

@Serializable
data class HealthDisk(
    val mount: String = "",
    val kind: String = "",
    val used: Long = 0L,
    val total: Long = 0L,
    val pct: Double = 0.0,
)

@Serializable
data class HealthDisks(
    val available: Boolean = false,
    val error: String? = null,
    val disks: List<HealthDisk> = emptyList(),
)

@Serializable
data class VpnState(
    val enabled: Boolean = false,
    @SerialName("tunnel_up") val tunnelUp: Boolean = false,
    @SerialName("forwarded_port") val forwardedPort: Int? = null,
    @SerialName("qbt_interface") val qbtInterface: String = "",
    @SerialName("public_ip") val publicIp: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val available: Boolean? = null,
    val error: String? = null,
)

@Serializable
data class HealthResponse(
    val ts: Long = 0L,
    val problems: List<HealthProblem> = emptyList(),
    val services: HealthServices = HealthServices(),
    val downloads: HealthDownloads = HealthDownloads(),
    val radarr: ArrQueue = ArrQueue(),
    val sonarr: ArrQueue = ArrQueue(),
    val jellyfin: HealthJellyfin = HealthJellyfin(),
    val disks: HealthDisks = HealthDisks(),
    val vpn: VpnState? = null,
)

@Serializable
data class ServicesListResponse(
    val services: List<ServiceUnit> = emptyList(),
)

@Serializable
data class ServiceLogsResponse(
    val lines: List<String> = emptyList(),
)
