package com.veg.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupPayload(
    val conversations: List<Conversation>,
    val messages: List<Message>
)

class WrongPassphraseException : Exception("Wrong passphrase, or the file has been modified")
class CorruptBackupException(message: String) : Exception(message)

/**
 * Encrypts a backup with AES-256-GCM using a PBKDF2-derived key.
 *
 * File layout, all big-endian:
 *   magic  4 bytes  "JRVS"
 *   version 1 byte  currently 1
 *   salt   16 bytes
 *   iv     12 bytes
 *   ciphertext + GCM tag
 *
 * GCM is authenticated, so a wrong passphrase and a tampered file both surface
 * as an authentication failure rather than silently decoding to garbage.
 */
object BackupCodec {

    private val MAGIC = byteArrayOf('J'.code.toByte(), 'R'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 210_000
    private const val HEADER_BYTES = 4 + 1 + SALT_BYTES + IV_BYTES

    fun encrypt(payload: BackupPayload, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        val ciphertext = cipher.doFinal(toJson(payload).toByteArray(Charsets.UTF_8))

        return MAGIC + byteArrayOf(VERSION) + salt + iv + ciphertext
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): BackupPayload {
        if (blob.size <= HEADER_BYTES) {
            throw CorruptBackupException("File is too short to be a backup")
        }
        if (!blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw CorruptBackupException("Not a Veg backup file")
        }
        if (blob[4] != VERSION) {
            throw CorruptBackupException("Backup format version ${blob[4]} is not supported")
        }

        val salt = blob.copyOfRange(5, 5 + SALT_BYTES)
        val iv = blob.copyOfRange(5 + SALT_BYTES, HEADER_BYTES)
        val ciphertext = blob.copyOfRange(HEADER_BYTES, blob.size)

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPassphraseException()
        }

        return fromJson(String(plaintext, Charsets.UTF_8))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun toJson(payload: BackupPayload): String {
        val conversations = JSONArray()
        for (c in payload.conversations) {
            conversations.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("createdAt", c.createdAt)
                    .put("updatedAt", c.updatedAt)
            )
        }

        val messages = JSONArray()
        for (m in payload.messages) {
            val obj = JSONObject()
                .put("id", m.id)
                .put("conversationId", m.conversationId)
                .put("role", m.role)
                .put("content", m.content)
                .put("createdAt", m.createdAt)
            if (m.tokensPerSecond != null) obj.put("tokensPerSecond", m.tokensPerSecond.toDouble())
            messages.put(obj)
        }

        return JSONObject()
            .put("conversations", conversations)
            .put("messages", messages)
            .toString()
    }

    private fun fromJson(json: String): BackupPayload {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw CorruptBackupException("Backup contents are not valid JSON")
        }

        val conversationsJson = root.getJSONArray("conversations")
        val conversations = (0 until conversationsJson.length()).map { i ->
            val o = conversationsJson.getJSONObject(i)
            Conversation(
                id = o.getLong("id"),
                title = o.getString("title"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt")
            )
        }

        val messagesJson = root.getJSONArray("messages")
        val messages = (0 until messagesJson.length()).map { i ->
            val o = messagesJson.getJSONObject(i)
            Message(
                id = o.getLong("id"),
                conversationId = o.getLong("conversationId"),
                role = o.getString("role"),
                content = o.getString("content"),
                createdAt = o.getLong("createdAt"),
                tokensPerSecond = if (o.has("tokensPerSecond")) o.getDouble("tokensPerSecond").toFloat() else null
            )
        }

        return BackupPayload(conversations, messages)
    }
}
