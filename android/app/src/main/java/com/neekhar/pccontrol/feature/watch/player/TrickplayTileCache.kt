package com.neekhar.pccontrol.feature.watch.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.neekhar.pccontrol.core.network.PcApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory-safe LRU cache of decoded Jellyfin Trickplay sprite sheets for 60fps scrub previews.
 *
 * Uses [Bitmap.Config.RGB_565] (half the RAM of ARGB_8888 with lossless fidelity for JPEG frames),
 * deduplicates in-flight HTTP fetches per tile index, and skips repeated retries for missing tiles.
 */
class TrickplayTileCache(
    private val httpClient: OkHttpClient?,
    maxTiles: Int = DEFAULT_MAX_TILES,
) {
    private val cache = object : LruCache<Int, Bitmap>(maxTiles.coerceAtLeast(1)) {}
    private val inFlight = ConcurrentHashMap<Int, CompletableDeferred<Bitmap?>>()
    private val failedTiles = ConcurrentHashMap.newKeySet<Int>()

    companion object {
        const val DEFAULT_MAX_TILES = 8
    }

    fun getCached(tileIndex: Int): Bitmap? {
        val bmp = cache.get(tileIndex)
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    suspend fun loadTile(tileIndex: Int, tileUrl: String): Bitmap? {
        if (tileIndex < 0 || tileUrl.isBlank()) return null
        getCached(tileIndex)?.let { return it }
        if (tileIndex in failedTiles) return null

        val existing = inFlight[tileIndex]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val raced = inFlight.putIfAbsent(tileIndex, deferred)
        if (raced != null) {
            return raced.await()
        }

        return try {
            val decoded = withContext(Dispatchers.IO) {
                fetchAndDecode(tileUrl)
            }
            if (decoded != null) {
                cache.put(tileIndex, decoded)
            } else {
                failedTiles.add(tileIndex)
            }
            deferred.complete(decoded)
            decoded
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (_: Throwable) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(tileIndex, deferred)
        }
    }

    private fun fetchAndDecode(tileUrl: String): Bitmap? {
        val client = httpClient ?: return null
        val req = Request.Builder()
            .url(tileUrl)
            .header("User-Agent", PcApiClient.USER_AGENT)
            .header("Accept", "image/jpeg, image/*")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        cache.evictAll()
        failedTiles.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }
}
