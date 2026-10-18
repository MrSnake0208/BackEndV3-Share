package com.lhs.share.hub.service.achievement

import com.lhs.share.hub.controller.achievement.request.AchievementImportRequest
import com.lhs.share.hub.controller.achievement.request.AchievementPatch
import com.lhs.share.hub.controller.achievement.response.AchievementSource
import com.lhs.share.hub.repository.AchievementCatalogRepository
import com.lhs.share.hub.repository.AchievementRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.AchievementArchive
import com.lhs.share.hub.repository.entity.AchievementRecord
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.testinfra.TestMongo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
class AchievementCatalogMongoTest {
    private val database = TestMongo.database("ach_catalog")
    private val client = TestMongo.client()
    private val factory = SimpleMongoClientDatabaseFactory(client, database)
    private val mongo = MongoTemplate(factory)
    private val mapper = achievementMapper()
    private val repository = AchievementCatalogRepository(mongo)
    private val publicCatalog = AchievementCatalogService(mapper, repository)
    private val admin = AchievementCatalogAdminService(publicCatalog, repository, mapper)
    private val game = "代号鸢"

    @AfterEach
    fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }

    private fun ready(targetGame: String = game): CatalogAdminView {
        var view = admin.read(targetGame)
        view = admin.addCategory(targetGame, view.revision, "合成测试分类")
        view = admin.addEntry(targetGame, view.revision, view.draft.categories.first().categoryId)
        val entry = view.draft.entries.first().copy(
            name = "测试成就", condition = "合成条件", reward = "无奖励",
            sources = listOf(AchievementSource("合成来源", "https://example.invalid/test", "独立测试 fixture")),
            verifiedAt = "2026-10-18", dataStatus = "verified", progressKind = "integer", target = 10.0, unit = "次",
        )
        return admin.save(targetGame, view.revision, view.draft.copy(entries = listOf(entry), dataStatus = "partial", coverage = "合成测试范围"))
    }

    @Test
    fun `draft isolated publish immediate restart durable seed idempotent and games independent`() {
        val other = publicCatalog.catalog("如鸢")
        val draft = ready()
        assertTrue(publicCatalog.catalog(game).entries.isEmpty())
        val preview = admin.preview(game, draft.revision)
        assertEquals(1, preview.changes.count { it.kind == "add" })
        assertTrue(preview.errors.isEmpty())
        val published = admin.publish(game, draft.revision, "admin-a")
        assertEquals(draft.draft.entries, publicCatalog.catalog(game).entries)
        assertEquals("admin-a", published.published.publishedBy)
        val restarted = AchievementCatalogService(mapper, repository)
        assertEquals(publicCatalog.catalog(game), restarted.catalog(game))
        assertEquals(other, restarted.catalog("如鸢"))
        assertEquals(published.revision, restarted.load(game).revision)
        assertThrows(InventoryApiException::class.java) { admin.save(game, draft.revision, draft.draft) }
        assertEquals(published, admin.read(game))
        assertThrows(InventoryApiException::class.java) { admin.publish(game, draft.revision, "stale-admin") }
        val ry = ready("如鸢")
        val ryPublished = admin.publish("如鸢", ry.revision, "admin-ry")
        assertNotEquals(published.published.version, ryPublished.published.version)
        assertEquals(published, admin.read(game))
        assertEquals("如鸢", publicCatalog.catalog("如鸢").entries.single().game)
    }

    @Test
    fun `simultaneous editors cannot silently replace each other`() {
        val current = ready()
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { n ->
                pool.submit(
                    Callable {
                        latch.await()
                        runCatching { admin.save(game, current.revision, current.draft.copy(coverage = "编辑$n")) }
                    },
                )
            }
            latch.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(409, (results.single { it.isFailure }.exceptionOrNull() as InventoryApiException).status.value())
            assertEquals(current.revision + 1, admin.read(game).revision)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `existing unverified draft publishes without dates rewards sources or manual coverage`() {
        val current = ready()
        val stored = repository.find(game)!!
        val minimal = current.draft.entries.single().copy(
            condition = "",
            reward = null,
            sources = emptyList(),
            verifiedAt = "",
            dataStatus = "unverified",
            progressKind = null,
            target = null,
            unit = null,
        )
        assertTrue(
            repository.replace(
                stored.copy(
                    revision = stored.revision + 1,
                    draft = stored.draft.copy(entries = listOf(minimal), dataStatus = "empty", coverage = "暂无已核验资料"),
                ),
                stored.revision,
            ),
        )
        val revision = stored.revision + 1
        assertTrue(admin.preview(game, revision).errors.isEmpty())
        assertTrue(publicCatalog.catalog(game).entries.isEmpty())
        val published = admin.publish(game, revision, "admin")
        assertEquals("partial", published.published.content.dataStatus)
        assertEquals(minimal, publicCatalog.catalog(game).entries.single())
        assertEquals("", published.draft.entries.single().verifiedAt)
        assertEquals("unverified", published.draft.entries.single().dataStatus)
        assertThrows(InventoryApiException::class.java) { admin.publish(game, revision, "other") }
    }

    @Test
    fun `thirty minimal imported achievements publish and players record their own completion`() {
        var current = admin.read(game)
        val document = mapper.createObjectNode().put("game", game)
        document.putArray("categories").addObject().put("category_id", "example_category").put("name", "测试分类")
        val rows = document.putArray("entries")
        repeat(30) { rows.addObject().put("game", game).put("category_id", "example_category").put("name", "测试成就$it") }
        val preview = admin.importPreview(game, current.revision, "admin", document, "keep")
        assertEquals(31, preview.items.count { it.outcome == "add" })
        current = admin.importConfirm(game, current.revision, "admin", document, "keep", preview.previewToken)
        assertEquals("partial", current.draft.dataStatus)
        assertTrue(publicCatalog.catalog(game).entries.isEmpty())
        assertTrue(admin.preview(game, current.revision).errors.isEmpty())
        current = admin.publish(game, current.revision, "admin")
        assertEquals(30, publicCatalog.catalog(game).entries.size)
        assertTrue(publicCatalog.catalog("如鸢").entries.isEmpty())
        val archives = AchievementRepository(mongo)
        mongo.createCollection(AchievementArchive::class.java)
        val accounts = mockk<SubAccountRepository>()
        val owners = listOf(
            SubAccount(id = "a", userId = "owner", accountId = "a", name = "甲", game = game),
            SubAccount(id = "b", userId = "other", accountId = "b", name = "乙", game = game),
        )
        every { accounts.findByUserIdAndAccountId(any(), any()) } answers {
            owners.find { it.userId == firstArg<String>() && it.accountId == secondArg<String>() }
        }
        every { accounts.fenceRecruitmentWrite(any(), any(), any()) } returns true
        val personal = AchievementService(
            archives,
            accounts,
            publicCatalog,
            mapper,
            AchievementImportPreviews(),
            TransactionTemplate(MongoTransactionManager(factory)),
        )
        val id = current.draft.entries.first().achievementId
        val result = personal.update(
            "owner",
            "a",
            0,
            listOf(AchievementPatch(id, setOf("status", "favorite", "note"), status = "completed", favorite = true, note = "个人备注")),
        )
        assertEquals("completed", result.records.single().status)
        assertEquals("个人备注", personal.export("owner", "a").records.single().note)
        assertTrue(personal.archive("other", "b").records.isEmpty())
        assertThrows(InventoryApiException::class.java) { personal.archive("other", "a") }
    }

    @Test
    fun `invalid names links references duplicate ids and unsupported progress prevent publication`() {
        var current = ready()
        val entry = current.draft.entries.single()
        for (bad in listOf(
            current.draft.copy(entries = listOf(entry.copy(sources = listOf(AchievementSource("来源", "http://example.invalid", ""))))),
            current.draft.copy(entries = listOf(entry.copy(name = ""))),
        )) {
            current = admin.save(game, current.revision, bad)
            assertTrue(admin.preview(game, current.revision).errors.isNotEmpty())
            assertThrows(InventoryApiException::class.java) { admin.publish(game, current.revision, "admin") }
            assertTrue(publicCatalog.catalog(game).entries.isEmpty())
        }
        for (bad in listOf(
            current.draft.copy(entries = listOf(entry, entry)),
            current.draft.copy(entries = listOf(entry.copy(categoryId = "missing"))),
            current.draft.copy(categories = current.draft.categories.map { it.copy(enabled = false) }),
            current.draft.copy(entries = listOf(entry.copy(progressKind = null, target = 1.0))),
            current.draft.copy(entries = listOf(entry.copy(achievementId = "yh_forged_new"))),
        )) {
            assertThrows(InventoryApiException::class.java) { admin.save(game, current.revision, bad) }
        }
    }

    @Test
    fun `rename retire rollback preserve personal facts and previews reject new catalog version`() {
        var current = ready()
        current = admin.publish(game, current.revision, "admin")
        val id = current.draft.entries.single().achievementId
        val archives = AchievementRepository(mongo)
        val accounts = mockk<SubAccountRepository>()
        val owners = listOf(
            SubAccount(id = "a", userId = "owner", accountId = "a", name = "甲", game = game),
            SubAccount(id = "b", userId = "owner", accountId = "b", name = "乙", game = game),
        )
        every { accounts.findByUserIdAndAccountId("owner", any()) } answers { owners.find { it.accountId == secondArg<String>() } }
        every { accounts.fenceRecruitmentWrite(any(), any(), any()) } returns true
        mongo.createCollection(AchievementArchive::class.java)
        val original = archives.save(
            AchievementArchive(
                "archive-a",
                "owner",
                "a",
                game,
                listOf(
                    AchievementRecord(
                        id,
                        status = "completed", favorite = true, note = "个人备注", progress = 3.0, completedOn = LocalDate.parse("2026-10-17"),
                        updatedAt = Instant.parse("2026-10-17T00:00:00Z"), revision = 1, source = "manual",
                    ),
                ),
            ),
        )
        val personal =
            AchievementService(
                archives,
                accounts,
                publicCatalog,
                mapper,
                AchievementImportPreviews(),
                TransactionTemplate(MongoTransactionManager(factory)),
            )
        val backup = personal.export("owner", "a")
        val request = AchievementImportRequest("b", mapper.valueToTree(backup), "keep_current", false)
        val preview = personal.preview("owner", request)
        val beforeVersion = current.published.version
        current = admin.save(game, current.revision, current.draft.copy(entries = current.draft.entries.map { it.copy(name = "更名后") }))
        current = admin.publish(game, current.revision, "admin-b")
        assertEquals(original, archives.find("owner", "a"))
        assertEquals(id, publicCatalog.catalog(game).entries.single().achievementId)
        assertThrows(InventoryApiException::class.java) {
            personal.confirm(
                "owner",
                request.copy(
                    expectedRevision = preview.expectedRevision,
                    catalogVersion = preview.catalogVersion,
                    previewToken = preview.previewToken,
                ),
            )
        }
        val fresh = personal.preview("owner", request)
        assertEquals(
            1,
            personal.confirm(
                "owner",
                request.copy(
                    expectedRevision = fresh.expectedRevision,
                    catalogVersion = fresh.catalogVersion,
                    previewToken = fresh.previewToken,
                ),
            ).records.size,
        )
        current = admin.save(game, current.revision, current.draft.copy(retiredIds = setOf(id), dataStatus = "empty"))
        current = admin.publish(game, current.revision, "admin")
        assertTrue(publicCatalog.catalog(game).entries.isEmpty())
        assertEquals(backup.records, personal.export("owner", "a").records)
        assertEquals(original, archives.find("owner", "a"))
        val rollback = admin.preview(game, current.revision, beforeVersion)
        assertEquals(1, rollback.changes.count { it.kind == "add" })
        current = admin.publish(game, current.revision, "admin-c", beforeVersion)
        assertNotEquals(beforeVersion, current.published.version)
        assertEquals(beforeVersion, current.published.restoredFrom)
        assertEquals(original, archives.find("owner", "a"))
    }

    @Test
    fun `published progress rules protect all historical records and unsafe rollback`() {
        var current = ready()
        current = admin.publish(game, current.revision, "admin")
        val entry = current.draft.entries.single()
        val oldVersion = current.published.version
        for (changed in listOf(
            entry.copy(target = 5.0),
            entry.copy(unit = "个"),
            entry.copy(progressKind = "number"),
            entry.copy(progressKind = null, target = null, unit = null),
        )) {
            current = admin.save(game, current.revision, current.draft.copy(entries = listOf(changed)))
            assertTrue(admin.preview(game, current.revision).errors.any { it.contains("口径") })
            assertThrows(InventoryApiException::class.java) { admin.publish(game, current.revision, "admin") }
        }
        current = admin.save(game, current.revision, current.draft.copy(entries = listOf(entry.copy(target = 20.0))))
        assertEquals(1, admin.preview(game, current.revision).progressChanges.size)
        current = admin.publish(game, current.revision, "admin")
        assertThrows(InventoryApiException::class.java) { admin.publish(game, current.revision, "admin", oldVersion) }
        val dirty = admin.save(game, current.revision, current.draft.copy(coverage = "未发布变更"))
        assertThrows(InventoryApiException::class.java) { admin.preview(game, dirty.revision, oldVersion) }
    }

    @Test
    fun `import is preview bound additive default keep explicit update unverified and unknown ids unmatched`() {
        var current = ready()
        val original = current.draft.entries.single()
        val file = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(current.draft)
        val rows = file.withArray("entries")
        rows.removeAll()
        rows.add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(original.copy(name = "导入更名")))
        val newEntry = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(original.copy(name = "新增测试"))
        newEntry.remove("achievement_id")
        rows.add(newEntry)
        rows.add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(original.copy(achievementId = "yh_unknown_id")))
        var preview = admin.importPreview(game, current.revision, "admin", file, "keep")
        assertEquals(listOf("conflict", "add", "unmatched"), preview.items.map { it.outcome })
        assertThrows(InventoryApiException::class.java) {
            admin.importConfirm(game, current.revision, "other", file, "keep", preview.previewToken)
        }
        assertThrows(InventoryApiException::class.java) {
            admin.importConfirm(game, current.revision, "admin", file, "update", preview.previewToken)
        }
        val changedFile = file.deepCopy().put("coverage", "changed")
        assertThrows(InventoryApiException::class.java) {
            admin.importConfirm(game, current.revision, "admin", changedFile, "keep", preview.previewToken)
        }
        current = admin.importConfirm(game, current.revision, "admin", file, "keep", preview.previewToken)
        assertEquals(original, current.draft.entries.first())
        assertEquals("unverified", current.draft.entries.last().dataStatus)
        preview = admin.importPreview(game, current.revision, "admin", file, "update")
        current = admin.importConfirm(game, current.revision, "admin", file, "update", preview.previewToken)
        assertEquals("导入更名", current.draft.entries.first().name)
        assertEquals("unverified", current.draft.entries.first().dataStatus)
        assertEquals(3, current.draft.entries.size)
        assertTrue(publicCatalog.catalog(game).entries.isEmpty())
        assertTrue(admin.preview(game, current.revision).errors.isEmpty())
        val foreign = file.deepCopy().put("game", "如鸢")
        assertThrows(InventoryApiException::class.java) { admin.importPreview(game, current.revision, "admin", foreign, "keep") }
    }

    @Test
    fun `retired identities survive history eviction and cannot be reassigned`() {
        var current = ready()
        current = admin.publish(game, current.revision, "admin")
        val original = current.draft.entries.single()
        val originalVersion = current.published.version
        current = admin.save(game, current.revision, current.draft.copy(entries = emptyList(), dataStatus = "empty"))
        current = admin.publish(game, current.revision, "admin")
        repeat(6) { current = admin.publish(game, current.revision, "admin") }
        assertEquals(5, current.history.size)
        assertTrue(repository.find(game)!!.identities.containsKey(original.achievementId))
        assertThrows(InventoryApiException::class.java) { admin.preview(game, current.revision, originalVersion) }
        assertThrows(InventoryApiException::class.java) {
            admin.save(game, current.revision, current.draft.copy(entries = listOf(original.copy(name = "另一成就"))))
        }
        val added = admin.addEntry(game, current.revision, current.draft.categories.single().categoryId)
        assertNotEquals(original.achievementId, added.draft.entries.single().achievementId)
    }

    @Test
    fun `illegal bulk import rejects whole batch and leaves existing draft unchanged`() {
        val current = ready()
        val file = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(current.draft)
        val rows = file.withArray("entries")
        rows.add(rows[0].deepCopy<com.fasterxml.jackson.databind.JsonNode>())
        val preview = admin.importPreview(game, current.revision, "admin", file, "update")
        assertTrue(preview.items.any { it.outcome == "invalid" })
        assertThrows(InventoryApiException::class.java) {
            admin.importConfirm(game, current.revision, "admin", file, "update", preview.previewToken)
        }
        assertEquals(current, admin.read(game))
    }
}
