package com.veg.model

import java.io.File
import java.security.MessageDigest

object Sha256Verifier {

    /** Streams the file so a 5 GB model never lands in memory. */
    fun hexDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
