package com.neekhar.pccontrol.feature.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPayloadTest {

    @Test
    fun buildPayloadV2IncludesTimeoutReasonSha256SnapSha256AndArgvHash() {
        val req = SignableRequest(
            id = "0123456789abcdef",
            nonce = "a".repeat(32),
            expires = 1794128000L,
            timeout = 120,
            reason = "restart nginx",
            snapSha256 = "b".repeat(64),
            argv = listOf("/usr/bin/systemctl", "restart", "nginx"),
        )
        val p2 = RootPayload.buildPayloadV2(Decision.APPROVE, req)
        val expectedV2 = listOf(
            "rootd-v2",
            "approve",
            req.id,
            req.nonce,
            req.expires.toString(),
            "120",
            RootPayload.sha256Hex(RootPayload.utf8("restart nginx")),
            "b".repeat(64),
            RootPayload.argvHash(req.argv),
        ).joinToString("\n")
        assertEquals(expectedV2, p2)

        val p1 = RootPayload.buildPayloadV1(Decision.APPROVE, req)
        assertEquals(
            "rootd-v1\napprove\n${req.id}\n${req.nonce}\n${req.expires}\n${RootPayload.argvHash(req.argv)}",
            p1,
        )
    }

    @Test
    fun buildPayloadV2DefaultsTimeoutAndEmptyOptionalFields() {
        val req = SignableRequest(
            id = "0123456789abcdef",
            nonce = "c".repeat(32),
            expires = 1794128000L,
            argv = listOf("/usr/bin/id"),
        )
        val p2 = RootPayload.buildPayloadV2(Decision.DENY, req)
        val lines = p2.split("\n")
        assertEquals(9, lines.size)
        assertEquals("rootd-v2", lines[0])
        assertEquals("deny", lines[1])
        assertEquals("600", lines[5])
        assertEquals(RootPayload.sha256Hex(ByteArray(0)), lines[6])
        assertEquals("", lines[7])
        assertEquals(RootPayload.argvHash(req.argv), lines[8])
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildPayloadV2RejectsControlCharactersInArgv() {
        val req = SignableRequest(
            id = "0123456789abcdef",
            nonce = "a".repeat(32),
            expires = 1794128000L,
            argv = listOf("/usr/bin/echo", "evil\u0007bell"),
        )
        RootPayload.buildPayloadV2(Decision.APPROVE, req)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildPayloadV2RejectsInvalidSnapshotHash() {
        val req = SignableRequest(
            id = "0123456789abcdef",
            nonce = "a".repeat(32),
            expires = 1794128000L,
            argv = listOf("/usr/bin/id"),
            snapSha256 = "not-a-sha256",
        )
        RootPayload.buildPayloadV2(Decision.APPROVE, req)
    }

    @Test
    fun showArgCollapsesLongRunsOfWhitespaceIntoVisibleMarkers() {
        assertEquals("a   b", RootPayload.showArg("a   b"))
        assertEquals("a[12 spaces]b", RootPayload.showArg("a" + " ".repeat(12) + "b"))
        assertEquals("x[5 tabs]y", RootPayload.showArg("x" + "\t".repeat(5) + "y"))
        assertEquals("x[4 newlines]y", RootPayload.showArg("x" + "\n".repeat(4) + "y"))
        assertEquals("line1\\nline2\\tend", RootPayload.showArg("line1\nline2\tend"))
    }

    @Test
    fun isLongCommandFlagsCommandsOver600Chars() {
        assertFalse(RootPayload.isLongCommand(listOf("/usr/bin/echo", "short")))
        assertTrue(RootPayload.isLongCommand(listOf("/usr/bin/echo", "a".repeat(RootPayload.LONG_COMMAND_CHARS + 10))))
        val padded = RootPayload.commandLine(
            listOf("/bin/sh", "-c", "systemctl restart nginx" + " ".repeat(600) + "; id"),
        )
        assertTrue(padded.contains("[600 spaces]; id"))
    }

    @Test
    fun hasUserWritablePathDetectsHomeAndTmpPaths() {
        assertFalse(RootPayload.hasUserWritablePath(listOf("/usr/bin/systemctl", "restart", "nginx")))
        assertTrue(RootPayload.hasUserWritablePath(listOf("/home/ayushlive/fix.sh")))
        assertTrue(RootPayload.hasUserWritablePath(listOf("/bin/bash", "/tmp/run.sh")))
        assertTrue(RootPayload.hasUserWritablePath(listOf("/usr/bin/python3", "--script=/home/ayushlive/x.py")))
        assertTrue(RootPayload.hasUserWritablePath(listOf("/usr/bin/ls", "/tmp")))
        assertFalse(RootPayload.hasUserWritablePath(listOf("/usr/bin/echo", "not-a-home-path")))
    }

    @Test
    fun keyFingerprintFormatsFirst64BitsAsHyphenatedUpperHex() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb924...
        assertEquals("E3B0-C442-98FC-1C14", RootPayload.keyFingerprint(""))
    }
}
