package com.lhs.share.hub.repository.entity

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

class StarWorkspaceLoadoutContractTest {
    @Test
    fun `workspace and loadout use isolated account owner collections`() {
        listOf(
            StarWorkspaceCurrent::class.java to "star_workspace_current",
            StarLoadoutCurrent::class.java to "star_loadout_current",
        ).forEach { (type, collection) ->
            val document = type.getAnnotation(Document::class.java)
            val indexes = type.getAnnotation(CompoundIndexes::class.java).value
            assertTrue(document.value == collection)
            assertTrue(indexes.any { it.unique && it.def.contains("userId") && it.def.contains("accountId") })
            assertTrue(indexes.any { it.def.contains("userId") && it.def.contains("updatedAt") })
        }
    }

    @Test
    fun `persistence payloads use typed values instead of dynamic BSON map keys`() {
        assertFalse(StarWorkspaceCurrent::class.java.getDeclaredField("planTargets").type == Map::class.java)
        assertFalse(StarLoadoutCurrent::class.java.getDeclaredField("loadouts").type == Map::class.java)
    }
}
