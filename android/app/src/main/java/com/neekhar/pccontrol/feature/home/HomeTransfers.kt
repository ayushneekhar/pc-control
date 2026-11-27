package com.neekhar.pccontrol.feature.home

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class HomeTransfersTone {
    WARN,
    ACCENT,
    DIM,
}

data class HomeTransfersSummary(
    val activeCount: Int = 0,
    val subtitle: String? = null,
    val trailing: String? = null,
    val trailingTone: HomeTransfersTone? = null,
    val progressFraction: Float? = null,
    val torrentCount: Int = 0,
    val uploadCount: Int = 0,
    val restoreCount: Int = 0,
    val downSpeedBps: Double = 0.0,
    val upSpeedBps: Double = 0.0,
) {
    val isDriveOnly: Boolean get() = activeCount > 0 && torrentCount == 0
}

val ACTIVE_TRANSFER_STATUSES: Set<String> = setOf("queued", "copying", "verifying", "cleaning")

private const val RECENT_TERMINAL_WINDOW_SEC = 1800L
private const val MAX_RECENT_TERMINAL_JOBS = 6

fun activeTransferJobs(jobs: List<TransferJob>): List<TransferJob> =
    jobs.filter { it.status in ACTIVE_TRANSFER_STATUSES }

fun visibleDownloadDriveJobs(
    jobs: List<TransferJob>,
    nowSec: Long = System.currentTimeMillis() / 1000L,
): List<TransferJob> {
    val active = mutableListOf<TransferJob>()
    val terminal = mutableListOf<TransferJob>()
    for (job in jobs) {
        if (job.status in ACTIVE_TRANSFER_STATUSES) {
            active.add(job)
        } else if (
            job.status == "failed" ||
            nowSec - (job.finishedAt ?: job.createdAt) <= RECENT_TERMINAL_WINDOW_SEC
        ) {
            terminal.add(job)
        }
    }
    terminal.sortByDescending { it.finishedAt ?: it.createdAt }
    return active + terminal.take(MAX_RECENT_TERMINAL_JOBS)
}

fun mergeEnqueuedJobs(existing: List<TransferJob>, created: List<TransferJob>): List<TransferJob> {
    if (created.isEmpty()) return existing
    val byId = LinkedHashMap<String, TransferJob>(existing.size + created.size)
    for (job in existing) byId[job.id] = job
    for (job in created) byId[job.id] = job
    return byId.values.toList()
}

fun transferStatusLabel(job: TransferJob): String = when (job.status) {
    "queued" -> "Queued"
    "copying" -> if (job.op == "restore") "Restoring" else "Offloading"
    "verifying" -> "Verifying"
    "cleaning" -> "Finishing"
    "done" -> "Done"
    "failed" -> "Failed"
    "cancelled" -> "Cancelled"
    else -> job.status
}

fun lastCatSighting(det: DetectState?): CatSighting? {
    if (det == null) return null
    var best: CatSighting? = null
    for ((key, cam) in det.cams) {
        val ts = cam.lastSeenTs ?: continue
        if (ts > 0.0 && (best == null || ts > best.ts)) {
            best = CatSighting(ts = ts, name = CAM_NAMES[key] ?: cam.name ?: key)
        }
    }
    if (best == null && det.lastAlert != null && det.lastAlert.ts > 0.0) {
        val alert = det.lastAlert
        best = CatSighting(ts = alert.ts, name = CAM_NAMES[alert.cam] ?: alert.name ?: alert.cam)
    }
    return best
}

private fun computeDriveProgress(activeDrive: List<TransferJob>): Int? {
    val started = activeDrive.any { it.status != "queued" }
    if (!started) return null

    var sumDone = 0L
    var sumTotal = 0L
    for (j in activeDrive) {
        val total = when {
            j.bytesTotal > 0L -> j.bytesTotal
            j.sizeBytes > 0L -> j.sizeBytes
            else -> 0L
        }
        if (total > 0L) {
            sumTotal += total
            val done = if (j.status == "verifying" || j.status == "cleaning") {
                total
            } else {
                min(total, max(0L, j.bytesDone))
            }
            sumDone += done
        }
    }
    if (sumTotal > 0L) {
        return min(100, max(0, ((sumDone.toDouble() / sumTotal.toDouble()) * 100.0).roundToInt()))
    }

    val avg = activeDrive.sumOf { if (it.pct.isFinite()) it.pct else 0.0 } / activeDrive.size
    return min(100, max(0, avg.roundToInt()))
}

private fun drivePhaseHint(jobs: List<TransferJob>): String? = when {
    jobs.any { it.status == "verifying" } -> "verifying"
    jobs.any { it.status == "cleaning" } -> "finishing"
    else -> null
}

fun summarizeHomeTransfers(
    downloads: DownloadsReply?,
    driveJobs: List<TransferJob>,
): HomeTransfersSummary {
    val torrentCount = downloads?.downloads?.size ?: 0
    val torrentSpeed = max(0.0, downloads?.total?.speed ?: 0.0)
    val torrentProgress = downloads?.total?.progress?.let { (it * 100.0).roundToInt().coerceIn(0, 100) }

    val activeDrive = activeTransferJobs(driveJobs)
    val offloads = activeDrive.filter { it.op == "offload" }
    val restores = activeDrive.filter { it.op == "restore" }

    val uploadCount = offloads.size
    val restoreCount = restores.size
    val uploadSpeed = offloads.sumOf { if (it.status == "copying" && it.speedBps > 0.0) it.speedBps else 0.0 }
    val restoreSpeed = restores.sumOf { if (it.status == "copying" && it.speedBps > 0.0) it.speedBps else 0.0 }

    val downCount = torrentCount + restoreCount
    val downSpeed = torrentSpeed + restoreSpeed
    val activeCount = torrentCount + uploadCount + restoreCount

    if (activeCount == 0) {
        return HomeTransfersSummary(activeCount = 0)
    }

    val driveProgress = if (activeDrive.isNotEmpty()) computeDriveProgress(activeDrive) else null

    // 1. Only torrent downloads active
    if (torrentCount > 0 && uploadCount == 0 && restoreCount == 0) {
        return HomeTransfersSummary(
            activeCount = activeCount,
            subtitle = if (torrentSpeed > 0.0) {
                "$torrentCount active · ↓ ${PcFormatters.formatRate(torrentSpeed)}"
            } else {
                "$torrentCount active"
            },
            trailing = torrentProgress?.let { "$it%" },
            trailingTone = if (torrentProgress != null) HomeTransfersTone.WARN else null,
            progressFraction = torrentProgress?.let { it / 100f },
            torrentCount = torrentCount,
            downSpeedBps = torrentSpeed,
        )
    }

    // 2. Only Drive uploads (offloads) active
    if (torrentCount == 0 && uploadCount > 0 && restoreCount == 0) {
        val phase = if (uploadSpeed > 0.0) null else drivePhaseHint(offloads)
        val subtitle = when {
            uploadSpeed > 0.0 -> "$uploadCount uploading · ↑ ${PcFormatters.formatRate(uploadSpeed)}"
            phase != null -> "$uploadCount uploading · $phase"
            else -> "$uploadCount uploading"
        }
        return HomeTransfersSummary(
            activeCount = activeCount,
            subtitle = subtitle,
            trailing = driveProgress?.let { "$it%" },
            trailingTone = if (driveProgress != null) HomeTransfersTone.ACCENT else null,
            progressFraction = driveProgress?.let { it / 100f },
            uploadCount = uploadCount,
            upSpeedBps = uploadSpeed,
        )
    }

    // 3. Only Drive restores active
    if (torrentCount == 0 && uploadCount == 0 && restoreCount > 0) {
        val phase = if (restoreSpeed > 0.0) null else drivePhaseHint(restores)
        val subtitle = when {
            restoreSpeed > 0.0 -> "$restoreCount restoring · ↓ ${PcFormatters.formatRate(restoreSpeed)}"
            phase != null -> "$restoreCount restoring · $phase"
            else -> "$restoreCount restoring"
        }
        return HomeTransfersSummary(
            activeCount = activeCount,
            subtitle = subtitle,
            trailing = driveProgress?.let { "$it%" },
            trailingTone = if (driveProgress != null) HomeTransfersTone.ACCENT else null,
            progressFraction = driveProgress?.let { it / 100f },
            restoreCount = restoreCount,
            downSpeedBps = restoreSpeed,
        )
    }

    // 4. Both downward (torrent and/or restore) and upward (Drive offload) active
    if (downCount > 0 && uploadCount > 0) {
        val subtitle = when {
            downSpeed > 0.0 && uploadSpeed > 0.0 ->
                "↓ ${PcFormatters.formatRate(downSpeed)} · ↑ ${PcFormatters.formatRate(uploadSpeed)}"
            downSpeed > 0.0 ->
                "↓ ${PcFormatters.formatRate(downSpeed)} · $uploadCount uploading"
            uploadSpeed > 0.0 ->
                "$downCount active · ↑ ${PcFormatters.formatRate(uploadSpeed)}"
            else ->
                "$downCount active · $uploadCount uploading"
        }
        val combinedPct = listOfNotNull(torrentProgress, driveProgress).takeIf { it.isNotEmpty() }?.average()
        return HomeTransfersSummary(
            activeCount = activeCount,
            subtitle = subtitle,
            trailing = "$activeCount active",
            trailingTone = HomeTransfersTone.DIM,
            progressFraction = combinedPct?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            torrentCount = torrentCount,
            uploadCount = uploadCount,
            restoreCount = restoreCount,
            downSpeedBps = downSpeed,
            upSpeedBps = uploadSpeed,
        )
    }

    // 5. Both torrents and Drive restores active (no uploads)
    val subtitle = if (downSpeed > 0.0) {
        "$activeCount active · ↓ ${PcFormatters.formatRate(downSpeed)}"
    } else {
        "$activeCount active"
    }
    val pct = torrentProgress ?: driveProgress
    return HomeTransfersSummary(
        activeCount = activeCount,
        subtitle = subtitle,
        trailing = pct?.let { "$it%" },
        trailingTone = when {
            torrentProgress != null -> HomeTransfersTone.WARN
            driveProgress != null -> HomeTransfersTone.ACCENT
            else -> null
        },
        progressFraction = pct?.let { it / 100f },
        torrentCount = torrentCount,
        restoreCount = restoreCount,
        downSpeedBps = downSpeed,
    )
}
