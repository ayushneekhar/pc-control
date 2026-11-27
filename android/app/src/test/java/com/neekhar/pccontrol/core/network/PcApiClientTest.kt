package com.neekhar.pccontrol.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.feature.chat.ChatAgentSettingsPatch
import com.neekhar.pccontrol.feature.chat.GeminiSettingsPatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PcApiClientTest {

    private class FakePreferencesDataStore(
        private val initialGate: CompletableDeferred<Preferences>? = null,
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = flow {
            if (initialGate != null) {
                state.value = initialGate.await()
            }
            state.collect { emit(it) }
        }

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

    @Test
    fun normalizesServerAndChatUrls() {
        assertEquals(
            AppPrefs.DEFAULT_SERVER_URL,
            AppPrefs.normalizeServerUrl("   "),
        )
        assertEquals(
            "https://my-host.ts.net",
            AppPrefs.normalizeServerUrl(" https://my-host.ts.net/// "),
        )
        assertEquals(
            "https://agent.neekhar.dev",
            AppPrefs.normalizeChatUrl("https://agent.neekhar.dev/pc/api/chat/messages"),
        )
        assertEquals(
            "https://agent.neekhar.dev",
            AppPrefs.normalizeChatUrl("https://agent.neekhar.dev///"),
        )
        assertEquals("", AppPrefs.normalizeChatUrl("  "))
    }

    @Test
    fun resolvesChatTargetAndAttachmentAuthScoping() {
        val defaultTarget = PcApiClient.resolveChatTarget(
            defaultBaseUrl = "https://hp.ts.net/",
            customChatUrl = "",
            chatToken = "ignored-when-tailnet",
        )
        assertEquals("https://hp.ts.net", defaultTarget.baseUrl)
        assertTrue(defaultTarget.headers.isEmpty())
        assertFalse(defaultTarget.isCustom)

        val customTarget = PcApiClient.resolveChatTarget(
            defaultBaseUrl = "https://hp.ts.net",
            customChatUrl = "https://agent.neekhar.dev/pc/api/chat",
            chatToken = "secret-tok",
        )
        assertEquals("https://agent.neekhar.dev", customTarget.baseUrl)
        assertEquals(mapOf("Authorization" to "Bearer secret-tok"), customTarget.headers)
        assertTrue(customTarget.isCustom)

        // Relative attachment gets chat origin + auth header
        val (relUrl, relHeaders) = PcApiClient.resolveChatAttachmentSource(
            url = "/pc/api/chat/file/abc",
            target = customTarget,
        )
        assertEquals("https://agent.neekhar.dev/pc/api/chat/file/abc", relUrl)
        assertEquals(mapOf("Authorization" to "Bearer secret-tok"), relHeaders)

        // Third-party absolute URL does NOT receive bearer token
        val (extUrl, extHeaders) = PcApiClient.resolveChatAttachmentSource(
            url = "https://cdn.example.com/pic.png",
            target = customTarget,
        )
        assertEquals("https://cdn.example.com/pic.png", extUrl)
        assertTrue(extHeaders.isEmpty())
    }

    @Test
    fun apiResultHelpersWorkAsExpected() {
        val ok: ApiResult<Int> = ApiResult.Success(21, statusCode = 200)
        assertTrue(ok.isSuccess)
        assertFalse(ok.isError)
        assertEquals(42, ok.map { it * 2 }.getOrNull())
        assertNull(ok.errorOrNull())

        val err: ApiResult<Int> = ApiResult.Error(statusCode = 502, message = "Bad Gateway")
        assertTrue(err.isError)
        assertNull(err.getOrNull())
        assertEquals(502, err.errorOrNull()?.statusCode)
    }

    @Test
    fun geminiSettingsPatchOmitsNullFieldsWhenSerialized() {
        val patch = GeminiSettingsPatch(model = "gemini-4-carbon")
        val raw = PcApiClient.DefaultJson.encodeToString(patch)
        val obj = PcApiClient.DefaultJson.parseToJsonElement(raw).jsonObject

        assertEquals("gemini-4-carbon", obj["model"]?.jsonPrimitive?.content)
        assertFalse("instance_id must be omitted when null", obj.containsKey("instance_id"))
        assertFalse("thought_level must be omitted when null", obj.containsKey("thought_level"))

        val wrapped = ChatAgentSettingsPatch(gemini = patch)
        val wrappedRaw = PcApiClient.DefaultJson.encodeToString(wrapped)
        val wrappedObj: JsonObject = PcApiClient.DefaultJson.parseToJsonElement(wrappedRaw).jsonObject

        assertFalse("claude must be omitted when null", wrappedObj.containsKey("claude"))
        val geminiObj = wrappedObj["gemini"]!!.jsonObject
        assertEquals("gemini-4-carbon", geminiObj["model"]?.jsonPrimitive?.content)
        assertFalse(geminiObj.containsKey("instance_id"))
        assertFalse(geminiObj.containsKey("thought_level"))
    }

    @Test
    fun executeRaw_awaitsAppPrefsBeforeFiringRequest() = runBlocking {
        val gate = CompletableDeferred<Preferences>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(initialGate = gate),
                scope = scope,
            )
            val seenUrl = AtomicReference<String?>(null)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seenUrl.set(chain.request().url.toString())
                    jsonResponse(chain, 200, """{"ok":true}""")
                }
                .build()
            val client = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)

            val req = async { client.executeRaw("GET", "/pc/api/info") }
            delay(50L)
            assertNull("Request must not fire before AppPrefs finishes loading", seenUrl.get())

            gate.complete(
                preferencesOf(
                    stringPreferencesKey("server_url") to "https://loaded-host.ts.net",
                ),
            )

            val res = withTimeout(5_000L) { req.await() }
            assertTrue(res is ApiResult.Success)
            assertEquals("https://loaded-host.ts.net/pc/api/info", seenUrl.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun getJson_retriesOnceOnTransientIoExceptionAndSucceeds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(stringPreferencesKey("server_url") to "https://host.ts.net"),
                ),
                scope = scope,
            )
            val attempts = AtomicInteger(0)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    if (attempts.incrementAndGet() == 1) {
                        throw IOException("stale keep-alive socket")
                    }
                    jsonResponse(chain, 200, """{"ok":true}""")
                }
                .build()
            val client = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)

            val res = withTimeout(5_000L) { client.getJson<Map<String, Boolean>>("/pc/api/info") }
            assertTrue(res is ApiResult.Success)
            assertEquals(true, (res as ApiResult.Success).data["ok"])
            assertEquals(2, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun getJson_retriesOnceOnTransient503AndSucceeds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(stringPreferencesKey("server_url") to "https://host.ts.net"),
                ),
                scope = scope,
            )
            val attempts = AtomicInteger(0)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    if (attempts.incrementAndGet() == 1) {
                        jsonResponse(chain, 503, """{"error":"upstream warming up"}""")
                    } else {
                        jsonResponse(chain, 200, """{"ok":true}""")
                    }
                }
                .build()
            val client = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)

            val res = withTimeout(5_000L) { client.getJson<Map<String, Boolean>>("/pc/api/seerr/requests") }
            assertTrue(res is ApiResult.Success)
            assertEquals(2, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun getJson_doesNotRetryOnClient404() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(stringPreferencesKey("server_url") to "https://host.ts.net"),
                ),
                scope = scope,
            )
            val attempts = AtomicInteger(0)
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    attempts.incrementAndGet()
                    jsonResponse(chain, 404, """{"error":"not found"}""")
                }
                .build()
            val client = PcApiClient(prefs = prefs, okHttpClient = okHttp, getRetryBackoffMs = 0L)

            val res = withTimeout(5_000L) { client.getJson<Map<String, Boolean>>("/pc/api/missing") }
            assertTrue(res is ApiResult.Error)
            assertEquals(404, (res as ApiResult.Error).statusCode)
            assertEquals(1, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun postJson_doesNotAutoRetryOnTransientErrors() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prefs = AppPrefs(
                dataStore = FakePreferencesDataStore(
                    initial = preferencesOf(stringPreferencesKey("server_url") to "https://host.ts.net"),
                ),
                scope = scope,
            )

            val ioAttempts = AtomicInteger(0)
            val ioClient = PcApiClient(
                prefs = prefs,
                okHttpClient = OkHttpClient.Builder()
                    .addInterceptor {
                        ioAttempts.incrementAndGet()
                        throw IOException("connection reset")
                    }
                    .build(),
                getRetryBackoffMs = 0L,
            )
            val ioRes = withTimeout(5_000L) { ioClient.postJson<Unit>("/pc/api/vpn", """{"enabled":true}""") }
            assertTrue(ioRes is ApiResult.Error)
            assertEquals(0, (ioRes as ApiResult.Error).statusCode)
            assertEquals(1, ioAttempts.get())

            val gatewayAttempts = AtomicInteger(0)
            val gatewayClient = PcApiClient(
                prefs = prefs,
                okHttpClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        gatewayAttempts.incrementAndGet()
                        jsonResponse(chain, 503, """{"error":"busy"}""")
                    }
                    .build(),
                getRetryBackoffMs = 0L,
            )
            val gwRes = withTimeout(5_000L) { gatewayClient.postJson<Unit>("/pc/api/vpn", """{"enabled":true}""") }
            assertTrue(gwRes is ApiResult.Error)
            assertEquals(503, (gwRes as ApiResult.Error).statusCode)
            assertEquals(1, gatewayAttempts.get())
        } finally {
            scope.cancel()
        }
    }
}
