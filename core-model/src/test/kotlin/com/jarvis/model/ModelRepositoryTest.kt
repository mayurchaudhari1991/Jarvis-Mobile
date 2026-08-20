package com.jarvis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `reports not installed when no file exists`() {
        val repo = ModelRepository(temp.root)
        assertFalse(repo.isInstalled("qwen3-8b-q4km"))
        assertNull(repo.installedFile("qwen3-8b-q4km"))
    }

    @Test
    fun `reports installed when the final file exists`() {
        File(temp.root, "qwen3-8b-q4km.gguf").writeBytes(ByteArray(16))
        val repo = ModelRepository(temp.root)
        assertTrue(repo.isInstalled("qwen3-8b-q4km"))
        assertEquals("qwen3-8b-q4km.gguf", repo.installedFile("qwen3-8b-q4km")?.name)
    }

    @Test
    fun `a partial file does not count as installed`() {
        File(temp.root, "qwen3-8b-q4km.gguf.part").writeBytes(ByteArray(16))
        val repo = ModelRepository(temp.root)
        assertFalse(repo.isInstalled("qwen3-8b-q4km"))
    }

    @Test
    fun `delete removes both the final and partial files`() {
        File(temp.root, "qwen3-8b-q4km.gguf").writeBytes(ByteArray(16))
        File(temp.root, "qwen3-8b-q4km.gguf.part").writeBytes(ByteArray(16))

        ModelRepository(temp.root).delete("qwen3-8b-q4km")

        assertFalse(File(temp.root, "qwen3-8b-q4km.gguf").exists())
        assertFalse(File(temp.root, "qwen3-8b-q4km.gguf.part").exists())
    }
}
