package com.lhs.share.hub.service.level

import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.level.request.LevelCatalogWriteRequest
import com.lhs.share.hub.repository.entity.level.LevelCatalogEntity
import com.lhs.share.hub.repository.entity.level.LevelCatalogRevisionAction
import com.lhs.share.hub.repository.entity.level.LevelStatus
import com.lhs.share.hub.repository.level.LevelCatalogRepository
import com.lhs.share.hub.repository.level.LevelCatalogRevisionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

class LevelCatalogServiceTest {
    private val repository = mockk<LevelCatalogRepository>(relaxed = true)
    private val revisionRepository = mockk<LevelCatalogRevisionRepository>(relaxed = true)
    private val service = LevelCatalogService(repository, revisionRepository, ObjectMapper().findAndRegisterModules())

    @BeforeEach
    fun setUp() {
        every { repository.save(any()) } answers { firstArg() }
        every { revisionRepository.save(any()) } answers { firstArg() }
        every { repository.findByLevelKey(any()) } returns null
        every { repository.findByGameAndStageId(any(), any()) } returns null
        every { repository.findByGameAndLevelId(any(), any()) } returns null
        every { repository.findTopByOrderByUpdatedAtDesc() } returns null
        every { repository.findAllByOrderBySortOrderAscLevelKeyAsc() } returns emptyList()
    }

    @Test
    fun `create normalizes fields and records create history`() {
        val result = service.create("admin", request())

        assertTrue(result.id.startsWith("lvl_"))
        assertEquals(0, result.revision)
        assertEquals("新关卡", result.name)
        verify {
            revisionRepository.save(match { it.action == LevelCatalogRevisionAction.CREATE && it.actorUserId == "admin" })
        }
    }

    @Test
    fun `same game stage id is rejected`() {
        every { repository.findByGameAndStageId("代号鸢", "stage_1") } returns entity()

        val exception = assertThrows(LevelCatalogApiException::class.java) {
            service.create("admin", request())
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("level_conflict", exception.code)
    }

    @Test
    fun `stale update returns a revision conflict before compare and set`() {
        every { repository.findByLevelKey("lvl_existing") } returns entity()

        val exception = assertThrows(LevelCatalogApiException::class.java) {
            service.update("admin", "lvl_existing", request().copy(expectedRevision = 0))
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("level_revision_conflict", exception.code)
        verify(exactly = 0) { repository.updateIfRevision(any(), any(), any()) }
    }

    @Test
    fun `compare and set failure returns a revision conflict`() {
        every { repository.findByLevelKey("lvl_existing") } returns entity()
        every { repository.updateIfRevision("lvl_existing", 1, any()) } returns null

        val exception = assertThrows(LevelCatalogApiException::class.java) {
            service.update("admin", "lvl_existing", request().copy(expectedRevision = 1))
        }

        assertEquals("level_revision_conflict", exception.code)
    }

    @Test
    fun `archived entries are hidden from the default public catalog`() {
        val active = entity()
        val archived = entity("lvl_archived").copy(status = LevelStatus.ARCHIVED)
        every { repository.findAllByOrderBySortOrderAscLevelKeyAsc() } returns listOf(active, archived)
        every { repository.findTopByOrderByUpdatedAtDesc() } returns active

        val result = service.catalog()
        val withArchived = service.catalog(includeArchived = true)

        assertEquals(listOf("lvl_existing"), result.levels.map { it.id })
        assertEquals(2, withArchived.levels.size)
        assertFalse(result.levels.any { it.status == "ARCHIVED" })
    }

    @Test
    fun `import preview does not write entity or history`() {
        val body = ObjectMapper().readTree("""
            {"levels":[{
              "id":"lvl_imported","game":"如鸢","cat_one":"活动","cat_two":"","cat_three":"",
              "name":"导入关卡","level_id":"event/1","stage_id":"event_1"
            }]}
        """.trimIndent())

        val result = service.previewImport("admin", body)

        assertEquals(1, result.createdCount)
        assertEquals(0, result.errorCount)
        assertEquals("0", result.catalogVersion)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { revisionRepository.save(any()) }
    }

    @Test
    fun `export shaped import creates one import history entry`() {
        val body = ObjectMapper().readTree("""
            {"levels":[{
              "id":"lvl_imported","game":"如鸢","cat_one":"活动","cat_two":"","cat_three":"",
              "name":"导入关卡","level_id":"event/1","stage_id":"event_1"
            }]}
        """.trimIndent())
        val saved = entity("lvl_imported").copy(game = "如鸢", catOne = "活动", catTwo = "", catThree = "", name = "导入关卡", levelId = "event/1", stageId = "event_1")
        every { repository.save(any()) } returns saved
        every { repository.findByLevelKey("lvl_imported") } returns null

        val result = service.commitImport("admin", body)

        assertEquals(1, result.createdCount)
        verify { revisionRepository.save(match { it.action == LevelCatalogRevisionAction.IMPORT }) }
    }

    private fun request() = LevelCatalogWriteRequest(
        game = " 代号鸢 ",
        catOne = " 主线 ",
        catTwo = "第一章",
        catThree = "",
        name = " 新关卡 ",
        levelId = "level/1",
        stageId = "stage_1",
        isOpen = true,
        sortOrder = 0,
    )

    private fun entity(levelKey: String = "lvl_existing") = LevelCatalogEntity(
        levelKey = levelKey,
        game = "代号鸢",
        catOne = "主线",
        catTwo = "第一章",
        catThree = "",
        name = "旧关卡",
        levelId = "level/1",
        stageId = "stage_1",
        revision = 1,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        createdBy = "seed",
        updatedBy = "seed",
    )
}
