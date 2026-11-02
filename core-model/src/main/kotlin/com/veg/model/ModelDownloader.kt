package com.veg.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface DownloadProgress {
    data class Running(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress
    data object Verifying : DownloadProgress
    data class Done(val file: File) : DownloadProgress
    data class Failed(val reason: String, val canRetry: Boolean) : DownloadProgress
}

/**
 * Downloads a model file with resume support and mandatory checksum verification.
 *
 * A multi-gigabyte download over mobile will be interrupted, so partial data is
 * kept in a .part file and continued with a Range request. The file is only
 * promoted to its final name after the checksum matches, which means a partial
 * or corrupt file can never be mistaken for an installed model.
 */
class ModelDownloader(
    private val client: OkHttpClient,
    private val targetDir: File
) {

    fun download(spec: ModelSpec): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Running(bytesDownloaded = 0L, totalBytes = spec.sizeBytes))

        val finalFile = File(targetDir, "${spec.id}.gguf")
        val partFile = File(targetDir, "${spec.id}.gguf.part")

        if (!targetDir.exists()) targetDir.mkdirs()

        if (finalFile.exists() && finalFile.length() == spec.sizeBytes) {
            emit(DownloadProgress.Done(finalFile))
            return@flow
        }

        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val request = Request.Builder()
            .url(spec.url)
            .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        DownloadProgress.Failed(
                            reason = "Server returned ${response.code}",
                            canRetry = response.code >= 500 || response.code == 429
                        )
                    )
                    return@flow
                }

                // A 200 to a Range request means the server ignored it; start over
                // rather than appending fresh bytes onto stale ones.
                val appending = existingBytes > 0 && response.code == 206
                if (!appending && partFile.exists()) partFile.delete()

                val bodyBytes = response.body?.contentLength() ?: -1L
                val total = if (bodyBytes > 0) {
                    (if (appending) existingBytes else 0L) + bodyBytes
                } else {
                    spec.sizeBytes
                }

                var written = if (appending) existingBytes else 0L
                var lastEmitted = 0L

                response.body?.byteStream()?.use { input ->
                    java.io.FileOutputStream(partFile, appending).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read

                            // Emitting on every 64 KB chunk would flood the UI;
                            // once per megabyte is smooth at this file size.
                            if (written - lastEmitted >= EMIT_INTERVAL_BYTES) {
                                lastEmitted = written
                                emit(DownloadProgress.Running(written, total))
                            }
                        }
                    }
                } ?: run {
                    emit(DownloadProgress.Failed("Empty response body", canRetry = true))
                    return@flow
                }

                emit(DownloadProgress.Running(written, total))
            }
        } catch (e: IOException) {
            emit(DownloadProgress.Failed(e.message ?: "Network error", canRetry = true))
            return@flow
        }

        emit(DownloadProgress.Verifying)

        val actual = Sha256Verifier.hexDigest(partFile)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            partFile.delete()
            emit(
                DownloadProgress.Failed(
                    reason = "Downloaded file failed verification and was discarded",
                    canRetry = true
                )
            )
            return@flow
        }

        if (!partFile.renameTo(finalFile)) {
            emit(DownloadProgress.Failed("Could not finalise the downloaded file", canRetry = true))
            return@flow
        }

        emit(DownloadProgress.Done(finalFile))
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val EMIT_INTERVAL_BYTES = 1L * 1024 * 1024
    }
}
