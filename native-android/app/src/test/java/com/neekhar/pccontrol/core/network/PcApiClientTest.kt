package com.neekhar.pccontrol.core.network

import com.neekhar.pccontrol.core.storage.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcApiClientTest {

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
}
