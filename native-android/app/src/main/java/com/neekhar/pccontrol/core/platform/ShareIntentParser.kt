package com.neekhar.pccontrol.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder

/**
 * Parses incoming `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents (URLs, magnet links, YouTube
 * links, IMDb/TMDB/Letterboxd links, plain text, images, and files) into a normalized [SharePayload].
 *
 * Pure text/URI parsing functions avoid Android framework APIs so JVM unit tests can exercise all
 * routing and extraction cases directly.
 */
object ShareIntentParser {
  const val ACTION_SEND = "android.intent.action.SEND"
  const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
  private const val MAX_TEXT_CHARS = 4000
  private const val MAX_ATTACHMENTS = 10

  private val MAGNET_RE = Regex("\\bmagnet:\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
  private val MAGNET_DN_RE = Regex("[?&]dn=([^&]+)", RegexOption.IGNORE_CASE)
  private val MAGNET_BTIH_RE = Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
  private val YOUTUBE_RE = Regex(
    "https?://(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?[^\\s]*v=|live/|shorts/)|youtu\\.be/)[A-Za-z0-9_-]{11}[^\\s]*",
  )
  private val URL_RE = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
  private val TRAILING_PUNCT_RE = Regex("[).,;!?]+$")
  private val IMDB_PATH_RE = Regex("/title/tt\\d+", RegexOption.IGNORE_CASE)
  private val TMDB_PATH_RE = Regex("/(?:movie|tv)/\\d+", RegexOption.IGNORE_CASE)
  private val LETTERBOXD_FILM_RE = Regex("/film/[^/]+", RegexOption.IGNORE_CASE)
  private val BOXD_IT_RE = Regex("^/[A-Za-z0-9]+")

  /** Extracts the first YouTube URL inside shared text (e.g. `"Title https://youtu.be/..."`), or null. */
  fun extractYouTubeUrl(text: String): String? =
    YOUTUBE_RE.find(text)?.value?.replace(TRAILING_PUNCT_RE, "")

  internal fun magnetDisplayName(uri: String): String {
    val dn = MAGNET_DN_RE.find(uri)?.groupValues?.getOrNull(1)
    if (!dn.isNullOrEmpty()) {
      try {
        val decoded = URLDecoder.decode(dn.replace('+', ' '), Charsets.UTF_8.name()).trim()
        if (decoded.isNotEmpty()) return decoded
      } catch (_: Exception) {
      }
    }
    val hash = MAGNET_BTIH_RE.find(uri)?.groupValues?.getOrNull(1)
    return if (!hash.isNullOrEmpty()) "Torrent ${hash.take(12)}" else "Magnet link"
  }

  internal fun mediaSource(url: String): MediaLinkSource? {
    val parsed = try {
      URI(url)
    } catch (_: Exception) {
      return null
    }
    val rawHost = parsed.host?.lowercase() ?: return null
    val host = rawHost.removePrefix("www.").removePrefix("m.")
    val path = parsed.rawPath.orEmpty()
    return when {
      host == "imdb.com" && IMDB_PATH_RE.containsMatchIn(path) -> MediaLinkSource.IMDB
      host == "themoviedb.org" && TMDB_PATH_RE.containsMatchIn(path) -> MediaLinkSource.TMDB
      host == "letterboxd.com" && LETTERBOXD_FILM_RE.containsMatchIn(path) -> MediaLinkSource.LETTERBOXD
      host == "boxd.it" && BOXD_IT_RE.containsMatchIn(path) -> MediaLinkSource.LETTERBOXD
      else -> null
    }
  }

  /**
   * Matches only specialized media/download targets (`Magnet`, `YouTube`, `IMDb`/`TMDB`/`Letterboxd`),
   * mirroring `src/lib/share.ts` `parseSharedText()` verbatim. Returns null for generic URLs/text.
   */
  fun classifyKnownMediaText(text: String?): SharedTarget? {
    if (text.isNullOrBlank()) return null
    val trimmed = text.trim()

    val mag = MAGNET_RE.find(trimmed)
    if (mag != null) {
      val uri = mag.value
      return SharedTarget.Magnet(uri = uri, name = magnetDisplayName(uri))
    }

    val yt = extractYouTubeUrl(trimmed)
    if (yt != null) {
      return SharedTarget.YouTube(url = yt)
    }

    for (match in URL_RE.findAll(trimmed)) {
      val clean = match.value.replace(TRAILING_PUNCT_RE, "")
      val src = mediaSource(clean)
      if (src != null) {
        return SharedTarget.Media(url = clean, source = src)
      }
    }

    return null
  }

  /**
   * Classifies any non-blank shared text: specialized targets (`Magnet`, `YouTube`, `Media`) first,
   * then generic `WebUrl`, and finally `PlainText` (for sharing into Chat).
   */
  fun parseSharedText(text: String?): SharedTarget? {
    if (text.isNullOrBlank()) return null
    val trimmed = text.trim()

    classifyKnownMediaText(trimmed)?.let { return it }

    val firstUrl = URL_RE.find(trimmed)?.value?.replace(TRAILING_PUNCT_RE, "")
    if (firstUrl != null) {
      return SharedTarget.WebUrl(url = firstUrl)
    }

    return SharedTarget.PlainText(text = trimmed)
  }

  /** Pure Kotlin parser for share intent fields (tested directly on JVM). */
  fun parseRawShare(
    action: String?,
    mimeType: String?,
    text: String?,
    subject: String?,
    attachments: List<SharedAttachment>,
  ): SharePayload? {
    if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return null
    val cleanText = text?.trim()?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotEmpty() }
    val cleanSubject = subject?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
    val cleanAttachments = attachments
      .filter { it.uriString.isNotBlank() }
      .distinctBy { it.uriString }
      .take(MAX_ATTACHMENTS)

    val target = parseSharedText(cleanText)
    val payload = SharePayload(
      text = cleanText,
      subject = cleanSubject,
      target = target,
      attachments = cleanAttachments,
      mimeType = mimeType?.trim()?.takeIf { it.isNotEmpty() },
    )
    return if (payload.isEmpty) null else payload
  }

  /**
   * Parses an Android [Intent] (`ACTION_SEND` or `ACTION_SEND_MULTIPLE`) into a [SharePayload].
   * Pass [context] to resolve attachment MIME types, display names, and file sizes via `ContentResolver`.
   * Set [consume] = true to clear the intent's action & extras so Activity recreation doesn't replay it.
   */
  fun parseIntent(
    intent: Intent?,
    context: Context? = null,
    consume: Boolean = false,
  ): SharePayload? {
    if (intent == null) return null
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

    val mimeType = intent.type
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
    val uris = linkedSetOf<Uri>()

    when (action) {
      Intent.ACTION_SEND -> {
        getStreamUri(intent)?.let { uris.add(it) }
      }
      Intent.ACTION_SEND_MULTIPLE -> {
        getStreamUris(intent).forEach { uris.add(it) }
      }
    }

    val clip = intent.clipData
    if (clip != null) {
      for (i in 0 until clip.itemCount) {
        clip.getItemAt(i)?.uri?.let { uris.add(it) }
      }
    }

    val attachments = uris.take(MAX_ATTACHMENTS).map { uri ->
      resolveAttachment(uri, mimeType, context)
    }

    val payload = parseRawShare(
      action = action,
      mimeType = mimeType,
      text = text,
      subject = subject,
      attachments = attachments,
    )

    if (consume) {
      intent.action = null
      intent.removeExtra(Intent.EXTRA_TEXT)
      intent.removeExtra(Intent.EXTRA_SUBJECT)
      intent.removeExtra(Intent.EXTRA_STREAM)
      intent.clipData = null
    }

    return payload
  }

  private fun getStreamUri(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

  private fun getStreamUris(intent: Intent): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }

  private fun resolveAttachment(
    uri: Uri,
    fallbackMime: String?,
    context: Context?,
  ): SharedAttachment {
    var displayName: String? = null
    var sizeBytes: Long? = null
    var resolvedMime: String? = null

    if (context != null) {
      val resolver = context.contentResolver
      resolvedMime = try {
        resolver.getType(uri)?.trim()?.takeIf { it.isNotEmpty() }
      } catch (_: Exception) {
        null
      }
      try {
        resolver.query(
          uri,
          arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
              displayName = cursor.getString(nameIdx)?.trim()?.takeIf { it.isNotEmpty() }
            }
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
              val s = cursor.getLong(sizeIdx)
              if (s >= 0L) sizeBytes = s
            }
          }
        }
      } catch (_: Exception) {
      }
    }

    val finalMime = resolvedMime
      ?: fallbackMime?.trim()?.takeIf { it.isNotEmpty() && it != "*/*" }
      ?: "application/octet-stream"
    val finalName = displayName
      ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }

    return SharedAttachment(
      uriString = uri.toString(),
      mimeType = finalMime,
      displayName = finalName,
      sizeBytes = sizeBytes,
    )
  }
}

/**
 * In-memory holder for an unconsumed share payload captured by `MainActivity.onCreate` / `onNewIntent`.
 */
object PendingShareStore {
  private val _pending = MutableStateFlow<SharePayload?>(null)
  val pending: StateFlow<SharePayload?> = _pending.asStateFlow()

  fun capture(intent: Intent?, context: Context? = null): SharePayload? {
    val parsed = ShareIntentParser.parseIntent(intent, context = context, consume = true)
    if (parsed != null) {
      _pending.value = parsed
    }
    return parsed
  }

  fun consume(): SharePayload? {
    val current = _pending.value
    if (current != null) _pending.value = null
    return current
  }
}
