package com.neekhar.pccontrol.core.storage

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class CacheEnvelope(
    val v: Int = LocalCache.CACHE_VERSION,
    val ts: Long,
    val payload: String,
)

/**
 * Fast two-tier (in-memory + disk JSON) stale-while-revalidate cache rooted in `context.cacheDir`.
 * Mirrors `src/lib/cache.ts` with versioned envelopes and safe error isolation.
 */
class LocalCache(
    private val directory: File,
    @PublishedApi internal val json: Json = DefaultJson,
    @PublishedApi internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        json: Json = DefaultJson,
    ) : this(
        directory = File(context.applicationContext.cacheDir, CACHE_DIR_NAME),
        json = json,
    )

    private data class MemoryEntry(
        val ts: Long,
        val payload: String,
    )

    private val memory = ConcurrentHashMap<String, MemoryEntry>()

    init {
        runCatching {
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }

    /**
     * Synchronous lookup for instant stale-while-revalidate initial screen state.
     * Returns null on miss, expiry, version mismatch, or decode failure.
     */
    inline fun <reified T> get(key: String, maxAgeMs: Long? = null): T? {
        val raw = getRawPayload(key, maxAgeMs) ?: return null
        return runCatching { json.decodeFromString<T>(raw) }.getOrNull()
    }

    /** IO-dispatched lookup when reading larger blobs off the main thread. */
    suspend inline fun <reified T> getAsync(key: String, maxAgeMs: Long? = null): T? =
        withContext(ioDispatcher) {
            get<T>(key, maxAgeMs)
        }

    /** Updates memory immediately and persists atomically to disk. */
    inline fun <reified T> put(key: String, value: T) {
        runCatching {
            val encoded = json.encodeToString(value)
            putRawPayload(key, encoded)
        }
    }

    /** IO-dispatched write for larger payloads. */
    suspend inline fun <reified T> putAsync(key: String, value: T) =
        withContext(ioDispatcher) {
            put(key, value)
        }

    fun getString(key: String, maxAgeMs: Long? = null): String? = getRawPayload(key, maxAgeMs)

    fun putString(key: String, value: String) {
        putRawPayload(key, value)
    }

    fun getTimestamp(key: String): Long? {
        memory[key]?.let { return it.ts }
        val env = readEnvelopeFromDisk(key) ?: return null
        memory[key] = MemoryEntry(ts = env.ts, payload = env.payload)
        return env.ts
    }

    fun remove(key: String) {
        memory.remove(key)
        runCatching {
            fileForKey(key).delete()
        }
    }

    fun clear() {
        memory.clear()
        runCatching {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    /** Clears only the in-memory layer (primarily for testing disk persistence). */
    internal fun clearMemoryOnly() {
        memory.clear()
    }

    @PublishedApi
    internal fun getRawPayload(key: String, maxAgeMs: Long? = null): String? {
        val now = clockMs()
        memory[key]?.let { hit ->
            if (maxAgeMs != null && now - hit.ts > maxAgeMs) {
                remove(key)
                return null
            }
            return hit.payload
        }

        val env = readEnvelopeFromDisk(key) ?: return null
        if (maxAgeMs != null && now - env.ts > maxAgeMs) {
            remove(key)
            return null
        }
        memory[key] = MemoryEntry(ts = env.ts, payload = env.payload)
        return env.payload
    }

    @PublishedApi
    internal fun putRawPayload(key: String, payload: String) {
        val now = clockMs()
        memory[key] = MemoryEntry(ts = now, payload = payload)
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val target = fileForKey(key)
            val tmp = File(directory, "${target.name}.tmp")
            val content = json.encodeToString(CacheEnvelope(v = CACHE_VERSION, ts = now, payload = payload))
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun readEnvelopeFromDisk(key: String): CacheEnvelope? {
        val file = fileForKey(key)
        if (!file.isFile) return null
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            val env = json.decodeFromString<CacheEnvelope>(raw)
            if (env.v != CACHE_VERSION) {
                file.delete()
                null
            } else {
                env
            }
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    private fun fileForKey(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            for (b in digest) {
                val i = b.toInt() and 0xFF
                append(HEX_CHARS[i ushr 4])
                append(HEX_CHARS[i and 0x0F])
            }
        }
        return File(directory, "$hex.json")
    }

    companion object {
        const val CACHE_VERSION = 1
        private const val CACHE_DIR_NAME = "pc_local_cache"
        private const val HEX_CHARS = "0123456789abcdef"

        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
