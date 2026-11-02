package com.veg.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackupCodecTest {

    private val payload = BackupPayload(
        conversations = listOf(
            Conversation(id = 1, title = "Planning", createdAt = 1000, updatedAt = 2000)
        ),
        messages = listOf(
            Message(id = 1, conversationId = 1, role = Message.ROLE_USER, content = "hello", createdAt = 1001),
            Message(
                id = 2,
                conversationId = 1,
                role = Message.ROLE_ASSISTANT,
                content = "hi \"there\", newline:\nand unicode: नमस्ते",
                createdAt = 1002,
                tokensPerSecond = 9.75f
            )
        )
    )

    @Test
    fun `round trips to an identical payload`() {
        val blob = BackupCodec.encrypt(payload, "correct horse".toCharArray())
        val restored = BackupCodec.decrypt(blob, "correct horse".toCharArray())
        assertEquals(payload, restored)
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val blob = BackupCodec.encrypt(payload, "correct horse".toCharArray())
        assertThrows(WrongPassphraseException::class.java) {
            BackupCodec.decrypt(blob, "wrong horse".toCharArray())
        }
    }

    @Test
    fun `same payload encrypts differently each time`() {
        val a = BackupCodec.encrypt(payload, "pass".toCharArray())
        val b = BackupCodec.encrypt(payload, "pass".toCharArray())
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `plaintext does not appear in the blob`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        val asText = String(blob, Charsets.ISO_8859_1)
        assertTrue("conversation title leaked", !asText.contains("Planning"))
        assertTrue("message content leaked", !asText.contains("hello"))
    }

    @Test
    fun `truncated blob is reported as corrupt`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        assertThrows(CorruptBackupException::class.java) {
            BackupCodec.decrypt(blob.copyOf(12), "pass".toCharArray())
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertThrows(WrongPassphraseException::class.java) {
            BackupCodec.decrypt(blob, "pass".toCharArray())
        }
    }

    @Test
    fun `unknown format version is reported as corrupt`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        blob[4] = 99
        assertThrows(CorruptBackupException::class.java) {
            BackupCodec.decrypt(blob, "pass".toCharArray())
        }
    }

    @Test
    fun `empty payload round trips`() {
        val empty = BackupPayload(emptyList(), emptyList())
        val blob = BackupCodec.encrypt(empty, "pass".toCharArray())
        assertEquals(empty, BackupCodec.decrypt(blob, "pass".toCharArray()))
    }
}
