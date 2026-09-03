package com.lhs.share.hub.repository.entity

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.star.response.StarInventoryEntryResponse
import com.lhs.share.hub.controller.star.response.StarInventorySnapshotResponse
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

class StarInventoryContractTest {
    private val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @Test
    fun `entity declares the isolated collection and owner indexes`() {
        val document = StarInventoryCurrent::class.java.getAnnotation(Document::class.java)
        val indexes = StarInventoryCurrent::class.java.getAnnotation(CompoundIndexes::class.java).value

        assertTrue(document.value == "star_inventory_current")
        assertTrue(
            indexes.any {
                it.name == "idx_star_inventory_user_account_unique" &&
                    it.unique &&
                    it.def.contains("userId") &&
                    it.def.contains("accountId")
            },
        )
        assertTrue(
            indexes.any {
                it.name == "idx_star_inventory_user_updated_at" &&
                    it.def.contains("userId") &&
                    it.def.contains("updatedAt")
            },
        )
    }

    @Test
    fun `response keeps null metadata and never exposes persistence-only fields`() {
        val emptyJson = mapper.writeValueAsString(StarInventorySnapshotResponse.empty("acc_a"))
        val json = mapper.writeValueAsString(
            StarInventorySnapshotResponse(
                accountId = "acc_a",
                effectiveAt = Instant.parse("2026-08-31T10:00:00Z"),
                entries = listOf(StarInventoryEntryResponse("main-1", "main", "天府", "orange", 60)),
                revision = 1,
                updatedAt = Instant.parse("2026-08-31T10:00:02Z"),
            ),
        )

        assertTrue(emptyJson.contains("\"effective_at\":null"), emptyJson)
        assertTrue(emptyJson.contains("\"revision\":null"), emptyJson)
        assertTrue(json.contains("\"instance_id\":\"main-1\""), json)
        assertFalse(json.contains("user_id"), json)
        assertFalse(json.contains("content_hash"), json)
    }
}
