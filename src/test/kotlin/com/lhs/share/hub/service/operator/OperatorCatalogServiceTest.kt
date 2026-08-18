package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.operator.request.OperatorCatalogDiscRequest
import com.lhs.share.hub.controller.operator.request.OperatorCatalogStarStoneRequest
import com.lhs.share.hub.controller.operator.request.OperatorCatalogWriteRequest
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorDiscCatalog
import com.lhs.share.hub.repository.entity.OperatorStarStoneCatalog
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

/**
 * 密探公共图鉴管理（管理员）核心业务单测：目录的增删改查与校验。
 * 权限校验本身在控制器层（AdminOperatorCatalogController），由契约测试覆盖。
 */
class OperatorCatalogServiceTest {
    private val repository = mockk<OperatorCatalogRepository>()
    private val service = OperatorCatalogService(repository, ObjectMapper())

    private fun seed(existing: List<OperatorCatalogEntity> = emptyList()) {
        every { repository.count() } returns 1L // 跳过资源文件整体播种，走"老库回填"路径
        // 回填扫描：老库行一律视为不存在 => 不产生任何 save
        every { repository.findByOperatorId(any()) } returns null
        every { repository.findAllByOrderByOperatorIdAsc() } returns existing
    }

    private fun writeRequest() = OperatorCatalogWriteRequest(
        id = "char_090_new",
        name = "新密探",
        alias = "new alias",
        rarity = 5,
        prof = listOf("阳"),
        subProf = listOf("shenji"),
        games = listOf("如鸢", "代号鸢"),
        discs = listOf(OperatorCatalogDiscRequest(otName = "初始能量+1")),
        starStones = listOf(OperatorCatalogStarStoneRequest("主星石", "main")),
        spOf = null,
    )

    @Test
    fun `create saves a new operator with a fresh catalog version`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns null
        val saved = slot<OperatorCatalogEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val entity = service.create(writeRequest())

        assertEquals("char_090_new", saved.captured.operatorId)
        assertEquals("新密探", saved.captured.name)
        assertEquals(saved.captured.catalogVersion, entity.catalogVersion)
        assertNotEquals("", entity.catalogVersion)
    }

    @Test
    fun `create rejects a duplicate operator id with conflict`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns existing("char_090_new")

        val e = assertThrows(OperatorApiException::class.java) { service.create(writeRequest()) }
        assertEquals(HttpStatus.CONFLICT, e.status)
        assertEquals("operator_conflict", e.code)
    }

    @Test
    fun `update rejects path id and body id mismatch`() {
        seed()
        val e = assertThrows(OperatorApiException::class.java) { service.update("char_other", writeRequest()) }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
        assertEquals("schema_validation_failed", e.code)
    }

    @Test
    fun `update throws not found when operator is absent`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns null
        val e = assertThrows(OperatorApiException::class.java) { service.update("char_090_new", writeRequest()) }
        assertEquals(HttpStatus.NOT_FOUND, e.status)
        assertEquals("operator_not_found", e.code)
    }

    @Test
    fun `update preserves mongo id and createdAt but bumps version`() {
        seed()
        val row = existing("char_090_new", createdAt = Instant.parse("2026-08-01T00:00:00Z"))
        every { repository.findByOperatorId("char_090_new") } returns row
        val saved = slot<OperatorCatalogEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.update("char_090_new", writeRequest().copy(name = "改名"))

        val updated = saved.captured
        assertEquals(row.id, updated.id)
        assertEquals(row.createdAt, updated.createdAt)
        assertEquals("改名", updated.name)
        assertNotEquals(row.catalogVersion, updated.catalogVersion)
    }

    @Test
    fun `delete removes the row and persists the version bump on a remaining row`() {
        seed(listOf(existing("char_001"), existing("char_002")))
        every { repository.findByOperatorId("char_001") } returns existing("char_001")
        every { repository.delete(any()) } returns Unit
        every { repository.findAllByOrderByOperatorIdAsc() } returns listOf(existing("char_002"))
        val saved = slot<OperatorCatalogEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.delete("char_001")

        verify { repository.delete(any()) }
        assertEquals("char_002", saved.captured.operatorId)
    }

    @Test
    fun `delete throws not found when operator is absent`() {
        seed()
        every { repository.findByOperatorId("missing") } returns null
        val e = assertThrows(OperatorApiException::class.java) { service.delete("missing") }
        assertEquals(HttpStatus.NOT_FOUND, e.status)
    }

    @Test
    fun `listForAdmin returns full internal entities ordered by id`() {
        val rows = listOf(existing("char_002"), existing("char_001"))
        seed(rows)
        every { repository.findAllByOrderByOperatorIdAsc() } returns rows
        assertEquals(rows, service.listForAdmin())
    }

    @Test
    fun `validate rejects unsupported game`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns null
        val e = assertThrows(OperatorApiException::class.java) {
            service.create(writeRequest().copy(games = listOf("其他")))
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
        assertEquals("invalid_game", e.code)
    }

    @Test
    fun `validate rejects duplicate disc ot_name`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns null
        val req = writeRequest().copy(
            discs = listOf(OperatorCatalogDiscRequest("初始能量+1"), OperatorCatalogDiscRequest("初始能量+1")),
        )
        val e = assertThrows(OperatorApiException::class.java) { service.create(req) }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
        assertEquals("invalid_disc", e.code)
    }

    @Test
    fun `validate rejects duplicate star stone type`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns null
        val req = writeRequest().copy(
            starStones = listOf(
                OperatorCatalogStarStoneRequest("主星石", "main"),
                OperatorCatalogStarStoneRequest("主星石二号", "main"),
            ),
        )
        val e = assertThrows(OperatorApiException::class.java) { service.create(req) }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
        assertEquals("invalid_star_stone", e.code)
    }

    @Test
    fun `validate rejects spOf referencing itself`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns null
        val e = assertThrows(OperatorApiException::class.java) {
            service.create(writeRequest().copy(spOf = "char_090_new"))
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
        assertEquals("schema_validation_failed", e.code)
    }

    @Test
    fun `validate rejects spOf referencing an unknown base`() {
        seed()
        every { repository.findByOperatorId("char_090_new") } returns null
        every { repository.findByOperatorId("char_missing") } returns null
        val e = assertThrows(OperatorApiException::class.java) {
            service.create(writeRequest().copy(spOf = "char_missing"))
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status)
        assertEquals("unknown_operator_id", e.code)
    }

    @Test
    fun `already-seeded catalog is backfilled with spOf from the resource`() {
        // 模拟升级前播种的老库：目录非空、SP 行没有 spOf。
        val oldSp = existing("char_085_shizimiaosp").copy(rarity = 5, prof = listOf("混沌"), games = listOf("如鸢", "代号鸢"))
        every { repository.count() } returns 1L
        every { repository.findByOperatorId(any()) } returns null
        every { repository.findByOperatorId("char_085_shizimiaosp") } returns oldSp
        every { repository.findAllByOrderByOperatorIdAsc() } returns listOf(oldSp)
        val saved = slot<OperatorCatalogEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.catalog() // 触发 ensureSeeded 回填

        // 资源文件中 char_085_shizimiaosp 的 spOf = char_023_shizimiao
        assertEquals("char_023_shizimiao", saved.captured.spOf)
        assertEquals(oldSp.id, saved.captured.id)          // 保留 mongo _id
        assertEquals(oldSp.createdAt, saved.captured.createdAt) // 保留创建时间
        assertEquals("2026-08-16", saved.captured.catalogVersion) // 版本不重置
    }

    @Test
    fun `already-set spOf is never overwritten by backfill`() {
        val withSp = existing("char_085_shizimiaosp").copy(spOf = "char_evil")
        every { repository.count() } returns 1L
        every { repository.findByOperatorId(any()) } returns null
        every { repository.findByOperatorId("char_085_shizimiaosp") } returns withSp
        every { repository.findAllByOrderByOperatorIdAsc() } returns listOf(withSp)

        service.catalog() // 触发 ensureSeeded

        verify(exactly = 0) { repository.save(any()) }
    }

    private fun existing(id: String, createdAt: Instant = Instant.parse("2026-08-01T00:00:00Z")) = OperatorCatalogEntity(
        id = "mongo_" + id,
        operatorId = id,
        name = id,
        rarity = 5,
        prof = listOf("阳"),
        subProf = emptyList(),
        games = listOf("如鸢"),
        discs = listOf(OperatorDiscCatalog("初始能量+1")),
        starStones = listOf(OperatorStarStoneCatalog("主星石", "main")),
        catalogVersion = "2026-08-16",
        createdAt = createdAt,
    )
}
