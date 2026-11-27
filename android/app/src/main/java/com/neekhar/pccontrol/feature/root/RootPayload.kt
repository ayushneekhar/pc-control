package com.neekhar.pccontrol.feature.root

import java.security.MessageDigest

/**
 * Canonical approval payload + key fingerprint, ported from src/lib/rootPayload.ts.
 * Byte-for-byte compatible with rootd's Python implementation (sig_version=2).
 */
enum class Decision(val wireValue: String) {
    APPROVE("approve"),
    DENY("deny");

    companion object {
        fun fromWire(value: String): Decision = when (value) {
            "approve" -> APPROVE
            "deny" -> DENY
            else -> throw IllegalArgumentException("bad decision")
        }
    }
}

data class SignableRequest(
    val id: String,
    val nonce: String,
    val expires: Long,
    val argv: List<String>,
    val timeout: Int? = null,
    val reason: String? = null,
    val snapSha256: String? = null,
)

object RootPayload {
    const val LONG_COMMAND_CHARS: Int = 600

    private val ID_RE = Regex("^[0-9a-f]{16}$")
    private val NONCE_RE = Regex("^[0-9a-f]{32}$")
    private val SHA256_RE = Regex("^[0-9a-f]{64}$")
    private val QUOTE_RE = Regex("[\\s\"'$`;&|<>(){}*?!#~]")
    private val LONG_SPACES_RE = Regex(" {4,}")
    private val LONG_TABS_RE = Regex("\t{4,}")
    private val LONG_NEWLINES_RE = Regex("\n{4,}")
    private val USER_WRITABLE_RE = Regex("""(?:^|[\s"'=:])(/(?:home|tmp)(?:/|$))""")

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val HEX_CHARS = "0123456789abcdef"

    /** UTF-8 encode; lone surrogates become U+FFFD (Kotlin/JVM String.toByteArray(Charsets.UTF_8) does this). */
    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0f])
        }
        return sb.toString()
    }

    fun base64ToBytes(b64: String): ByteArray {
        val out = ArrayList<Byte>(b64.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (ch in b64) {
            val idx = B64_ALPHABET.indexOf(ch)
            if (idx < 0) continue
            buf = (buf shl 6) or idx
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buf ushr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }

    /**
     * Exact match for JSON.stringify(argv) / Python json.dumps(argv, separators=(",", ":"), ensure_ascii=False).
     */
    fun canonicalArgv(argv: List<String>): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in argv.indices) {
            if (i > 0) sb.append(',')
            sb.append('"')
            for (ch in argv[i]) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u")
                            val hex = ch.code.toString(16).padStart(4, '0')
                            sb.append(hex)
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            sb.append('"')
        }
        sb.append(']')
        return sb.toString()
    }

    fun argvHash(argv: List<String>): String = sha256Hex(utf8(canonicalArgv(argv)))

    /** Same rule as rootd.bad_char: reject Cc, Cf, Cs, Zl, Zp (except \t and \n) and lone surrogates. */
    fun hasBadChar(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\t' || ch == '\n') {
                i++
                continue
            }
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                val cp = Character.toCodePoint(ch, s[i + 1])
                if (isForbiddenCategory(Character.getType(cp))) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(ch)) return true
            if (isForbiddenCategory(Character.getType(ch))) return true
            i++
        }
        return false
    }

    private fun isForbiddenCategory(type: Int): Boolean =
        type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.SURROGATE.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()

    fun argvLooksSafe(argv: List<String>?): Boolean =
        !argv.isNullOrEmpty() && argv.all { !hasBadChar(it) }

    /** Legacy v1 payload (kept for compatibility tests). */
    fun buildPayloadV1(decision: Decision, req: SignableRequest): String {
        require(ID_RE.matches(req.id) && NONCE_RE.matches(req.nonce)) { "bad request id/nonce" }
        require(req.expires > 0L) { "bad expiry" }
        require(argvLooksSafe(req.argv)) { "argv contains characters that cannot be shown faithfully" }
        return "rootd-v1\n${decision.wireValue}\n${req.id}\n${req.nonce}\n${req.expires}\n${argvHash(req.argv)}"
    }

    /**
     * Canonical v2 payload signed by the phone's hardware-backed ECDSA key.
     * Signs timeout, sha256(reason), snap_sha256 (empty when not snapshotted), and argv_hash.
     */
    fun buildPayloadV2(decision: Decision, req: SignableRequest): String {
        require(ID_RE.matches(req.id) && NONCE_RE.matches(req.nonce)) { "bad request id/nonce" }
        require(req.expires > 0L) { "bad expiry" }
        val timeout = req.timeout ?: 600
        require(timeout in 1..3600) { "bad timeout" }
        require(argvLooksSafe(req.argv)) { "argv contains characters that cannot be shown faithfully" }
        val snap = req.snapSha256 ?: ""
        require(snap.isEmpty() || SHA256_RE.matches(snap)) { "bad snapshot sha256" }
        val reasonHash = sha256Hex(utf8(req.reason ?: ""))
        return buildString {
            append("rootd-v2\n")
            append(decision.wireValue).append('\n')
            append(req.id).append('\n')
            append(req.nonce).append('\n')
            append(req.expires).append('\n')
            append(timeout).append('\n')
            append(reasonHash).append('\n')
            append(snap).append('\n')
            append(argvHash(req.argv))
        }
    }

    fun buildPayload(decision: Decision, req: SignableRequest): String = buildPayloadV2(decision, req)

    /** Short enrollment code: first 64 bits of SHA-256 over DER SubjectPublicKeyInfo, as XXXX-XXXX-XXXX-XXXX. */
    fun keyFingerprint(spkiDerBase64: String): String {
        val h = sha256Hex(base64ToBytes(spkiDerBase64)).uppercase()
        return listOf(0, 4, 8, 12).joinToString("-") { h.substring(it, it + 4) }
    }

    /** Visible rendering of one argv element: flags long runs of whitespace and shows tabs/newlines. */
    fun showArg(a: String): String {
        val collapsed = a
            .replace(LONG_SPACES_RE) { "[${it.value.length} spaces]" }
            .replace(LONG_TABS_RE) { "[${it.value.length} tabs]" }
            .replace(LONG_NEWLINES_RE) { "[${it.value.length} newlines]" }
        return collapsed
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
    }

    fun commandLine(argv: List<String>): String =
        argv.joinToString(" ") { a ->
            val s = showArg(a)
            if (s.isEmpty() || QUOTE_RE.containsMatchIn(s)) "\"${s.replace("\"", "\\\"")}\"" else s
        }

    fun isLongCommand(argv: List<String>): Boolean = commandLine(argv).length > LONG_COMMAND_CHARS

    /** True when any argv element references a path under /home or /tmp. */
    fun hasUserWritablePath(argv: List<String>): Boolean =
        argv.any { a ->
            a == "/home" ||
                a == "/tmp" ||
                a.startsWith("/home/") ||
                a.startsWith("/tmp/") ||
                USER_WRITABLE_RE.containsMatchIn(a)
        }
}
