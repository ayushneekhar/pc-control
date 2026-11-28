package com.neekhar.pccontrol.feature.seerr

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

@Serializable(with = SeerrStatusSerializer::class)
enum class SeerrStatus(val wire: String) {
    AVAILABLE("available"),
    PARTIAL("partial"),
    GRABBING("grabbing"),
    REQUESTED("requested"),
    PENDING("pending"),
    DECLINED("declined"),
    DELETED("deleted"),
    BLOCKLISTED("blocklisted"),
    FAILED("failed"),
    NONE("none");

    companion object {
        fun fromWire(value: String?): SeerrStatus {
            val normalized = value?.trim()?.lowercase() ?: return NONE
            return entries.firstOrNull { it.wire == normalized } ?: NONE
        }

        /**
         * Maps raw Seerr numeric media status codes (from Seerr v1 API) to [SeerrStatus].
         * 2 = Pending, 3 = Processing, 4 = Partially Available, 5 = Available,
         * 6 = Blocklisted, 7 = Deleted.
         */
        fun fromMediaCode(code: Int?, hasActiveDownload: Boolean = false): SeerrStatus {
            return when {
                code == 5 -> AVAILABLE
                code == 7 -> DELETED
                code == 6 -> BLOCKLISTED
                code == 4 -> PARTIAL
                hasActiveDownload -> GRABBING
                code == 2 || code == 3 -> REQUESTED
                else -> NONE
            }
        }
    }
}

object SeerrStatusSerializer : KSerializer<SeerrStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SeerrStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SeerrStatus) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): SeerrStatus {
        return SeerrStatus.fromWire(decoder.decodeString())
    }
}

enum class SeerrRequestFilter(val wire: String, val label: String) {
    ALL("all", "All"),
    PENDING("pending", "Pending"),
    PROCESSING("processing", "Processing"),
    AVAILABLE("available", "Available"),
    PARTIAL("partial", "Partial"),
    FAILED("failed", "Failed"),
    DELETED("deleted", "Deleted"),
    APPROVED("approved", "Approved"),
    UNAVAILABLE("unavailable", "Unavailable"),
    COMPLETED("completed", "Completed"),
    GRABBING("grabbing", "Grabbing"),
    REQUESTED("requested", "Requested"),
    DECLINED("declined", "Declined"),
    BLOCKLISTED("blocklisted", "Blocklisted");

    companion object {
        val BASE_TABS: List<SeerrRequestFilter> = listOf(
            ALL,
            PENDING,
            PROCESSING,
            AVAILABLE,
            PARTIAL,
            FAILED,
            DELETED,
        )

        fun fromWire(value: String?): SeerrRequestFilter {
            val normalized = value?.trim()?.lowercase() ?: return ALL
            return entries.firstOrNull { it.wire == normalized } ?: ALL
        }
    }
}

enum class SeerrSearchMediaFilter(val label: String) {
    ALL("All"),
    MOVIE("Movies"),
    TV("TV"),
    COLLECTION("Collections"),
}

enum class SeerrPersonMediaFilter(val label: String) {
    ALL("All"),
    MOVIE("Movies"),
    TV("TV"),
}

enum class SeerrPersonSortMode(val label: String) {
    POPULAR("Popular"),
    NEWEST("Newest"),
}

@Serializable
data class SeerrSeason(
    val seasonNumber: Int,
    val name: String = "",
    val episodeCount: Int = 0,
    val status: SeerrStatus = SeerrStatus.NONE,
    val airDate: String? = null,
    val poster: String? = null,
    val overview: String? = null,
)

@Serializable
data class SeerrCollectionRef(
    val id: Int,
    val name: String = "",
    val poster: String? = null,
)

@Serializable
data class SeerrTmdbRating(
    val score: Double = 0.0,
    val count: Int = 0,
)

@Serializable
data class SeerrRtRating(
    val criticsScore: Int? = null,
    val criticsRating: String? = null,
    val audienceScore: Int? = null,
    val audienceRating: String? = null,
    val url: String? = null,
)

@Serializable
data class SeerrImdbRating(
    val score: Double = 0.0,
    val count: Int? = null,
    val url: String? = null,
)

@Serializable
data class SeerrRatings(
    val tmdb: SeerrTmdbRating? = null,
    val rt: SeerrRtRating? = null,
    val imdb: SeerrImdbRating? = null,
)

@Serializable
data class SeerrCastMember(
    val id: Int,
    val name: String = "",
    val character: String = "",
    val profilePath: String? = null,
)

@Serializable
data class SeerrCrewMember(
    val id: Int,
    val name: String = "",
    val job: String = "",
    val department: String? = null,
    val profilePath: String? = null,
)

@Serializable
data class SeerrMediaRequestRef(
    val id: Int,
    val requestStatus: String = "unknown",
    val requestedBy: String = "",
    val createdAt: String = "",
    val seasons: List<Int> = emptyList(),
)

@Serializable
data class WatchMediaBadges(
    val resolution: String? = null,
    val videoCodec: String? = null,
    val hdr: String? = null,
    val audioCodec: String? = null,
    val sizeBytes: Long? = null,
    val sizeLabel: String? = null,
)

@Serializable
data class SeerrItem(
    val id: Int,
    val mediaType: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val poster: String? = null,
    val overview: String = "",
    val status: SeerrStatus = SeerrStatus.NONE,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    val backdrop: String? = null,
    val logoUrl: String? = null,
    val jellyfinItemId: String? = null,
    val played: Boolean = false,
    val mediaBadges: WatchMediaBadges? = null,
    val originalTitle: String? = null,
    val tagline: String? = null,
    val releaseDate: String? = null,
    val lastAirDate: String? = null,
    val runtime: Int? = null,
    val mediaStatus: String? = null,
    val genres: List<String> = emptyList(),
    val certification: String? = null,
    val spokenLanguages: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val budget: Long? = null,
    val revenue: Long? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    val imdbId: String? = null,
    val mediaUrl: String? = null,
    val ratings: SeerrRatings? = null,
    val cast: List<SeerrCastMember> = emptyList(),
    val crew: List<SeerrCrewMember> = emptyList(),
    val recommendations: List<SeerrItem> = emptyList(),
    val requests: List<SeerrMediaRequestRef> = emptyList(),
    val seasons: List<SeerrSeason> = emptyList(),
    val collection: SeerrCollectionRef? = null,
    val resumePositionTicks: Long = 0L,
    val resumeRuntimeTicks: Long = 0L,
    val resumeJellyfinItemId: String? = null,
    val resumeEpisodeLabel: String? = null,
    val resumeSeasonNumber: Int? = null,
    val resumeEpisodeNumber: Int? = null,
) {
    val isMovie: Boolean get() = mediaType == "movie"
    val isTv: Boolean get() = mediaType == "tv"
    val isCollection: Boolean get() = mediaType == "collection"
}

@Serializable
data class SeerrSearchResponse(
    val results: List<SeerrItem> = emptyList(),
)

@Serializable
data class SeerrPersonCredit(
    val id: Int,
    val mediaType: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val releaseDate: String? = null,
    val poster: String? = null,
    val character: String? = null,
    val job: String? = null,
    val role: String = "",
    val isCast: Boolean = false,
    val isCrew: Boolean = false,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    val popularity: Double = 0.0,
    val status: SeerrStatus = SeerrStatus.NONE,
    val episodeCount: Int? = null,
)

@Serializable
data class SeerrPerson(
    val id: Int,
    val name: String = "",
    val biography: String = "",
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    val knownForDepartment: String? = null,
    val profilePath: String? = null,
    val imdbId: String? = null,
    val alsoKnownAs: List<String> = emptyList(),
    val credits: List<SeerrPersonCredit> = emptyList(),
    val castCount: Int = 0,
    val crewCount: Int = 0,
)

@Serializable
data class SeerrCollection(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val parts: List<SeerrItem> = emptyList(),
)

@Serializable
data class SeerrRequestItem(
    val id: Int,
    val tmdbId: Int,
    val mediaType: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val poster: String? = null,
    val status: SeerrStatus = SeerrStatus.NONE,
    val requestStatus: String? = null,
    val seasons: List<Int> = emptyList(),
    val requestedBy: String = "",
    val createdAt: String = "",
)

@Serializable
data class SeerrRequestsResponse(
    val requests: List<SeerrRequestItem> = emptyList(),
    val filter: String? = null,
    val total: Int? = null,
    val counts: Map<String, Int>? = null,
)

@Serializable
data class SeerrCreateMovieRequestBody(
    val mediaType: String = "movie",
    val mediaId: Int,
)

@Serializable
data class SeerrCreateTvRequestBody(
    val mediaType: String = "tv",
    val mediaId: Int,
    val seasons: JsonElement,
)

@Serializable
data class SeerrCreateRequestResponse(
    val ok: Boolean = false,
    val id: Int? = null,
    val status: Int? = null,
)

@Serializable
data class SeerrResolveRequestBody(
    val url: String,
)

@Serializable
data class PlayOnTvRequestBody(
    val mediaUrl: String? = null,
    val tmdbId: Int? = null,
    val mediaType: String = "movie",
    val title: String? = null,
    val jellyfinItemId: String? = null,
    val startPositionTicks: Long? = null,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class PlayOnTvResponse(
    val ok: Boolean = false,
    val device: String? = null,
    val sessionId: String? = null,
    val action: String? = null,
    val itemId: String? = null,
    val code: String? = null,
    val error: String? = null,
)

// ---------------------------------------------------------------- Watch (Netflix-style hub)

enum class WatchTab(val id: String, val label: String) {
    ALL("all", "All"),
    LIBRARY("library", "In Library"),
    DOWNLOADS("downloads", "Downloads"),
    MOVIES("movies", "Movies"),
    TV("tv", "TV"),
    MY_LIST("watchlist", "My List"),
    REQUESTS("requests", "Requests"),
}

enum class WatchLibraryFilter(val mediaType: String?, val label: String) {
    ALL(null, "All"),
    MOVIES("movie", "Movies"),
    TV("tv", "TV"),
}

enum class WatchLibrarySort(val wire: String, val label: String) {
    RECENT("recent", "Recent"),
    TITLE("title", "Title"),
    RATING("rating", "Top rated"),
}

@Serializable
data class WatchItem(
    val id: Int = 0,
    val mediaType: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logoUrl: String? = null,
    val voteAverage: Double? = null,
    val status: SeerrStatus = SeerrStatus.NONE,
    val jellyfinItemId: String? = null,
    val played: Boolean = false,
    val inWatchlist: Boolean = false,
    val genres: List<String> = emptyList(),
    val positionTicks: Long = 0L,
    val runtimeTicks: Long = 0L,
    val resumeEpisodeLabel: String? = null,
    val resumeJellyfinItemId: String? = null,
) {
    val isMovie: Boolean get() = mediaType == "movie"
    val isTv: Boolean get() = mediaType == "tv"

    fun toSeedItem(resolveUrl: (String?) -> String? = { it }): SeerrItem = SeerrItem(
        id = id,
        mediaType = mediaType,
        title = title,
        year = year,
        poster = resolveUrl(poster),
        backdrop = resolveUrl(backdrop),
        logoUrl = resolveUrl(logoUrl),
        jellyfinItemId = jellyfinItemId,
        played = played,
        overview = overview,
        status = status,
        voteAverage = voteAverage,
        genres = genres,
    )
}

@Serializable
data class WatchContinueItem(
    val id: Int = 0,
    val mediaType: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val status: SeerrStatus = SeerrStatus.AVAILABLE,
    val jellyfinItemId: String = "",
    val seriesId: String? = null,
    val episodeLabel: String? = null,
    val episodeTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val progressPct: Double = 0.0,
    val positionTicks: Long = 0L,
    val runtimeTicks: Long = 0L,
    val remainingMinutes: Int? = null,
    val isNextUp: Boolean = false,
    val backdrop: String? = null,
    val poster: String? = null,
) {
    val isTv: Boolean get() = mediaType == "tv"

    fun toSeedItem(resolveUrl: (String?) -> String? = { it }): SeerrItem = SeerrItem(
        id = id,
        mediaType = mediaType,
        title = title,
        year = year,
        poster = resolveUrl(poster),
        backdrop = resolveUrl(backdrop),
        status = status,
    )
}

@Serializable
data class WatchDownloadItem(
    val tmdbId: Int = 0,
    val mediaType: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val percent: Double = 0.0,
    val status: String = "downloading",
    val eta: String? = null,
    val etaSeconds: Int? = null,
    val sizeLeft: Long = 0L,
    val size: Long = 0L,
    val episodeLabel: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val source: String = "",
) {
    fun toSeedItem(): SeerrItem = SeerrItem(
        id = tmdbId,
        mediaType = mediaType,
        title = title,
        year = year,
        poster = poster,
        backdrop = backdrop,
        status = SeerrStatus.GRABBING,
    )
}

@Serializable
data class WatchRail(
    val id: String = "",
    val title: String = "",
    val category: String = "all",
    val mediaType: String? = null,
    val genreId: Int? = null,
    val networkId: Int? = null,
    val items: List<WatchItem> = emptyList(),
)

@Serializable
data class WatchHomeResponse(
    val hero: WatchItem? = null,
    val heroItems: List<WatchItem> = emptyList(),
    val continueWatching: List<WatchContinueItem> = emptyList(),
    val recentlyAdded: List<WatchItem> = emptyList(),
    val downloads: List<WatchDownloadItem> = emptyList(),
    val rails: List<WatchRail> = emptyList(),
    val watchlistIds: List<String> = emptyList(),
    val libraryCount: Int = 0,
    val ts: Long = 0L,
    val degraded: List<String> = emptyList(),
) {
    val effectiveHeroItems: List<WatchItem>
        get() = heroItems.ifEmpty { listOfNotNull(hero) }
}

@Serializable
data class WatchGenre(
    val id: Int,
    val name: String = "",
)

@Serializable
data class WatchBoxSet(
    val id: String = "",
    val name: String = "",
    val overview: String = "",
    val itemCount: Int = 0,
    val poster: String? = null,
    val backdrop: String? = null,
    val logoUrl: String? = null,
)

@Serializable
data class WatchBoxSetsResponse(
    val collections: List<WatchBoxSet> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class WatchBoxSetDetailResponse(
    val collection: WatchBoxSet = WatchBoxSet(),
    val items: List<WatchItem> = emptyList(),
)

@Serializable
data class WatchWatchedRequestBody(
    val jellyfinItemId: String,
    val played: Boolean,
)

@Serializable
data class WatchWatchedResponse(
    val ok: Boolean = false,
    val jellyfinItemId: String = "",
    val played: Boolean = false,
)

@Serializable
data class WatchSurpriseResponse(
    val item: WatchItem? = null,
    val unwatchedCount: Int = 0,
    val totalMovies: Int = 0,
)

@Serializable
data class WatchDiscoverResponse(
    val category: String = "all",
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val items: List<WatchItem> = emptyList(),
    val genres: List<WatchGenre> = emptyList(),
    val collections: List<WatchBoxSet> = emptyList(),
)

@Serializable
data class WatchWatchlistRequestBody(
    val id: Int,
    val mediaType: String,
    val action: String = "toggle",
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val voteAverage: Double? = null,
)

@Serializable
data class WatchWatchlistResponse(
    val ok: Boolean = false,
    val inWatchlist: Boolean = false,
    val count: Int = 0,
    val ids: List<String> = emptyList(),
)

@Serializable
data class WatchEpisode(
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String = "",
    val overview: String = "",
    val airDate: String? = null,
    val still: String? = null,
    val runtimeMinutes: Int? = null,
    val voteAverage: Double? = null,
    val available: Boolean = false,
    val jellyfinItemId: String? = null,
    val played: Boolean = false,
    val progressPct: Double = 0.0,
    val positionTicks: Long = 0L,
    val runtimeTicks: Long = 0L,
)

@Serializable
data class WatchEpisodesResponse(
    val tmdbId: Int = 0,
    val seasonNumber: Int = 1,
    val name: String = "",
    val overview: String = "",
    val seriesJellyfinId: String? = null,
    val availableCount: Int = 0,
    val totalCount: Int = 0,
    val episodes: List<WatchEpisode> = emptyList(),
)

@Serializable
data class WatchDownloadsResponse(
    val items: List<WatchDownloadItem> = emptyList(),
    val ts: Long = 0L,
)
