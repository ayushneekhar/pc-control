package com.neekhar.pccontrol.feature.watch.downloads

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal sealed interface WatchDownloadTransferResult {
    data class Completed(val totalBytes: Long) : WatchDownloadTransferResult
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : WatchDownloadTransferResult
    data class TailscaleRequired(val downloadedBytes: Long, val totalBytes: Long) : WatchDownloadTransferResult
    data object Cancelled : WatchDownloadTransferResult
    data class Failed(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val errorMessage: String,
    ) : WatchDownloadTransferResult
}

internal object WatchDownloadTransfer {
    const val MAX_TRANSIENT_RETRIES = 6
    val RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 12_000L, 15_000L)
    private const val SLEEP_SLICE_MS = 200L
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val BUFFER_SIZE_BYTES = 64 * 1024

    fun backoffMsForFailure(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return RETRY_BACKOFF_MS[0]
        return RETRY_BACKOFF_MS[(consecutiveFailures - 1).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]
    }

    fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    fun downloadWithResume(
        client: OkHttpClient,
        url: String,
        userAgent: String,
        videoFile: File,
        initialTotalBytes: Long,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onCallCreated: (Call) -> Unit = {},
        onCallFinished: () -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long, persist: Boolean) -> Unit = { _, _, _, _ -> },
        clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
        sleepStepMs: (Long) -> Unit = { Thread.sleep(it) },
    ): WatchDownloadTransferResult {
        var knownTotalBytes = initialTotalBytes.coerceAtLeast(0L)
        var consecutiveFailures = 0

        while (true) {
            if (isCancelled()) return WatchDownloadTransferResult.Cancelled
            val startOffset = if (videoFile.isFile) videoFile.length() else 0L
            if (isPaused()) {
                return WatchDownloadTransferResult.Paused(startOffset, maxOf(knownTotalBytes, startOffset))
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
            if (startOffset > 0L) {
                reqBuilder.header("Range", "bytes=$startOffset-")
            }

            val call = client.newCall(reqBuilder.build())
            onCallCreated(call)

            try {
                call.execute().use { resp ->
                    val code = resp.code
                    if (code == 416 && knownTotalBytes > 0L && startOffset >= knownTotalBytes) {
                        return WatchDownloadTransferResult.Completed(startOffset)
                    }
                    if (code == 403) {
                        return WatchDownloadTransferResult.TailscaleRequired(
                            downloadedBytes = startOffset,
                            totalBytes = maxOf(knownTotalBytes, startOffset),
                        )
                    }
                    if (code in 500..599 || code == 408 || code == 429) {
                        throw IOException("Server returned HTTP $code")
                    }
                    if (code != 200 && code != 206) {
                        return WatchDownloadTransferResult.Failed(
                            downloadedBytes = startOffset,
                            totalBytes = maxOf(knownTotalBytes, startOffset),
                            errorMessage = "Download failed (HTTP $code)",
                        )
                    }

                    val isResume = (code == 206 && startOffset > 0L)
                    val contentLen = resp.body?.contentLength() ?: -1L
                    val resolvedTotal = when {
                        isResume -> parseTotalFromContentRange(resp.header("Content-Range"))
                            ?: if (contentLen > 0L) startOffset + contentLen else knownTotalBytes
                        contentLen > 0L -> contentLen
                        else -> knownTotalBytes
                    }
                    if (resolvedTotal > 0L) {
                        knownTotalBytes = resolvedTotal
                    }

                    var writtenBytes = if (isResume) startOffset else 0L
                    val body = resp.body ?: throw IOException("Empty download response body")

                    FileOutputStream(videoFile, isResume).use { out ->
                        val buf = ByteArray(BUFFER_SIZE_BYTES)
                        val input = body.byteStream()
                        var lastTickMs = clockMs()
                        var bytesSinceTick = 0L
                        var smoothedBps = 0L

                        while (true) {
                            if (isCancelled()) {
                                return WatchDownloadTransferResult.Cancelled
                            }
                            if (isPaused()) {
                                out.flush()
                                return WatchDownloadTransferResult.Paused(
                                    downloadedBytes = writtenBytes,
                                    totalBytes = maxOf(knownTotalBytes, writtenBytes),
                                )
                            }
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            writtenBytes += read
                            bytesSinceTick += read
                            if (writtenBytes > startOffset) {
                                consecutiveFailures = 0
                            }

                            val nowMs = clockMs()
                            val elapsedMs = nowMs - lastTickMs
                            if (elapsedMs >= PROGRESS_INTERVAL_MS) {
                                val instantBps = (bytesSinceTick * 1000L) / elapsedMs.coerceAtLeast(1L)
                                smoothedBps = if (smoothedBps == 0L) {
                                    instantBps
                                } else {
                                    ((smoothedBps * 7L) + (instantBps * 3L)) / 10L
                                }
                                lastTickMs = nowMs
                                bytesSinceTick = 0L
                                onProgress(
                                    writtenBytes,
                                    maxOf(knownTotalBytes, writtenBytes),
                                    smoothedBps,
                                    false,
                                )
                            }
                        }
                        out.flush()
                    }

                    if (isCancelled()) return WatchDownloadTransferResult.Cancelled
                    if (isPaused()) {
                        return WatchDownloadTransferResult.Paused(
                            downloadedBytes = writtenBytes,
                            totalBytes = maxOf(knownTotalBytes, writtenBytes),
                        )
                    }

                    // Treat early EOF before knownTotalBytes as a transient interruption and resume via Range.
                    if (knownTotalBytes > 0L && writtenBytes < knownTotalBytes) {
                        throw IOException("Stream ended early at $writtenBytes / $knownTotalBytes bytes")
                    }

                    return WatchDownloadTransferResult.Completed(writtenBytes)
                }
            } catch (e: IOException) {
                if (isCancelled()) {
                    return WatchDownloadTransferResult.Cancelled
                }
                val diskBytes = if (videoFile.isFile) videoFile.length() else startOffset
                if (isPaused()) {
                    return WatchDownloadTransferResult.Paused(
                        downloadedBytes = diskBytes,
                        totalBytes = maxOf(knownTotalBytes, diskBytes),
                    )
                }
                val madeProgress = diskBytes > startOffset
                if (madeProgress) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures += 1
                }
                if (consecutiveFailures <= MAX_TRANSIENT_RETRIES) {
                    onProgress(
                        diskBytes,
                        maxOf(knownTotalBytes, diskBytes),
                        0L,
                        true,
                    )
                    val waitMs = if (madeProgress) RETRY_BACKOFF_MS[0] else backoffMsForFailure(consecutiveFailures)
                    val shouldContinue = sleepInterruptibly(
                        durationMs = waitMs,
                        isCancelled = isCancelled,
                        isPaused = isPaused,
                        sleepStepMs = sleepStepMs,
                    )
                    if (!shouldContinue) {
                        if (isCancelled()) return WatchDownloadTransferResult.Cancelled
                        return WatchDownloadTransferResult.Paused(
                            downloadedBytes = diskBytes,
                            totalBytes = maxOf(knownTotalBytes, diskBytes),
                        )
                    }
                    continue
                }
                return WatchDownloadTransferResult.Failed(
                    downloadedBytes = diskBytes,
                    totalBytes = maxOf(knownTotalBytes, diskBytes),
                    errorMessage = e.message ?: "Network interrupted. Tap Resume when on Tailscale.",
                )
            } finally {
                onCallFinished()
            }
        }
    }

    private fun sleepInterruptibly(
        durationMs: Long,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        sleepStepMs: (Long) -> Unit,
    ): Boolean {
        var remaining = durationMs
        while (remaining > 0L) {
            if (isCancelled() || isPaused()) return false
            val step = minOf(remaining, SLEEP_SLICE_MS)
            try {
                sleepStepMs(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= step
        }
        return !isCancelled() && !isPaused()
    }
}
