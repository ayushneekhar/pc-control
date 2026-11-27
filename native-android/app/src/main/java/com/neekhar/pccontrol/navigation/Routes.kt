package com.neekhar.pccontrol.navigation

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder

/**
 * Type-safe Navigation Compose routes for all PC Control screens.
 */
@Serializable
sealed interface PcRoute {
    @Serializable
    data object Home : PcRoute

    @Serializable
    data class System(val initialTab: String? = null) : PcRoute

    @Serializable
    data object Vitals : PcRoute

    @Serializable
    data object Services : PcRoute

    @Serializable
    data class ServiceDetail(val unit: String) : PcRoute

    @Serializable
    data object Chat : PcRoute

    @Serializable
    data class Root(val requestId: String? = null) : PcRoute

    @Serializable
    data object Settings : PcRoute

    @Serializable
    data class Cams(
        val initialTab: String? = null,
        val sharedYoutubeUrl: String? = null,
        val replayCam: String? = null,
        val replayTsMs: Long? = null,
    ) : PcRoute

    @Serializable
    data object Seerr : PcRoute

    @Serializable
    data class SeerrDetail(
        val mediaType: String,
        val id: Int,
    ) : PcRoute

    @Serializable
    data class SeerrPerson(val id: Int) : PcRoute

    @Serializable
    data object Space : PcRoute

    @Serializable
    data object Downloads : PcRoute

    @Serializable
    data object AgentSettings : PcRoute

    @Serializable
    data class UsageAccountDetail(
        val accountId: String,
        val initialWindow: String? = null,
    ) : PcRoute

    @Serializable
    data object AgentView : PcRoute

    @Serializable
    data class AgentRule(
        val ruleId: String? = null,
        val url: String? = null,
    ) : PcRoute

    @Serializable
    data object ConnectionSettings : PcRoute
}

/**
 * Parsed result of an incoming deep link (`pccontrol://...` or `https://app.t3.codes/threads/...`).
 */
sealed interface DeepLinkTarget {
    data class Route(val route: PcRoute) : DeepLinkTarget
    data class T3Thread(
        val environmentId: String,
        val threadId: String,
        val originalUrl: String,
    ) : DeepLinkTarget
}

/**
 * Parsed result of an incoming Android `ACTION_SEND` (`text/plain`) share intent.
 * Mirrors `src/lib/share.ts`.
 */
sealed interface SharedContent {
    data class Youtube(val url: String) : SharedContent
    data class Magnet(val uri: String, val name: String) : SharedContent
    data class MediaUrl(val url: String, val source: MediaSource) : SharedContent
    data class Unsupported(val rawText: String) : SharedContent

    enum class MediaSource {
        Imdb,
        Tmdb,
        Letterboxd,
    }
}

/**
 * Pure JVM parser for deep links and `ACTION_SEND` text shares.
 */
object DeepLinkParser {
    private val MAGNET_REGEX = Regex("""\bmagnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val URL_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val YOUTUBE_REGEX = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?[^\s]*v=|live/|shorts/)|youtu\.be/)[A-Za-z0-9_-]{11}[^\s]*""",
    )
    private val MAGNET_DN_REGEX = Regex("""[?&]dn=([^&]+)""", RegexOption.IGNORE_CASE)
    private val MAGNET_HASH_REGEX = Regex("""xt=urn:btih:([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val IMDB_TITLE_REGEX = Regex("""/title/tt\d+""", RegexOption.IGNORE_CASE)
    private val TMDB_MEDIA_REGEX = Regex("""/(movie|tv)/\d+""", RegexOption.IGNORE_CASE)
    private val LETTERBOXD_FILM_REGEX = Regex("""/film/[^/]+""", RegexOption.IGNORE_CASE)
    private val BOXD_SHORT_REGEX = Regex("""^/[A-Za-z0-9]+""")

    fun parseUri(rawUri: String?): DeepLinkTarget? {
        val trimmed = rawUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null

        return when (scheme) {
            "pccontrol" -> parsePcControlUri(uri)?.let { DeepLinkTarget.Route(it) }
            "https", "http" -> parseHttpUri(uri, trimmed)
            else -> null
        }
    }

    private fun parseHttpUri(uri: URI, raw: String): DeepLinkTarget? {
        val host = uri.host?.lowercase() ?: return null
        if (host != "app.t3.codes") return null
        val segments = pathSegments(uri.rawPath)
        if (segments.size >= 3 && segments[0].equals("threads", ignoreCase = true)) {
            val envId = decodeSegment(segments[1])
            val threadId = decodeSegment(segments[2])
            if (envId.isNotEmpty() && threadId.isNotEmpty()) {
                return DeepLinkTarget.T3Thread(
                    environmentId = envId,
                    threadId = threadId,
                    originalUrl = raw,
                )
            }
        }
        return DeepLinkTarget.Route(PcRoute.Chat)
    }

    private fun parsePcControlUri(uri: URI): PcRoute? {
        val host = uri.host?.takeIf { it.isNotBlank() }
        val extraSegments = pathSegments(uri.rawPath)
        val segments = buildList {
            if (host != null) add(host)
            addAll(extraSegments)
        }.map { decodeSegment(it) }.filter { it.isNotEmpty() }

        if (segments.isEmpty()) return PcRoute.Home

        val query = parseQueryParams(uri.rawQuery)
        val first = segments[0].lowercase()

        return when (first) {
            "home", "routes" -> if (query["unlockKeyring"] == "1") PcRoute.Settings else PcRoute.Home
            "system" -> PcRoute.System(initialTab = segments.getOrNull(1)?.lowercase() ?: query["tab"])
            "vitals" -> PcRoute.System(initialTab = "vitals")
            "health", "services" -> PcRoute.System(initialTab = "services")
            "service" -> {
                val unit = segments.getOrNull(1) ?: query["unit"]
                if (!unit.isNullOrBlank()) PcRoute.ServiceDetail(unit = unit) else PcRoute.System(initialTab = "services")
            }
            "chat" -> PcRoute.Chat
            "root" -> PcRoute.Root(requestId = segments.getOrNull(1) ?: query["requestId"])
            "settings" -> PcRoute.Settings
            "cams" -> PcRoute.Cams(
                initialTab = segments.getOrNull(1)?.lowercase() ?: query["tab"],
                sharedYoutubeUrl = query["share"],
                replayCam = query["cam"],
                replayTsMs = query["ts"]?.toLongOrNull(),
            )
            "seerr" -> when {
                segments.size >= 3 && segments[1].equals("person", ignoreCase = true) -> {
                    val id = segments[2].toIntOrNull()
                    if (id != null) PcRoute.SeerrPerson(id = id) else PcRoute.Seerr
                }
                segments.size >= 3 -> {
                    val mediaType = segments[1].lowercase()
                    val id = segments[2].toIntOrNull()
                    if (id != null && mediaType in setOf("movie", "tv", "collection")) {
                        PcRoute.SeerrDetail(mediaType = mediaType, id = id)
                    } else {
                        PcRoute.Seerr
                    }
                }
                else -> PcRoute.Seerr
            }
            "space" -> PcRoute.Space
            "downloads" -> PcRoute.Downloads
            "agent-settings" -> PcRoute.AgentSettings
            "agent-usage" -> {
                val accountId = segments.getOrNull(1) ?: query["accountId"]
                if (!accountId.isNullOrBlank()) {
                    PcRoute.UsageAccountDetail(accountId = accountId, initialWindow = query["window"])
                } else {
                    PcRoute.AgentSettings
                }
            }
            "agent-view" -> PcRoute.AgentView
            "agent-rule" -> PcRoute.AgentRule(
                ruleId = segments.getOrNull(1) ?: query["ruleId"],
                url = query["url"],
            )
            "connection-settings", "connection" -> PcRoute.ConnectionSettings
            else -> null
        }
    }

    /**
     * Classifies plain text shared to PC Control via `ACTION_SEND`.
     */
    fun parseSharedText(text: String?): SharedContent? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        MAGNET_REGEX.find(trimmed)?.let { match ->
            val uri = match.value
            return SharedContent.Magnet(uri = uri, name = extractMagnetName(uri))
        }

        YOUTUBE_REGEX.find(trimmed)?.let { match ->
            return SharedContent.Youtube(url = match.value.trimEnd('.', ',', ')', ';', '!', '?'))
        }

        for (match in URL_REGEX.findAll(trimmed)) {
            val clean = match.value.trimEnd(')', '.', ',', ';', '!', '?')
            val source = classifyMediaSource(clean)
            if (source != null) {
                return SharedContent.MediaUrl(url = clean, source = source)
            }
        }

        return SharedContent.Unsupported(rawText = trimmed)
    }

    private fun extractMagnetName(uri: String): String {
        val dn = MAGNET_DN_REGEX.find(uri)?.groupValues?.getOrNull(1)
        if (!dn.isNullOrBlank()) {
            val decoded = runCatching { URLDecoder.decode(dn.replace("+", " "), "UTF-8").trim() }.getOrNull()
            if (!decoded.isNullOrEmpty()) return decoded
        }
        val hash = MAGNET_HASH_REGEX.find(uri)?.groupValues?.getOrNull(1)
        return if (!hash.isNullOrEmpty()) "Torrent ${hash.take(12)}" else "Magnet link"
    }

    private fun classifyMediaSource(url: String): SharedContent.MediaSource? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        val host = parsed.host
            ?.lowercase()
            ?.removePrefix("www.")
            ?.removePrefix("m.")
            ?: return null
        val path = parsed.rawPath.orEmpty()
        return when {
            host == "imdb.com" && IMDB_TITLE_REGEX.containsMatchIn(path) -> SharedContent.MediaSource.Imdb
            host == "themoviedb.org" && TMDB_MEDIA_REGEX.containsMatchIn(path) -> SharedContent.MediaSource.Tmdb
            host == "letterboxd.com" && LETTERBOXD_FILM_REGEX.containsMatchIn(path) -> SharedContent.MediaSource.Letterboxd
            host == "boxd.it" && BOXD_SHORT_REGEX.containsMatchIn(path) -> SharedContent.MediaSource.Letterboxd
            else -> null
        }
    }

    private fun pathSegments(rawPath: String?): List<String> =
        rawPath?.split("/")?.filter { it.isNotEmpty() } ?: emptyList()

    private fun decodeSegment(segment: String): String =
        runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        rawQuery.split("&").forEach { part ->
            if (part.isEmpty()) return@forEach
            val idx = part.indexOf('=')
            if (idx > 0) {
                val k = decodeSegment(part.substring(0, idx))
                val v = decodeSegment(part.substring(idx + 1))
                map[k] = v
            }
        }
        return map
    }
}
