package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.repository.InventoryAgentFavoriteRepository
import com.lhs.share.hub.repository.OperatorAnnotationRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorGrowthTargetRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.InventoryAgentFavorite
import com.lhs.share.hub.repository.entity.OperatorAnnotation
import com.lhs.share.hub.repository.entity.OperatorCurrent
import com.lhs.share.hub.repository.entity.OperatorEntry
import com.lhs.share.hub.repository.entity.OperatorGrowthTarget
import com.lhs.share.hub.repository.entity.SubAccount
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperatorV3ExportServiceTest {
    private val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @Test
    fun `v3 full export contains objective and complete subjective backup and validates schema`() {
        val accounts = mockk<SubAccountRepository>()
        val currents = mockk<OperatorCurrentRepository>()
        val annotations = mockk<OperatorAnnotationRepository>()
        val targets = mockk<OperatorGrowthTargetRepository>()
        val favorites = mockk<InventoryAgentFavoriteRepository>()
        val catalog = mockk<OperatorCatalogService>()
        val account = SubAccount(userId = "u1", accountId = "a1", name = "账号", game = "如鸢")
        every { accounts.findByUserIdAndAccountId("u1", "a1") } returns account
        every { currents.findByUserIdAndAccountIdOrderByUpdatedAtDesc("u1", "a1") } returns listOf(
            OperatorCurrent(
                userId = "u1",
                accountId = "a1",
                game = "如鸢",
                entries = mapOf("op1" to OperatorEntry(elite = 17, starLevel = 31, level = 100, revision = 4)),
            ),
        )
        every { annotations.findAllByUserIdAndAccountIdOrderByOperatorIdAsc("u1", "a1") } returns listOf(
            OperatorAnnotation(userId = "u1", accountId = "a1", operatorId = "op1", growthState = "graduated", note = "继续收集"),
        )
        every { targets.findAllByUserIdAndAccountIdOrderByOperatorIdAsc("u1", "a1") } returns listOf(
            OperatorGrowthTarget(userId = "u1", accountId = "a1", operatorId = "op1", targetStarLevel = 31),
        )
        every { favorites.findAllByUserIdAndAccountIdOrderByAgentIdAsc("u1", "a1") } returns listOf(
            InventoryAgentFavorite(userId = "u1", accountId = "a1", agentId = "op1"),
        )
        every { catalog.currentCatalogVersion() } returns "catalog-1"
        val service = OperatorV3ExportService(mapper, accounts, currents, annotations, targets, favorites, catalog)

        val exported = service.export("u1", "a1", null)
        OperatorV3SchemaValidator(mapper).validate(exported)

        assertEquals(3, exported.path("version").intValue())
        assertEquals(2, exported.path("records").size())
        val subjective = exported.path("records").first { it.path("record_type").asText() == "operator_annotation_snapshot" }
        val entry = subjective.path("entries").single()
        assertEquals("graduated", entry.path("growth_state").asText())
        assertTrue(entry.path("favorite").booleanValue())
        assertEquals("继续收集", entry.path("note").asText())
        assertEquals(31, entry.path("targets").path("star_level").intValue())
    }
}
