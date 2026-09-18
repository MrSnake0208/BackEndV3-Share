package com.lhs.share.hub.work

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.repository.entity.level.LevelCatalogEntity
import com.lhs.share.hub.repository.level.LevelCatalogRepository
import com.lhs.share.hub.work.adapter.MaaYuanWorkAdapter
import com.lhs.share.hub.work.adapter.YuanAssistWorkAdapter
import com.lhs.share.hub.work.parser.MaaYuanLegacyParser
import com.lhs.share.hub.work.repository.MaaCopilotWorkSource
import com.lhs.share.hub.work.repository.MaaCopilotWorkSourceRepository
import com.lhs.share.hub.work.service.WorkService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkServiceTest {
    private val sourceRepository = mockk<MaaCopilotWorkSourceRepository>()
    private val levelRepository = mockk<LevelCatalogRepository>()
    private val service = WorkService(
        sourceRepository,
        levelRepository,
        MaaYuanLegacyParser(jacksonObjectMapper()),
        MaaYuanWorkAdapter(),
        YuanAssistWorkAdapter(),
    )

    @Test
    fun `detail uses raw content and links only one exact catalog match`() {
        every { sourceRepository.findPublicById(7) } returns source(tags = listOf("如鸢"))
        every { levelRepository.findAllByOrderBySortOrderAscLevelKeyAsc() } returns listOf(level("lvl_1", "如鸢"))

        val detail = service.get(7)

        assertEquals("lvl_1", detail.work!!.levelId)
        assertEquals("如鸢", detail.work!!.game)
        assertEquals("attack", (detail.work!!.rounds.single().actions.single() as com.lhs.share.hub.work.model.SlotAction).type)
        assertEquals(RAW_CONTENT, detail.source.rawContent)
    }

    @Test
    fun `ambiguous game tags do not guess a protocol game`() {
        every { sourceRepository.findPublicById(7) } returns source(tags = listOf("代号鸢", "如鸢"))
        every { levelRepository.findAllByOrderBySortOrderAscLevelKeyAsc() } returns emptyList()

        val detail = service.get(7)

        assertNull(detail.work)
        assertTrue(detail.conversion.issues.any { it.code == "ambiguous_game" })
    }

    private fun source(tags: List<String>) = MaaCopilotWorkSource(
        copilotId = 7,
        stageName = "数据库冗余关卡",
        name = "数据库冗余标题",
        content = RAW_CONTENT,
        tags = tags,
    )

    private fun level(key: String, game: String) = LevelCatalogEntity(
        levelKey = key,
        game = game,
        catOne = "活动",
        catTwo = "测试活动",
        catThree = "",
        name = "测试关卡",
        levelId = "level-1",
        stageId = "stage-1",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        createdBy = "test",
        updatedBy = "test",
    )

    private companion object {
        const val RAW_CONTENT =
            "{\"stage_name\":\"测试关卡\",\"stage_id\":\"stage-1\",\"doc\":{\"title\":\"content 标题\",\"details\":\"\"}," +
                "\"opers\":[{\"name\":\"一\"},{\"name\":\"二\"},{\"name\":\"三\"},{\"name\":\"四\"},{\"name\":\"五\"}]," +
                "\"actions\":{\"回合1行动1\":{\"text_doc\":\"1普\"}}}"
    }
}
