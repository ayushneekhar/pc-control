package com.neekhar.pccontrol.core.platform

/**
 * Converts Markdown-formatted assistant replies into clean, human-readable plain text
 * suitable for Android push notifications (which cannot render Markdown), long-press
 * message preview cards, and reply quote bars.
 */
object MarkdownStripper {

    private val fenceOpenRe = Regex("""^[ \t]*(`{3,}|~{3,})([^\n]*)$""")
    private val hrRe = Regex("""^[ \t]*([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val blockquotePrefixRe = Regex("""^[ \t]*(?:>[ \t]?)+""")
    private val headingPrefixRe = Regex("""^[ \t]*#{1,6}[ \t]+""")
    private val headingTrailingRe = Regex("""[ \t]+#+[ \t]*$""")
    private val bulletPrefixRe = Regex("""^([ \t]*)[-*+][ \t]+""")
    private val tableSepRe = Regex("""^[ \t]*\|?(?:[ \t]*:?-{2,}:?[ \t]*\|)+[ \t]*:?-{2,}:?[ \t]*\|?[ \t]*$""")
    private val tripleBoldStarRe = Regex("""\*\*\*([^\n]+?)\*\*\*""")
    private val tripleBoldUnderRe = Regex("""(?<![A-Za-z0-9_])___([^\s_\n](?:[^\n]*?[^\s_\n])?)___(?![A-Za-z0-9_])""")
    private val doubleBoldStarRe = Regex("""\*\*([^\n]+?)\*\*""")
    private val doubleBoldUnderRe = Regex("""(?<![A-Za-z0-9_])__([^\s_\n](?:[^\n]*?[^\s_\n])?)__(?![A-Za-z0-9_])""")
    private val strikeRe = Regex("""~~([^\n]+?)~~""")
    private val italicStarRe = Regex("""(?<!\*)\*([^\s*\n](?:[^\n]*?[^\s*\n])?)\*(?!\*)""")
    private val italicUnderRe = Regex("""(?<![A-Za-z0-9_])_([^\s_\n](?:[^\n]*?[^\s_\n])?)_(?![A-Za-z0-9_])""")
    private val escapeRe = Regex("""\\([\\`*_{}\[\]()#+\-.!~|>])""")
    private val multiNewlineRe = Regex("""\n{3,}""")
    private val whitespaceRe = Regex("""\s+""")

    /**
     * Strips Markdown syntax from [raw] while preserving readable structure:
     * - Fenced code blocks (` ```lang ... ``` `) become cleanly indented plain text without fences.
     * - Links `[label](url)` (including `t3-thread://` and `file://`) become `label`.
     * - Inline code, bold, italic, and strikethrough delimiters are removed while keeping inner text.
     * - Heading `#` and blockquote `>` prefixes are removed; `- ` / `* ` bullets become `• `.
     * - 3+ consecutive newlines collapse to a single blank line and surrounding whitespace is trimmed.
     */
    fun stripForNotification(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val out = mutableListOf<String>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = fenceOpenRe.matchEntire(line)
            if (fenceMatch != null) {
                val fenceRun = fenceMatch.groupValues[1]
                val fenceChar = fenceRun[0]
                val fenceLen = fenceRun.length
                val codeLines = mutableListOf<String>()
                i += 1
                while (i < lines.size) {
                    val cur = lines[i]
                    if (isClosingFence(cur, fenceChar, fenceLen)) {
                        i += 1
                        break
                    }
                    codeLines.add(cur.trimEnd())
                    i += 1
                }
                while (codeLines.isNotEmpty() && codeLines.first().isBlank()) codeLines.removeAt(0)
                while (codeLines.isNotEmpty() && codeLines.last().isBlank()) codeLines.removeAt(codeLines.lastIndex)
                for (codeLine in codeLines) {
                    out.add(if (codeLine.isBlank()) "" else "  $codeLine")
                }
                continue
            }

            if (hrRe.matches(line)) {
                out.add("")
                i += 1
                continue
            }

            if (tableSepRe.matches(line)) {
                i += 1
                continue
            }

            val trimmed = line.trim()
            if (trimmed.length >= 3 && trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.count { it == '|' } >= 2) {
                val cells = trimmed
                    .substring(1, trimmed.length - 1)
                    .split('|')
                    .map { stripInline(it.trim()) }
                    .filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) {
                    out.add(cells.joinToString(" · "))
                }
                i += 1
                continue
            }

            var processed = line.trimEnd()
            if (processed.isEmpty()) {
                out.add("")
                i += 1
                continue
            }

            processed = blockquotePrefixRe.replaceFirst(processed, "")
            val isHeading = headingPrefixRe.containsMatchIn(processed)
            if (isHeading) {
                processed = headingPrefixRe.replaceFirst(processed, "")
                processed = headingTrailingRe.replaceFirst(processed, "")
            } else {
                processed = bulletPrefixRe.replaceFirst(processed, "$1• ")
            }

            out.add(stripInline(processed).trimEnd())
            i += 1
        }

        val joined = out.joinToString("\n")
        return multiNewlineRe.replace(joined, "\n\n").trim()
    }

    /**
     * Strips Markdown and collapses all whitespace/newlines into a clean single-line snippet
     * suitable for action sheet previews and reply quote bars.
     */
    fun stripToSingleLine(raw: String): String {
        val stripped = stripForNotification(raw)
        if (stripped.isEmpty()) return ""
        return whitespaceRe.replace(stripped, " ").trim()
    }

    private fun isClosingFence(line: String, fenceChar: Char, minLen: Int): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < minLen) return false
        return trimmed.all { it == fenceChar }
    }

    private sealed interface InlinePart {
        data class Plain(val text: String) : InlinePart
        data class Protected(val text: String) : InlinePart
    }

    private fun stripInline(text: String, depth: Int = 0): String {
        if (text.isEmpty()) return ""
        val parts = mutableListOf<InlinePart>()
        val plainBuf = StringBuilder()

        fun flushPlain() {
            if (plainBuf.isNotEmpty()) {
                parts.add(InlinePart.Plain(plainBuf.toString()))
                plainBuf.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            // 1. Inline code spans (`code` or ``code``) — strip backticks, keep inner text verbatim
            if (ch == '`') {
                val matched = matchCodeSpan(text, i)
                if (matched != null) {
                    flushPlain()
                    val inner = normalizeCodeSpan(text.substring(matched.first, matched.second))
                    parts.add(InlinePart.Protected(inner))
                    i = matched.third
                    continue
                }
                // Stray or truncated opening backtick(s): drop the backticks rather than showing raw `
                while (i < text.length && text[i] == '`') i += 1
                continue
            }

            // 2. Markdown image ![alt](url) -> alt
            if (ch == '!' && i + 1 < text.length && text[i + 1] == '[' && depth < 6) {
                val link = matchLink(text, i + 1)
                if (link != null) {
                    flushPlain()
                    val altText = stripInline(link.first, depth + 1)
                    if (altText.isNotEmpty()) {
                        parts.add(InlinePart.Protected(altText))
                    }
                    i = link.third
                    continue
                }
            }

            // 3. Markdown link [label](url) -> label (supports http, https, t3-thread://, file://, etc.)
            if (ch == '[' && depth < 6) {
                val link = matchLink(text, i)
                if (link != null) {
                    flushPlain()
                    val labelText = stripInline(link.first, depth + 1)
                    parts.add(InlinePart.Protected(labelText))
                    i = link.third
                    continue
                }
            }

            plainBuf.append(ch)
            i += 1
        }
        flushPlain()

        // Strip emphasis & strikethrough across the assembled string while shielding protected spans
        // using private-use placeholders so formatting that wraps inline code (e.g. **`code`**) still strips!
        val protectedValues = mutableListOf<String>()
        val assembled = buildString {
            for (part in parts) {
                when (part) {
                    is InlinePart.Plain -> append(part.text)
                    is InlinePart.Protected -> {
                        val idx = protectedValues.size
                        protectedValues.add(part.text)
                        append('\uE000').append(idx).append('\uE001')
                    }
                }
            }
        }

        var cleaned = stripEmphasis(assembled)
        cleaned = escapeRe.replace(cleaned, "$1")

        if (protectedValues.isEmpty()) return cleaned
        val restored = StringBuilder(cleaned.length)
        var k = 0
        while (k < cleaned.length) {
            if (cleaned[k] == '\uE000') {
                val endMarker = cleaned.indexOf('\uE001', k + 1)
                if (endMarker > k + 1) {
                    val idx = cleaned.substring(k + 1, endMarker).toIntOrNull()
                    if (idx != null && idx in protectedValues.indices) {
                        restored.append(protectedValues[idx])
                        k = endMarker + 1
                        continue
                    }
                }
            }
            restored.append(cleaned[k])
            k += 1
        }
        return restored.toString()
    }

    private fun stripEmphasis(input: String): String {
        var cur = input
        repeat(4) {
            val prev = cur
            cur = tripleBoldStarRe.replace(cur, "$1")
            cur = tripleBoldUnderRe.replace(cur, "$1")
            cur = doubleBoldStarRe.replace(cur, "$1")
            cur = doubleBoldUnderRe.replace(cur, "$1")
            cur = strikeRe.replace(cur, "$1")
            cur = italicStarRe.replace(cur, "$1")
            cur = italicUnderRe.replace(cur, "$1")
            if (cur == prev) return cur
        }
        return cur
    }

    private fun matchCodeSpan(text: String, start: Int): Triple<Int, Int, Int>? {
        var tickEnd = start
        while (tickEnd < text.length && text[tickEnd] == '`') tickEnd += 1
        val tickLen = tickEnd - start
        if (tickLen == 0) return null

        var k = tickEnd
        while (k < text.length) {
            if (text[k] == '`') {
                var closeEnd = k
                while (closeEnd < text.length && text[closeEnd] == '`') closeEnd += 1
                if (closeEnd - k == tickLen) {
                    return Triple(tickEnd, k, closeEnd)
                }
                k = closeEnd
            } else {
                k += 1
            }
        }
        return null
    }

    private fun normalizeCodeSpan(raw: String): String {
        val singleLine = raw.replace('\n', ' ')
        return if (
            singleLine.length >= 2 &&
            singleLine.startsWith(' ') &&
            singleLine.endsWith(' ') &&
            singleLine.any { it != ' ' }
        ) {
            singleLine.substring(1, singleLine.length - 1)
        } else {
            singleLine
        }
    }

    /**
     * Matches `[label](url)` starting at [openBracket], supporting inline code inside labels,
     * nested brackets, and balanced parentheses inside URLs.
     */
    private fun matchLink(text: String, openBracket: Int): Triple<String, String, Int>? {
        var bracketDepth = 0
        var k = openBracket
        var closeBracket = -1
        while (k < text.length) {
            val ch = text[k]
            if (ch == '\n') return null
            if (ch == '`') {
                val code = matchCodeSpan(text, k)
                if (code != null) {
                    k = code.third
                    continue
                }
                while (k < text.length && text[k] == '`') k += 1
                continue
            }
            if (ch == '[') {
                bracketDepth += 1
            } else if (ch == ']') {
                bracketDepth -= 1
                if (bracketDepth == 0) {
                    closeBracket = k
                    break
                }
            }
            k += 1
        }
        if (closeBracket <= openBracket) return null
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null

        val urlStart = closeBracket + 2
        var parenDepth = 0
        var u = urlStart
        var closeParen = -1
        while (u < text.length) {
            val ch = text[u]
            if (ch.isWhitespace()) return null
            if (ch == '(') {
                parenDepth += 1
            } else if (ch == ')') {
                if (parenDepth == 0) {
                    closeParen = u
                    break
                }
                parenDepth -= 1
            }
            u += 1
        }
        if (closeParen <= urlStart) return null
        val url = text.substring(urlStart, closeParen)
        val label = text.substring(openBracket + 1, closeBracket)
        return Triple(label, url, closeParen + 1)
    }
}
