package com.neekhar.pccontrol.core.platform

enum class MediaLinkSource {
  IMDB,
  TMDB,
  LETTERBOXD,
}

enum class ShareDestination {
  /** YouTube URL to play on laptop speakers / media player. */
  YOUTUBE,

  /** Magnet URI to queue in Downloads / qBittorrent. */
  MAGNET,

  /** IMDb / TMDB / Letterboxd link to search/request in Seerr. */
  MEDIA_REQUEST,

  /** Arbitrary text, web links, images, or files to attach in Chat. */
  CHAT,
}

sealed interface SharedTarget {
  data class YouTube(val url: String) : SharedTarget
  data class Magnet(val uri: String, val name: String) : SharedTarget
  data class Media(val url: String, val source: MediaLinkSource) : SharedTarget
  data class WebUrl(val url: String) : SharedTarget
  data class PlainText(val text: String) : SharedTarget
}

data class SharedAttachment(
  val uriString: String,
  val mimeType: String,
  val displayName: String? = null,
  val sizeBytes: Long? = null,
) {
  val isImage: Boolean
    get() = mimeType.startsWith("image/", ignoreCase = true)
}

/**
 * Normalized representation of an incoming Android `ACTION_SEND` or `ACTION_SEND_MULTIPLE` intent,
 * ready to be routed to Chat, Seerr, Downloads, or YouTube playback.
 */
data class SharePayload(
  val text: String? = null,
  val subject: String? = null,
  val target: SharedTarget? = null,
  val attachments: List<SharedAttachment> = emptyList(),
  val mimeType: String? = null,
) {
  val isEmpty: Boolean
    get() = text.isNullOrBlank() && target == null && attachments.isEmpty()

  val hasImages: Boolean
    get() = attachments.any { it.isImage }

  val hasFiles: Boolean
    get() = attachments.any { !it.isImage }

  val suggestedDestination: ShareDestination
    get() = when {
      attachments.isNotEmpty() -> ShareDestination.CHAT
      target is SharedTarget.YouTube -> ShareDestination.YOUTUBE
      target is SharedTarget.Magnet -> ShareDestination.MAGNET
      target is SharedTarget.Media -> ShareDestination.MEDIA_REQUEST
      else -> ShareDestination.CHAT
    }
}
