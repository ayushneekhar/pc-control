package com.neekhar.pccontrol.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class BiometricSignerPayloadTest {

  @Test
  fun buildPayloadV2_matchesRootdProtocolByteForByte() {
    val req = RootApprovalRequest(
      id = "0123456789abcdef",
      nonce = "a".repeat(32),
      expires = 1794128000L,
      timeout = 120,
      reason = "restart nginx",
      snapSha256 = "b".repeat(64),
      argv = listOf("/usr/bin/systemctl", "restart", "nginx"),
    )
    val payload = BiometricSigner.buildPayloadV2(RootDecision.APPROVE, req)
    val expected = listOf(
      "rootd-v2",
      "approve",
      req.id,
      req.nonce,
      "1794128000",
      "120",
      BiometricSigner.sha256Hex("restart nginx".toByteArray(Charsets.UTF_8)),
      "b".repeat(64),
      BiometricSigner.argvHash(req.argv),
    ).joinToString("\n")
    assertEquals(expected, payload)
  }

  @Test
  fun canonArgv_escapesJsonAndPreservesUnicode() {
    val canon = BiometricSigner.canonArgv(listOf("echo", "a\"b\\c\td\ne", "café"))
    assertEquals("""["echo","a\"b\\c\td\ne","café"]""", canon)
  }

  @Test
  fun hasBadChar_rejectsControlFormatAndLoneSurrogates() {
    assertFalse(BiometricSigner.hasBadChar("normal\ttext\nline2"))
    assertFalse(BiometricSigner.hasBadChar("valid emoji \uD83D\uDE80"))
    assertTrue(BiometricSigner.hasBadChar("null\u0000byte"))
    assertTrue(BiometricSigner.hasBadChar("rtl\u202Eoverride"))
    assertTrue(BiometricSigner.hasBadChar("zero\u200Bwidth"))
    assertTrue(BiometricSigner.hasBadChar("line\u2028sep"))
    assertTrue(BiometricSigner.hasBadChar("lone\uD800high"))
    assertTrue(BiometricSigner.hasBadChar("lone\uDC00low"))
  }

  @Test
  fun showArgAndFormatCommand_collapseLongWhitespace() {
    assertEquals("a   b", BiometricSigner.showArg("a   b"))
    assertEquals("a[12 spaces]b", BiometricSigner.showArg("a" + " ".repeat(12) + "b"))
    assertEquals("x[5 tabs]y", BiometricSigner.showArg("x" + "\t".repeat(5) + "y"))
    assertEquals("x[4 newlines]y", BiometricSigner.showArg("x" + "\n".repeat(4) + "y"))
    assertEquals("line1\\nline2\\tend", BiometricSigner.showArg("line1\nline2\tend"))

    val padded = BiometricSigner.formatCommand(
      listOf("/bin/sh", "-c", "systemctl restart nginx" + " ".repeat(600) + "; id"),
    )
    assertTrue(padded.contains("[600 spaces]; id"))
  }

  @Test
  fun isLongCommandAndHasUserWritablePath_behaveCorrectly() {
    assertFalse(BiometricSigner.isLongCommand(listOf("/usr/bin/echo", "short")))
    assertTrue(
      BiometricSigner.isLongCommand(
        listOf("/usr/bin/echo", "a".repeat(BiometricSigner.LONG_COMMAND_CHARS + 10)),
      ),
    )

    assertFalse(BiometricSigner.hasUserWritablePath(listOf("/usr/bin/systemctl", "restart", "nginx")))
    assertTrue(BiometricSigner.hasUserWritablePath(listOf("/home/ayushlive/fix.sh")))
    assertTrue(BiometricSigner.hasUserWritablePath(listOf("/bin/bash", "/tmp/run.sh")))
    assertTrue(BiometricSigner.hasUserWritablePath(listOf("/usr/bin/python3", "--script=/home/ayushlive/x.py")))
    assertTrue(BiometricSigner.hasUserWritablePath(listOf("/usr/bin/ls", "/tmp")))
    assertFalse(BiometricSigner.hasUserWritablePath(listOf("/usr/bin/echo", "not-a-home-path")))
  }

  @Test
  fun keyFingerprintAndPemFormatting_produceExpectedFormat() {
    val sampleDer = ByteArray(91) { it.toByte() }
    val b64 = Base64.getEncoder().encodeToString(sampleDer)
    val fp = BiometricSigner.keyFingerprint(b64)
    assertTrue(Regex("^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$").matches(fp))
    assertEquals(BiometricSigner.keyFingerprintFromDer(sampleDer), fp)

    val pem = BiometricSigner.formatSpkiPem(sampleDer)
    assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"))
    assertTrue(pem.endsWith("\n-----END PUBLIC KEY-----\n"))
  }

  @Test
  fun buildPayloadV2_rejectsInvalidFields() {
    val valid = RootApprovalRequest(
      id = "0123456789abcdef",
      nonce = "0".repeat(32),
      expires = 1794128000L,
      timeout = 60,
      argv = listOf("/usr/bin/true"),
    )

    val invalidCases = listOf(
      valid.copy(id = "short"),
      valid.copy(nonce = "nothex"),
      valid.copy(expires = 0L),
      valid.copy(timeout = 0),
      valid.copy(timeout = 3601),
      valid.copy(argv = emptyList()),
      valid.copy(argv = listOf("")),
      valid.copy(argv = listOf("/bin/echo", "bad\u202Etext")),
      valid.copy(snapSha256 = "not-64-hex"),
    )

    for (bad in invalidCases) {
      try {
        BiometricSigner.buildPayloadV2(RootDecision.APPROVE, bad)
        fail("Expected PlatformException for $bad")
      } catch (e: PlatformException) {
        assertEquals("E_BAD_REQUEST", e.code)
      }
    }
  }
}
