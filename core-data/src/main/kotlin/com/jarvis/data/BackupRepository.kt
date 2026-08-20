package com.jarvis.data

import androidx.room.withTransaction

/**
 * Reads and writes the whole database as one encrypted blob.
 *
 * Import replaces rather than merges: a backup is a snapshot of a point in
 * time, and merging two histories would produce a conversation that never
 * happened. The replace runs in a transaction so a failure part-way leaves the
 * existing data intact.
 */
class BackupRepository(private val db: JarvisDatabase) {

    suspend fun export(passphrase: CharArray): ByteArray {
        val conversations = db.conversations().observeAllOnce()
        val messages = conversations.flatMap { db.messages().forConversation(it.id) }
        return BackupCodec.encrypt(BackupPayload(conversations, messages), passphrase)
    }

    suspend fun import(blob: ByteArray, passphrase: CharArray) {
        val payload = BackupCodec.decrypt(blob, passphrase)
        db.withTransaction {
            // Messages cascade from conversations, so deleting conversations is enough.
            db.conversations().deleteAll()
            for (c in payload.conversations) db.conversations().insert(c)
            for (m in payload.messages) db.messages().insert(m)
        }
    }
}
