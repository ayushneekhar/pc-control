package com.neekhar.pccontrol.core.storage

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Serializable
    private data class SamplePayload(val host: String, val cpu: Int)

    @Test
    fun persistsAndReadsAcrossMemoryClears() {
        var now = 1_000L
        val cache = LocalCache(
            directory = tempFolder.newFolder("cache"),
            clockMs = { now },
        )

        val payload = SamplePayload(host = "hp-pavilion", cpu = 17)
        cache.put("vitals:latest", payload)
        assertEquals(payload, cache.get<SamplePayload>("vitals:latest"))

        // Drop in-memory layer and verify disk read
        cache.clearMemoryOnly()
        assertEquals(payload, cache.get<SamplePayload>("vitals:latest"))
        assertEquals(1_000L, cache.getTimestamp("vitals:latest"))

        // Advance time past maxAgeMs -> expired and evicted
        now = 10_000L
        assertNull(cache.get<SamplePayload>("vitals:latest", maxAgeMs = 5_000L))
        assertNull(cache.get<SamplePayload>("vitals:latest"))
    }

    @Test
    fun supportsAsyncReadWriteAndClear() = runTest {
        val cache = LocalCache(directory = tempFolder.newFolder("cache-async"))
        val payload = SamplePayload(host = "laptop", cpu = 42)

        cache.putAsync("home:state", payload)
        assertEquals(payload, cache.getAsync<SamplePayload>("home:state"))

        cache.remove("home:state")
        assertNull(cache.getAsync<SamplePayload>("home:state"))

        cache.putString("k1", "v1")
        cache.putString("k2", "v2")
        cache.clear()
        assertNull(cache.getString("k1"))
        assertNull(cache.getString("k2"))
    }
}
