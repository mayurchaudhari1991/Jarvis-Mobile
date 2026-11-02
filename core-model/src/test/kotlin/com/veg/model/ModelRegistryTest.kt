package com.veg.model

import com.veg.device.ModelCatalog
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `no placeholder checksums remain`() {
        for (candidate in ModelCatalog.ALL) {
            val spec = ModelRegistry.specFor(candidate.id)!!
            assertTrue("${candidate.id} still has a placeholder", !spec.sha256.contains("PIN_IN", ignoreCase = true))
        }
    }

    @Test
    fun `checksums are well formed sha256 hex`() {
        val hex = Regex("^[0-9a-f]{64}$")
        for (candidate in ModelCatalog.ALL) {
            val spec = ModelRegistry.specFor(candidate.id)!!
            assertTrue("${candidate.id}: ${spec.sha256}", hex.matches(spec.sha256.lowercase()))
        }
    }
}
