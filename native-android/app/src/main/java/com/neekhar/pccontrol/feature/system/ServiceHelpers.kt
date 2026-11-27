package com.neekhar.pccontrol.feature.system

import java.util.Locale

enum class SystemTab(val label: String) {
    VITALS("Vitals"),
    SERVICES("Services"),
}

enum class ServiceTone(val sortOrder: Int) {
    BAD(0),
    WARN(1),
    OK(2),
    DIM(3),
}

enum class ServiceGroup(val label: String) {
    ALL("All"),
    MEDIA("Media"),
    CAMS("Cams"),
    AGENTS("Agents"),
}

enum class ProcessSortKey(val label: String, val defaultDescending: Boolean) {
    CPU("CPU", true),
    RSS("Mem", true),
    NAME("Name", false),
    PID("PID", true),
    USER("User", false),
    THREADS("Threads", true),
    GPU("GPU", true),
}

data class ServicesSummaryCopy(
    val headline: String,
    val detail: String?,
)

object ServiceHelpers {
    private val CAMS_REGEX = Regex("^(cam-|camrec|catcam|catdetect|cat-)")
    private val MEDIA_REGEX = Regex("^(jellyfin|qbittorrent|radarr|sonarr|seerr|bazarr|prowlarr|flaresolverr|import-watch|media)")
    private val AGENTS_REGEX = Regex("(agent|qwen|ollama)")

    private val FRIENDLY_NAMES: Map<String, String> = mapOf(
        "jellyfin" to "Jellyfin",
        "qbittorrent" to "qBittorrent",
        "radarr" to "Radarr",
        "sonarr" to "Sonarr",
        "seerr" to "Seerr",
        "bazarr" to "Bazarr",
        "prowlarr" to "Prowlarr",
        "flaresolverr" to "FlareSolverr",
        "catcam" to "Cat cameras",
        "catdetect" to "Cat detection",
        "camrec" to "Recording",
        "cam-music" to "Cam music",
        "cam-ptz" to "Cam PTZ",
    )

    private val WORD_START = Regex("\\b[a-z]")

    fun tone(service: ServiceUnit): ServiceTone = when (service.active) {
        "active" -> ServiceTone.OK
        "failed" -> ServiceTone.BAD
        "activating", "deactivating", "reloading" -> ServiceTone.WARN
        else -> ServiceTone.DIM
    }

    fun group(service: ServiceUnit): ServiceGroup {
        val base = service.unit.removeSuffix(".service")
        return when {
            CAMS_REGEX.containsMatchIn(base) -> ServiceGroup.CAMS
            MEDIA_REGEX.containsMatchIn(base) -> ServiceGroup.MEDIA
            AGENTS_REGEX.containsMatchIn(base) -> ServiceGroup.AGENTS
            else -> ServiceGroup.ALL
        }
    }

    fun friendlyName(unit: String): String {
        val base = unit.removeSuffix(".service")
        FRIENDLY_NAMES[base]?.let { return it }
        val spaced = base.replace('-', ' ').replace('_', ' ')
        return WORD_START.replace(spaced) { it.value.uppercase(Locale.US) }
    }

    fun statusLabel(service: ServiceUnit): String {
        if (service.timer && service.active != "active") return "timer"
        return service.active.ifBlank { "unknown" }
    }

    fun isProtectedUnit(unit: String): Boolean = unit == "pc-api.service"

    fun sortAndFilter(
        services: List<ServiceUnit>,
        filter: ServiceGroup,
        catCamEnabled: Boolean = true,
    ): List<ServiceUnit> {
        val effectiveFilter = if (!catCamEnabled && filter == ServiceGroup.CAMS) ServiceGroup.ALL else filter
        val sorted = services.sortedWith(
            compareBy<ServiceUnit> { tone(it).sortOrder }.thenBy { it.unit },
        )
        return if (effectiveFilter == ServiceGroup.ALL) {
            sorted
        } else {
            sorted.filter { group(it) == effectiveFilter }
        }
    }

    fun filterAndSortProcesses(
        processes: List<ProcessInfo>,
        query: String,
        sortKey: ProcessSortKey,
        descending: Boolean,
        limit: Int = if (query.isBlank()) 40 else 100,
    ): Pair<List<ProcessInfo>, Int> {
        val q = query.trim().lowercase(Locale.US)
        val filtered = if (q.isEmpty()) {
            processes
        } else {
            processes.filter { p ->
                p.cmd.lowercase(Locale.US).contains(q) ||
                    p.name.lowercase(Locale.US).contains(q) ||
                    p.pid.toString() == q
            }
        }
        val baseComparator: Comparator<ProcessInfo> = when (sortKey) {
            ProcessSortKey.CPU -> compareBy { it.cpu }
            ProcessSortKey.RSS -> compareBy { it.rss }
            ProcessSortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ProcessSortKey.PID -> compareBy { it.pid }
            ProcessSortKey.USER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.user }
            ProcessSortKey.THREADS -> compareBy { it.thr ?: -1 }
            ProcessSortKey.GPU -> compareBy { it.gpu ?: -1.0 }
        }
        val finalComparator = if (descending) baseComparator.reversed() else baseComparator
        val sorted = filtered.sortedWith(finalComparator)
        return sorted.take(limit) to filtered.size
    }

    private val CPU_TEMP_ALIASES = setOf("tctl", "tdie", "package id 0", "coretemp", "k10temp")

    fun servicesSummaryCopy(
        runningCount: Int,
        counts: ServiceCounts?,
        problemCount: Int,
    ): ServicesSummaryCopy {
        if (problemCount > 0) {
            val headline = "$problemCount problem${if (problemCount == 1) "" else "s"}"
            val detail = counts?.let { c ->
                buildList {
                    add("${c.running} running")
                    if (c.failed > 0) add("${c.failed} failed")
                    if (c.inactive > 0) add("${c.inactive} inactive")
                    if (c.other > 0) add("${c.other} other")
                }.joinToString(" · ")
            }
            return ServicesSummaryCopy(headline = headline, detail = detail)
        }

        if (counts == null) {
            return ServicesSummaryCopy(
                headline = "$runningCount running",
                detail = "0 failed · all healthy",
            )
        }

        if (counts.failed > 0) {
            val detail = buildList {
                if (counts.inactive > 0) add("${counts.inactive} inactive")
                if (counts.other > 0) add("${counts.other} other")
            }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            return ServicesSummaryCopy(
                headline = "${counts.running} running · ${counts.failed} failed",
                detail = detail,
            )
        }

        if (counts.inactive > 0 || counts.other > 0) {
            val headline = if (counts.inactive > 0) {
                "${counts.running} running · ${counts.inactive} inactive"
            } else {
                "${counts.running} running · ${counts.other} other"
            }
            val detail = buildList {
                if (counts.inactive > 0 && counts.other > 0) add("${counts.other} other")
                add("0 failed · all healthy")
            }.joinToString(" · ")
            return ServicesSummaryCopy(headline = headline, detail = detail)
        }

        return ServicesSummaryCopy(
            headline = "All ${counts.running} running",
            detail = "0 failed · all healthy",
        )
    }

    fun sortedTemperatures(temps: Map<String, Double?>): List<Pair<String, Double>> =
        temps.entries
            .mapNotNull { (k, v) -> if (v != null && v.isFinite()) k to v else null }
            .sortedWith { (a, _), (b, _) ->
                val aCpu = a.equals("cpu", ignoreCase = true)
                val bCpu = b.equals("cpu", ignoreCase = true)
                when {
                    aCpu && !bCpu -> -1
                    bCpu && !aCpu -> 1
                    else -> a.compareTo(b)
                }
            }

    fun prettyTempLabel(key: String): String = when (key.lowercase(Locale.US)) {
        "cpu" -> "CPU"
        "gpu" -> "GPU"
        "nvme" -> "NVMe"
        else -> key
    }

    fun primaryCpuTemp(vitals: VitalsResponse?): Double? {
        if (vitals == null) return null
        val valid = vitals.temps.entries.mapNotNull { (k, v) ->
            if (v != null && v.isFinite()) k to v else null
        }
        if (valid.isEmpty()) return null
        valid.firstOrNull { (k, _) -> k.equals("cpu", ignoreCase = true) }?.let { return it.second }
        valid.firstOrNull { (k, _) -> k.contains("cpu", ignoreCase = true) }?.let { return it.second }
        valid.firstOrNull { (k, _) -> k.lowercase(Locale.US) in CPU_TEMP_ALIASES }?.let { return it.second }
        return valid.maxOfOrNull { it.second }
    }
}
