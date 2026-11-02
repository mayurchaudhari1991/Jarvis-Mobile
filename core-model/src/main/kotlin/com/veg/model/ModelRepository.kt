package com.veg.model

import java.io.File

/**
 * Tracks which model files are present on disk.
 *
 * Only the promoted .gguf name counts as installed; a .part file is in-flight
 * data and must never be handed to the inference engine.
 */
class ModelRepository(private val targetDir: File) {

    fun installedFile(id: String): File? =
        File(targetDir, "$id.gguf").takeIf { it.exists() && it.length() > 0 }

    fun isInstalled(id: String): Boolean = installedFile(id) != null

    fun delete(id: String) {
        File(targetDir, "$id.gguf").delete()
        File(targetDir, "$id.gguf.part").delete()
    }
}
