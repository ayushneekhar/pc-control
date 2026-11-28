package com.neekhar.pccontrol.feature.watch

import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadTransfer
import com.neekhar.pccontrol.feature.watch.downloads.WatchDownloadTransferResult
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WatchDownloadTransferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun streamingBody(
        contentLength: Long,
        stream: InputStream,
        mediaType: MediaType = "video/mp4".toMediaType(),
    ): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = contentLength
        override fun source(): BufferedSource = stream.source().buffer()
    }

    private fun failingAfterBytesStream(
        data: ByteArray,
        failAfterBytes: Int,
        message: String = "stream was reset: CANCEL",
    ): InputStream = object : InputStream() {
        private var pos = 0

        override fun read(): Int {
            if (pos >= failAfterBytes) throw IOException(message)
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= failAfterBytes) throw IOException(message)
            val remainingBeforeFail = failAfterBytes - pos
            val remainingInData = data.size - pos
            if (remainingInData <= 0) return -1
            val toRead = minOf(len, remainingBeforeFail, remainingInData)
            System.arraycopy(data, pos, b, off, toRead)
            pos += toRead
            return toRead
        }
    }

    private fun clientWithInterceptor(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(interceptor)
            .build()

    @Test
    fun `transient stream reset mid-transfer automatically resumes with Range and completes intact`() {
        val fullPayload = ByteArray(16_384) { (it % 251).toByte() }
        val videoFile = File(tempFolder.root, "movie.mp4")
        val seenRanges = mutableListOf<String?>()
        val attempts = AtomicInteger(0)
        val sleeps = mutableListOf<Long>()

        val client = clientWithInterceptor { chain ->
            val attempt = attempts.incrementAndGet()
            val req = chain.request()
            seenRanges.add(req.header("Range"))
            when (attempt) {
                1 -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        streamingBody(
                            contentLength = fullPayload.size.toLong(),
                            stream = failingAfterBytesStream(fullPayload, failAfterBytes = 6_000),
                        ),
                    )
                    .build()
                2 -> {
                    val tail = fullPayload.copyOfRange(6_000, fullPayload.size)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 6000-${fullPayload.size - 1}/${fullPayload.size}")
                        .body(
                            streamingBody(
                                contentLength = tail.size.toLong(),
                                stream = failingAfterBytesStream(tail, failAfterBytes = 5_000),
                            ),
                        )
                        .build()
                }
                else -> {
                    val tail = fullPayload.copyOfRange(11_000, fullPayload.size)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 11000-${fullPayload.size - 1}/${fullPayload.size}")
                        .body(tail.toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = client,
            url = "https://hp.ts.net/pc/api/watch/download/item1",
            userAgent = "PcControl-Test",
            videoFile = videoFile,
            initialTotalBytes = fullPayload.size.toLong(),
            isCancelled = { false },
            isPaused = { false },
            sleepStepMs = { sleeps.add(it) },
        )

        assertEquals(WatchDownloadTransferResult.Completed(fullPayload.size.toLong()), result)
        assertEquals(listOf(null, "bytes=6000-", "bytes=11000-"), seenRanges)
        assertArrayEquals(fullPayload, videoFile.readBytes())
        assertTrue(sleeps.sum() == 2_000L)
    }

    @Test
    fun `early EOF before Content-Length resumes remaining bytes instead of finalizing truncated file`() {
        val fullPayload = ByteArray(10_000) { ((it * 7) % 256).toByte() }
        val videoFile = File(tempFolder.root, "early_eof.mp4")
        val seenRanges = mutableListOf<String?>()
        val attempts = AtomicInteger(0)

        val client = clientWithInterceptor { chain ->
            val attempt = attempts.incrementAndGet()
            val req = chain.request()
            seenRanges.add(req.header("Range"))
            if (attempt == 1) {
                val truncated = fullPayload.copyOfRange(0, 4_096)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        streamingBody(
                            contentLength = fullPayload.size.toLong(),
                            stream = truncated.inputStream(),
                        ),
                    )
                    .build()
            } else {
                val remainder = fullPayload.copyOfRange(4_096, fullPayload.size)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 4096-${fullPayload.size - 1}/${fullPayload.size}")
                    .body(remainder.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = client,
            url = "https://hp.ts.net/pc/api/watch/download/item2",
            userAgent = "PcControl-Test",
            videoFile = videoFile,
            initialTotalBytes = fullPayload.size.toLong(),
            isCancelled = { false },
            isPaused = { false },
            sleepStepMs = {},
        )

        assertEquals(WatchDownloadTransferResult.Completed(fullPayload.size.toLong()), result)
        assertEquals(listOf(null, "bytes=4096-"), seenRanges)
        assertArrayEquals(fullPayload, videoFile.readBytes())
    }

    @Test
    fun `unexpected 200 response on Range request truncates and rewrites cleanly from byte zero`() {
        val fullPayload = ByteArray(8_192) { ((it * 3) % 251).toByte() }
        val videoFile = File(tempFolder.root, "rewrite.mp4").apply {
            writeBytes(ByteArray(2_048) { 0x55 })
        }

        val client = clientWithInterceptor { chain ->
            val req = chain.request()
            assertEquals("bytes=2048-", req.header("Range"))
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(fullPayload.toResponseBody("video/mp4".toMediaType()))
                .build()
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = client,
            url = "https://hp.ts.net/pc/api/watch/download/item3",
            userAgent = "PcControl-Test",
            videoFile = videoFile,
            initialTotalBytes = fullPayload.size.toLong(),
            isCancelled = { false },
            isPaused = { false },
            sleepStepMs = {},
        )

        assertEquals(WatchDownloadTransferResult.Completed(fullPayload.size.toLong()), result)
        assertArrayEquals(fullPayload, videoFile.readBytes())
    }

    @Test
    fun `progress resets consecutive failure counter across multiple drops`() {
        val fullPayload = ByteArray(6_000) { (it % 199).toByte() }
        val videoFile = File(tempFolder.root, "reset_counter.mp4")
        val attempts = AtomicInteger(0)

        val client = clientWithInterceptor { chain ->
            val req = chain.request()
            val n = attempts.incrementAndGet()
            when {
                n in 1..4 -> throw IOException("stream was reset: INTERNAL_ERROR")
                n == 5 -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        streamingBody(
                            contentLength = fullPayload.size.toLong(),
                            stream = failingAfterBytesStream(fullPayload, failAfterBytes = 3_000),
                        ),
                    )
                    .build()
                n in 6..10 -> throw IOException("stream was reset: CANCEL")
                else -> {
                    val tail = fullPayload.copyOfRange(3_000, fullPayload.size)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes 3000-${fullPayload.size - 1}/${fullPayload.size}")
                        .body(tail.toResponseBody("video/mp4".toMediaType()))
                        .build()
                }
            }
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = client,
            url = "https://hp.ts.net/pc/api/watch/download/item4",
            userAgent = "PcControl-Test",
            videoFile = videoFile,
            initialTotalBytes = fullPayload.size.toLong(),
            isCancelled = { false },
            isPaused = { false },
            sleepStepMs = {},
        )

        assertEquals(WatchDownloadTransferResult.Completed(fullPayload.size.toLong()), result)
        assertEquals(11, attempts.get())
        assertArrayEquals(fullPayload, videoFile.readBytes())
    }

    @Test
    fun `fails after exhausting max consecutive zero-progress retries with expected backoff`() {
        val videoFile = File(tempFolder.root, "exhausted.mp4")
        val attempts = AtomicInteger(0)
        var totalSleptMs = 0L

        val client = clientWithInterceptor {
            attempts.incrementAndGet()
            throw IOException("stream was reset: CANCEL")
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = client,
            url = "https://hp.ts.net/pc/api/watch/download/item5",
            userAgent = "PcControl-Test",
            videoFile = videoFile,
            initialTotalBytes = 5_000L,
            isCancelled = { false },
            isPaused = { false },
            sleepStepMs = { totalSleptMs += it },
        )

        assertTrue(result is WatchDownloadTransferResult.Failed)
        val failed = result as WatchDownloadTransferResult.Failed
        assertEquals("stream was reset: CANCEL", failed.errorMessage)
        assertEquals(WatchDownloadTransfer.MAX_TRANSIENT_RETRIES + 1, attempts.get())
        assertEquals(WatchDownloadTransfer.RETRY_BACKOFF_MS.sum(), totalSleptMs)
    }

    @Test
    fun `non-retryable 404 fails immediately without retrying`() {
        val videoFile = File(tempFolder.root, "missing.mp4")
        val attempts = AtomicInteger(0)

        val client = clientWithInterceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("not found".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = client,
            url = "https://hp.ts.net/pc/api/watch/download/missing",
            userAgent = "PcControl-Test",
            videoFile = videoFile,
            initialTotalBytes = 5_000L,
            isCancelled = { false },
            isPaused = { false },
            sleepStepMs = {},
        )

        assertEquals(1, attempts.get())
        assertEquals(
            WatchDownloadTransferResult.Failed(
                downloadedBytes = 0L,
                totalBytes = 5_000L,
                errorMessage = "Download failed (HTTP 404)",
            ),
            result,
        )
    }

    @Test
    fun `pausing during retry backoff exits cleanly as Paused without another attempt`() {
        val videoFile = File(tempFolder.root, "paused_backoff.mp4")
        val paused = AtomicBoolean(false)
        val attempts = AtomicInteger(0)

        val client = clientWithInterceptor {
            attempts.incrementAndGet()
            throw IOException("stream was reset: CANCEL")
        }

        val result = WatchDownloadTransfer.downloadWithResume(
            client = client,
            url = "https://hp.ts.net/pc/api/watch/download/pause_me",
            userAgent = "PcControl-Test",
            videoFile = videoFile,
            initialTotalBytes = 5_000L,
            isCancelled = { false },
            isPaused = { paused.get() },
            sleepStepMs = { paused.set(true) },
        )

        assertEquals(1, attempts.get())
        assertEquals(
            WatchDownloadTransferResult.Paused(downloadedBytes = 0L, totalBytes = 5_000L),
            result,
        )
    }
}
