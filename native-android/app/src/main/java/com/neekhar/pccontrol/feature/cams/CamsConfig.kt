package com.neekhar.pccontrol.feature.cams

/**
 * Camera definitions and go2rtc / camrec stream identifiers.
 * Mirrors `src/lib/cams/config.ts`.
 */
enum class CamId(val id: String, val displayName: String, val shortName: String) {
    CAT("cat", "Living room", "Living"),
    CAT2("cat2", "Hall", "Hall"),
    CAT3("cat3", "Food bowl", "Bowl");

    companion object {
        fun fromId(id: String?): CamId? = entries.firstOrNull { it.id == id }
    }
}

enum class CamsTab(val label: String) {
    LIVE("Live"),
    REPLAY("Replay"),
    DETECTIONS("Detections"),
    MUSIC("Music"),
}

object CamsConfig {
    val CAMS: List<CamId> = listOf(CamId.CAT, CamId.CAT2, CamId.CAT3)

    val NAMES: Map<String, String> = mapOf(
        "cat" to "Living room",
        "cat2" to "Hall",
        "cat3" to "Food bowl",
        "cat3hd" to "Food bowl",
    )

    /** CP Plus: H.265 2304x1296 passthrough. Played only when hardware HEVC is supported over WebRTC. */
    const val HD_STREAM = "cat3hd"

    /** CP Plus mic (PCMA). Same source for HD and SD, so Listen survives HD/SD switches. */
    const val CAM_MIC_STREAM = "cat3hd"

    /** Laptop mic (Opus). */
    const val LAPTOP_MIC_STREAM = "mic"

    /** Phone mic -> laptop speakers (go2rtc backchannel). */
    const val TALK_STREAM = "talk"

    /** No HD frame within this window falls back to SD (first HD frame waits for a keyframe). */
    const val HD_WAIT_MS = 12_000L

    /** Web page path for fallback viewing. */
    const val WEB_PATH = "/cams/"

    fun camName(cam: String): String = NAMES[cam] ?: cam

    private val YOUTUBE_URL_REGEX = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?[^\s]*v=|live/|shorts/)|youtu\.be/)[A-Za-z0-9_-]{11}[^\s]*""",
    )

    /** Extract the YouTube URL from shared text (e.g. "Title https://youtu.be/..."), or null. */
    fun extractYoutubeLink(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return YOUTUBE_URL_REGEX.find(text)?.value
    }
}
