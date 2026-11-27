package com.neekhar.pccontrol.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentParserTest {

  @Test
  fun classifyKnownMediaText_detectsMagnetLinksAndDisplayName() {
    val uri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Ubuntu+24.04+ISO"
    val res = ShareIntentParser.classifyKnownMediaText(uri)
    assertEquals(SharedTarget.Magnet(uri = uri, name = "Ubuntu 24.04 ISO"), res)

    val noDn = "magnet:?xt=urn:btih:ABCDEF1234567890"
    assertEquals(
      SharedTarget.Magnet(uri = noDn, name = "Torrent ABCDEF123456"),
      ShareIntentParser.classifyKnownMediaText(noDn),
    )

    val minimal = "magnet:?tr=udp://tracker.example.com:80"
    assertEquals(
      SharedTarget.Magnet(uri = minimal, name = "Magnet link"),
      ShareIntentParser.classifyKnownMediaText(minimal),
    )
  }

  @Test
  fun classifyKnownMediaText_detectsYouTubeLinks() {
    val short = ShareIntentParser.classifyKnownMediaText("Check this out https://youtu.be/dQw4w9WgXcQ")
    assertEquals(SharedTarget.YouTube("https://youtu.be/dQw4w9WgXcQ"), short)

    val watch = ShareIntentParser.classifyKnownMediaText(
      "Song Title https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD123",
    )
    assertEquals(
      SharedTarget.YouTube("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD123"),
      watch,
    )

    val shorts = ShareIntentParser.classifyKnownMediaText("https://m.youtube.com/shorts/dQw4w9WgXcQ.")
    assertEquals(SharedTarget.YouTube("https://m.youtube.com/shorts/dQw4w9WgXcQ"), shorts)
  }

  @Test
  fun classifyKnownMediaText_detectsImdbTmdbAndLetterboxdLinks() {
    assertEquals(
      SharedTarget.Media("https://www.imdb.com/title/tt1375666/", MediaLinkSource.IMDB),
      ShareIntentParser.classifyKnownMediaText("https://www.imdb.com/title/tt1375666/"),
    )
    assertEquals(
      SharedTarget.Media("https://www.themoviedb.org/tv/1396-breaking-bad", MediaLinkSource.TMDB),
      ShareIntentParser.classifyKnownMediaText("Watch https://www.themoviedb.org/tv/1396-breaking-bad."),
    )
    assertEquals(
      SharedTarget.Media("https://letterboxd.com/film/inception/", MediaLinkSource.LETTERBOXD),
      ShareIntentParser.classifyKnownMediaText("https://letterboxd.com/film/inception/"),
    )
    assertEquals(
      SharedTarget.Media("https://boxd.it/1c2d", MediaLinkSource.LETTERBOXD),
      ShareIntentParser.classifyKnownMediaText("https://boxd.it/1c2d"),
    )
  }

  @Test
  fun classifyKnownMediaText_ignoresUnsupportedDomainsAndPlainText() {
    assertNull(ShareIntentParser.classifyKnownMediaText("hello world"))
    assertNull(ShareIntentParser.classifyKnownMediaText("https://example.com/film/test"))
    assertNull(ShareIntentParser.classifyKnownMediaText("https://notimdb.com/title/tt1234567"))
    assertNull(ShareIntentParser.classifyKnownMediaText("   "))
    assertNull(ShareIntentParser.classifyKnownMediaText(null))
  }

  @Test
  fun parseSharedText_fallsBackToWebUrlAndPlainTextForChat() {
    assertEquals(
      SharedTarget.WebUrl("https://example.com/article"),
      ShareIntentParser.parseSharedText("Read this: https://example.com/article!"),
    )
    assertEquals(
      SharedTarget.PlainText("Summarize this meeting note"),
      ShareIntentParser.parseSharedText("  Summarize this meeting note  "),
    )
  }

  @Test
  fun parseRawShare_handlesSingleTextAndRoutesDestination() {
    val ytPayload = ShareIntentParser.parseRawShare(
      action = ShareIntentParser.ACTION_SEND,
      mimeType = "text/plain",
      text = "Cool video https://youtu.be/dQw4w9WgXcQ",
      subject = "Cool video",
      attachments = emptyList(),
    )
    assertNotNull(ytPayload)
    assertEquals(ShareDestination.YOUTUBE, ytPayload!!.suggestedDestination)
    assertEquals("Cool video", ytPayload.subject)

    val mediaPayload = ShareIntentParser.parseRawShare(
      action = ShareIntentParser.ACTION_SEND,
      mimeType = "text/plain",
      text = "https://www.imdb.com/title/tt1375666/",
      subject = null,
      attachments = emptyList(),
    )
    assertEquals(ShareDestination.MEDIA_REQUEST, mediaPayload!!.suggestedDestination)

    val magnetPayload = ShareIntentParser.parseRawShare(
      action = ShareIntentParser.ACTION_SEND,
      mimeType = "text/plain",
      text = "magnet:?xt=urn:btih:0123456789abcdef&dn=Linux",
      subject = null,
      attachments = emptyList(),
    )
    assertEquals(ShareDestination.MAGNET, magnetPayload!!.suggestedDestination)
  }

  @Test
  fun parseRawShare_handlesSingleAndMultipleAttachmentsForChat() {
    val img1 = SharedAttachment("content://media/1", "image/jpeg", "photo1.jpg", 12345L)
    val img2 = SharedAttachment("content://media/2", "image/png", "photo2.png", 67890L)
    val pdf = SharedAttachment("content://docs/3", "application/pdf", "notes.pdf", 99999L)

    val multi = ShareIntentParser.parseRawShare(
      action = ShareIntentParser.ACTION_SEND_MULTIPLE,
      mimeType = "*/*",
      text = "Check these files",
      subject = null,
      attachments = listOf(img1, img1, img2, pdf), // duplicate img1 should be deduplicated
    )
    assertNotNull(multi)
    assertEquals(3, multi!!.attachments.size)
    assertTrue(multi.hasImages)
    assertTrue(multi.hasFiles)
    assertEquals(ShareDestination.CHAT, multi.suggestedDestination)

    val imageOnly = ShareIntentParser.parseRawShare(
      action = ShareIntentParser.ACTION_SEND,
      mimeType = "image/jpeg",
      text = null,
      subject = null,
      attachments = listOf(img1),
    )
    assertNotNull(imageOnly)
    assertTrue(imageOnly!!.hasImages)
    assertFalse(imageOnly.hasFiles)
    assertEquals(ShareDestination.CHAT, imageOnly.suggestedDestination)
  }

  @Test
  fun parseRawShare_returnsNullForUnsupportedActionOrEmptyPayload() {
    assertNull(
      ShareIntentParser.parseRawShare(
        action = "android.intent.action.VIEW",
        mimeType = "text/plain",
        text = "https://youtu.be/dQw4w9WgXcQ",
        subject = null,
        attachments = emptyList(),
      ),
    )
    assertNull(
      ShareIntentParser.parseRawShare(
        action = ShareIntentParser.ACTION_SEND,
        mimeType = "text/plain",
        text = "   ",
        subject = null,
        attachments = emptyList(),
      ),
    )
  }
}
