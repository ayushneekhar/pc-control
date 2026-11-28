package com.neekhar.pccontrol.feature.watch

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.neekhar.pccontrol.core.network.ApiResult
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.feature.watch.player.WatchPlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WatchPlayerRepositoryTest {

    private class FakePreferencesDataStore(
        initial: Preferences = preferencesOf(
            stringPreferencesKey("server_url") to "https://test-host.ts.net",
        ),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private fun jsonResponse(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun playbackInfoJson(
        jellyfinItemId: String,
        title: String,
        playSessionId: String = "sess-1",
    ): String = """
        {
          "jellyfinItemId": "$jellyfinItemId",
          "mediaSourceId": "$jellyfinItemId",
          "playSessionId": "$playSessionId",
          "title": "$title",
          "mediaType": "movie",
          "directStreamUrl": "/pc/api/watch/stream/$jellyfinItemId/direct"
        }
    """.trimIndent()

    @Test
    fun `prefetchPlaybackInfo warms cache for instant getPlaybackInfo hit`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val networkCalls = AtomicInteger(0)
            val nowMs = AtomicLong(1_000L)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val n = networkCalls.incrementAndGet()
                    jsonResponse(chain, 200, playbackInfoJson("jf-123", "Dune #$n", "sess-$n"))
                }
                .build()
            val prefs = AppPrefs(dataStore = FakePreferencesDataStore(), scope = scope)
            val api = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)
            val repo = WatchPlayerRepository(api = api, cacheTtlMs = 20_000L, clockMs = { nowMs.get() })

            // Prefetch by TMDB id
            repo.prefetchPlaybackInfo(tmdbId = 438631, mediaType = "movie")
            assertEquals(1, networkCalls.get())

            // Subsequent getPlaybackInfo with same TMDB id returns cached response with 0 network calls
            nowMs.addAndGet(5_000L)
            val hitByTmdb = repo.getPlaybackInfo(tmdbId = 438631, mediaType = "movie")
            assertTrue(hitByTmdb is ApiResult.Success)
            assertEquals("Dune #1", (hitByTmdb as ApiResult.Success).data.title)
            assertEquals(1, networkCalls.get())

            // Also hits by resolved jellyfinItemId
            val hitByJf = repo.getPlaybackInfo(jellyfinItemId = "jf-123")
            assertTrue(hitByJf is ApiResult.Success)
            assertEquals("Dune #1", (hitByJf as ApiResult.Success).data.title)
            assertEquals(1, networkCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `getPlaybackInfo refreshes after cache TTL expires`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val networkCalls = AtomicInteger(0)
            val nowMs = AtomicLong(10_000L)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val n = networkCalls.incrementAndGet()
                    jsonResponse(chain, 200, playbackInfoJson("jf-ttl", "Movie v$n", "sess-$n"))
                }
                .build()
            val prefs = AppPrefs(dataStore = FakePreferencesDataStore(), scope = scope)
            val api = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)
            val repo = WatchPlayerRepository(api = api, cacheTtlMs = 20_000L, clockMs = { nowMs.get() })

            repo.prefetchPlaybackInfo(jellyfinItemId = "jf-ttl")
            assertEquals(1, networkCalls.get())

            // Still fresh at +20_000ms boundary
            nowMs.addAndGet(20_000L)
            val boundaryHit = repo.getPlaybackInfo(jellyfinItemId = "jf-ttl")
            assertEquals("Movie v1", (boundaryHit as ApiResult.Success).data.title)
            assertEquals(1, networkCalls.get())

            // Expired at +20_001ms -> fetches fresh entry
            nowMs.addAndGet(1L)
            val refreshed = repo.getPlaybackInfo(jellyfinItemId = "jf-ttl")
            assertEquals("Movie v2", (refreshed as ApiResult.Success).data.title)
            assertEquals(2, networkCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `getPlaybackInfo bypasses cache on explicit maxBitrate or audioStreamIndex or useCache false`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val networkCalls = AtomicInteger(0)
            val nowMs = AtomicLong(1_000L)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val n = networkCalls.incrementAndGet()
                    jsonResponse(chain, 200, playbackInfoJson("jf-override", "Call #$n", "sess-$n"))
                }
                .build()
            val prefs = AppPrefs(dataStore = FakePreferencesDataStore(), scope = scope)
            val api = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)
            val repo = WatchPlayerRepository(api = api, cacheTtlMs = 20_000L, clockMs = { nowMs.get() })

            // Warm default cache entry
            repo.prefetchPlaybackInfo(jellyfinItemId = "jf-override")
            assertEquals(1, networkCalls.get())

            // Explicit maxBitrate bypasses cache and does not clobber default cached entry
            val bitrateRes = repo.getPlaybackInfo(jellyfinItemId = "jf-override", maxBitrate = 8_000_000L)
            assertEquals("Call #2", (bitrateRes as ApiResult.Success).data.title)
            assertEquals(2, networkCalls.get())

            // Explicit audioStreamIndex bypasses cache and does not clobber default cached entry
            val audioRes = repo.getPlaybackInfo(jellyfinItemId = "jf-override", audioStreamIndex = 2)
            assertEquals("Call #3", (audioRes as ApiResult.Success).data.title)
            assertEquals(3, networkCalls.get())

            // Default call still gets original cached entry
            val defaultHit = repo.getPlaybackInfo(jellyfinItemId = "jf-override")
            assertEquals("Call #1", (defaultHit as ApiResult.Success).data.title)
            assertEquals(3, networkCalls.get())

            // useCache = false forces a fresh network fetch and updates cache
            val forced = repo.getPlaybackInfo(jellyfinItemId = "jf-override", useCache = false)
            assertEquals("Call #4", (forced as ApiResult.Success).data.title)
            assertEquals(4, networkCalls.get())

            val afterForced = repo.getPlaybackInfo(jellyfinItemId = "jf-override")
            assertEquals("Call #4", (afterForced as ApiResult.Success).data.title)
            assertEquals(4, networkCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `prefetchPlaybackInfo swallows network errors silently`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { throw IOException("Tailscale offline") }
                .build()
            val prefs = AppPrefs(dataStore = FakePreferencesDataStore(), scope = scope)
            val api = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)
            val repo = WatchPlayerRepository(api = api)

            // Must not throw
            repo.prefetchPlaybackInfo(jellyfinItemId = "jf-offline")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `mediaStreamingClient shares connectionPool and dispatcher with PcApiClient`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(dataStore = FakePreferencesDataStore(), scope = scope)
            val api = PcApiClient(prefs = prefs)
            val streaming = api.mediaStreamingClient()

            assertSame(api.okHttpClient.connectionPool, streaming.connectionPool)
            assertSame(api.okHttpClient.dispatcher, streaming.dispatcher)
            assertEquals(0, streaming.callTimeoutMillis)
            assertEquals(15_000, streaming.connectTimeoutMillis)
            assertEquals(30_000, streaming.readTimeoutMillis)
            assertTrue(streaming.followRedirects)
            assertTrue(streaming.followSslRedirects)
        } finally {
            scope.cancel()
        }
    }
}
