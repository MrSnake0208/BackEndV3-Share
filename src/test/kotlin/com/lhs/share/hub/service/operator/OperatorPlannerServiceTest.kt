package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.repository.OperatorAnnotationRepository
import com.lhs.share.hub.repository.OperatorPlannerImportRepository
import com.lhs.share.hub.repository.OperatorStaminaScheduleRepository
import com.lhs.share.hub.repository.OperatorTrainingWorkspaceRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorPlannerImport
import com.lhs.share.hub.repository.entity.OperatorStaminaSchedule
import com.lhs.share.hub.repository.entity.OperatorTrainingWorkspace
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.inventory.EntityCatalogService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class OperatorPlannerServiceTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val accounts = mockk<SubAccountRepository>()
    private val workspaces = mockk<OperatorTrainingWorkspaceRepository>()
    private val schedules = mockk<OperatorStaminaScheduleRepository>()
    private val imports = mockk<OperatorPlannerImportRepository>()
    private val annotations = mockk<OperatorAnnotationRepository>()
    private val subjective = mockk<OperatorSubjectiveService>()
    private val events = mockk<AccountEventService>(relaxed = true)
    private val catalog = mockk<OperatorCatalogService>()
    private val items = mockk<EntityCatalogService>()
    private val validator = OperatorPlannerValidator(catalog, items, mapper)
    private val workspaceData = mutableMapOf<String, OperatorTrainingWorkspace>()
    private val scheduleData = mutableMapOf<String, OperatorStaminaSchedule>()
    private val receiptData = mutableMapOf<String, OperatorPlannerImport>()
    private var rollbacks = 0
    private val manager = object : PlatformTransactionManager {
        private var oldWorkspace = emptyMap<String, OperatorTrainingWorkspace>()
        private var oldSchedules = emptyMap<String, OperatorStaminaSchedule>()
        private var oldReceipts = emptyMap<String, OperatorPlannerImport>()
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            oldWorkspace = workspaceData.toMap()
            oldSchedules = scheduleData.toMap()
            oldReceipts = receiptData.toMap()
            return SimpleTransactionStatus()
        }
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) {
            rollbacks++
            workspaceData.clear()
            workspaceData.putAll(oldWorkspace)
            scheduleData.clear()
            scheduleData.putAll(oldSchedules)
            receiptData.clear()
            receiptData.putAll(oldReceipts)
        }
    }
    private val service =
        OperatorPlannerService(
            accounts, workspaces, schedules, imports, annotations, subjective, validator, mapper, events,
            TransactionTemplate(
                manager,
            ),
        )

    @BeforeEach
    fun setup() {
        every { accounts.findByUserIdAndAccountId(any(), any()) } answers {
            if (firstArg<String>() == "u1" && secondArg<String>() in setOf("a1", "a2")) {
                SubAccount(userId = "u1", accountId = secondArg(), name = "账号")
            } else {
                null
            }
        }
        every { catalog.getOperator(any()) } answers {
            if (firstArg<String>() in setOf(OPERATOR, "char_002_ayan")) {
                OperatorCatalogEntity(
                    operatorId = firstArg(), name = "密探", rarity = 5, prof = listOf("阳"), subProf = emptyList(),
                    games = listOf("如鸢"), discs = emptyList(), starStones = emptyList(), catalogVersion = "1",
                )
            } else {
                null
            }
        }
        every { items.exists("item", any()) } returns true
        every { workspaces.findByUserIdAndAccountId(any(), any()) } answers
            { workspaceData["${firstArg<String>()}:${secondArg<String>()}"] }
        every { workspaces.replace(any(), any(), any(), any(), any()) } answers {
            val key = "${arg<String>(0)}:${arg<String>(1)}"
            synchronized(workspaceData) {
                if ((workspaceData[key]?.revision ?: 0L) != arg<Long>(2)) {
                    null
                } else {
                    OperatorTrainingWorkspace(key, arg(0), arg(1), arg(3), arg<Long>(2) + 1, arg(4)).also { workspaceData[key] = it }
                }
            }
        }
        every { schedules.findByUserIdAndAccountIdAndPlanId(any(), any(), any()) } answers
            { scheduleData["${arg<String>(0)}:${arg<String>(1)}:${arg<String>(2)}"] }
        every { schedules.replace(any(), any(), any(), any(), any(), any()) } answers {
            val key = "${arg<String>(0)}:${arg<String>(1)}:${arg<String>(2)}"
            if ((scheduleData[key]?.revision ?: 0L) != arg<Long>(3)) {
                null
            } else {
                OperatorStaminaSchedule(key, arg(0), arg(1), arg(2), arg(4), arg<Long>(3) + 1, arg(5)).also { scheduleData[key] = it }
            }
        }
        every { imports.findByUserIdAndAccountIdAndMigrationId(any(), any(), any()) } answers
            { receiptData["${arg<String>(0)}:${arg<String>(1)}:${arg<String>(2)}"] }
        every { imports.insert(any<OperatorPlannerImport>()) } answers
            { firstArg<OperatorPlannerImport>().also { receiptData[it.id] = it } }
    }

    @Test
    fun `empty reads do not create data and foreign accounts cannot read or write`() {
        assertEquals(0, service.workspace("u1", "a1").path("revision").asInt())
        assertEquals(0, service.schedule("u1", "a1", "favorites").path("revision").asInt())
        assertTrue(workspaceData.isEmpty())
        assertTrue(scheduleData.isEmpty())
        val foreign = assertThrows(OperatorApiException::class.java) { service.putWorkspace("u2", "a1", workspace()) }
        assertEquals(HttpStatus.NOT_FOUND, foreign.status)
        assertThrows(OperatorApiException::class.java) { service.schedule("u2", "a1", "favorites") }
    }

    @Test
    fun `plans have independent targets and compare-and-set rejects a stale whole workspace`() {
        val request = workspace()
        val plans = request.withArray("plans")
        plans.add(
            mapper.readTree(
                """{"id":"$PLAN","name":" 主队 ","source":"custom","operator_ids":["$OPERATOR"],"excluded_operator_ids":[],"targets":{"$OPERATOR":{"level":80,"elite":13,"star_level":19}}}""",
            ),
        )
        val saved = service.putWorkspace("u1", "a1", request)
        assertEquals("主队", saved.path("plans")[1].path("name").asText())
        assertEquals(80, saved.path("plans")[1].path("targets").path(OPERATOR).path("level").asInt())
        val stale = assertThrows(OperatorApiException::class.java) { service.putWorkspace("u1", "a1", request) }
        assertEquals(OperatorPlannerService.WORKSPACE_CONFLICT, stale.code)
        assertEquals(0, service.workspace("u1", "a2").path("revision").asInt())
        verify(exactly = 0) { subjective.putAnnotation(any(), any(), any(), any()) }
    }

    @Test
    fun `invalid structures never write a partial workspace`() {
        val invalidRequests = listOf(
            workspace().put("schema_version", 2),
            workspace().put("active_plan_id", PLAN),
            workspace().also { it.withArray("plans").removeAll() },
            workspace().also { (it.path("plans")[0] as ObjectNode).put("source", "custom") },
            workspace().also { (it.path("training_levels") as ObjectNode).put("fh", 13) },
            workspace().also { (it.path("plans")[0] as ObjectNode).withArray("operator_ids").add("unknown") },
        )
        invalidRequests.forEach { assertThrows(OperatorApiException::class.java) { service.putWorkspace("u1", "a1", it) } }
        assertTrue(workspaceData.isEmpty())
    }

    @Test
    fun `real frontend fixed schedule round trips and past predictions cannot change`() {
        val request = fixture()
        val saved = service.putSchedule("u1", "a1", "favorites", request)
        assertEquals(request.path("schedule"), saved.path("schedule"))
        assertEquals(request.path("manual_plans"), service.schedule("u1", "a1", "favorites").path("manual_plans"))
        val changed = request.deepCopy().put("expected_revision", 1)
        (changed.path("schedule").path("result").path("timeline")[0].path("totals") as ObjectNode).put("balance", 999)
        assertThrows(OperatorApiException::class.java) { service.putSchedule("u1", "a1", "favorites", changed) }
        assertEquals(1, service.schedule("u1", "a1", "favorites").path("revision").asInt())
        assertThrows(OperatorApiException::class.java) { service.putSchedule("u1", "a1", "favorites", request) }
    }

    @Test
    fun `infeasible editable drafts can persist without modifying stock or generating fake history`() {
        val request = validator.emptySchedule().put("expected_revision", 0)
        val day = fixture().path("manual_plans").path("2026-09-12").deepCopy<ObjectNode>()
        (day.path("spends")[0] as ObjectNode).put("value", 6.5)
        request.withObject("manual_plans").set<ObjectNode>("2026-09-12", day)
        val result = service.putSchedule("u1", "a1", "favorites", request)
        assertEquals(6.5, result.path("manual_plans").path("2026-09-12").path("spends")[0].path("value").asDouble())
        assertTrue(result.path("schedule").isNull)
    }

    @Test
    fun `remove changes only membership and graduation conflict rolls back the workspace`() {
        val initial = workspace()
        (initial.path("plans")[0] as ObjectNode).withArray("operator_ids").add(OPERATOR)
        service.putWorkspace("u1", "a1", initial)
        every { annotations.findByUserIdAndAccountIdAndOperatorId("u1", "a1", OPERATOR) } returns null
        val request = mapper.createObjectNode().put("expected_revision", 1).put("graduate", true).put("expected_annotation_revision", 3)
        val error = assertThrows(OperatorApiException::class.java) { service.removeMember("u1", "a1", "favorites", OPERATOR, request) }
        assertEquals("annotation_revision_conflict", error.code)
        assertEquals(1, rollbacks)
        assertEquals(1, service.workspace("u1", "a1").path("revision").asInt())
        val removed = service.removeMember("u1", "a1", "favorites", OPERATOR, request.put("graduate", false))
        assertTrue(removed.path("workspace").path("plans")[0].path("operator_ids").isEmpty)
        assertEquals(OPERATOR, removed.path("workspace").path("plans")[0].path("excluded_operator_ids")[0].asText())
        verify(exactly = 0) { subjective.putAnnotation(any(), any(), any(), any()) }
    }

    @Test
    fun `graduating removal preserves annotation note and returns both committed revisions`() {
        every { annotations.findByUserIdAndAccountIdAndOperatorId("u1", "a1", OPERATOR) } returns null
        every { subjective.putAnnotation("u1", "a1", OPERATOR, any()) } answers {
            com.lhs.share.hub.controller.operator.response.OperatorAnnotationResponse(
                OPERATOR,
                "graduated",
                "继续追踪心纸",
                1,
                java.time.Instant.now(),
            )
        }
        val request = mapper.createObjectNode().put("expected_revision", 0).put("graduate", true)
            .put("expected_annotation_revision", 0)
        val result = service.removeMember("u1", "a1", "favorites", OPERATOR, request)
        assertEquals(1, result.path("workspace").path("revision").asInt())
        assertEquals("graduated", result.path("annotation").path("growth_state").asText())
        assertEquals("继续追踪心纸", result.path("annotation").path("note").asText())
        verify {
            subjective.putAnnotation(
                "u1",
                "a1",
                OPERATOR,
                match {
                    it.path("growth_state").asText() == "graduated" && !it.has("note") && !it.has("favorite")
                },
            )
        }
    }

    @Test
    fun `local import commits schedules and receipt together and retries before revision checks`() {
        val request = mapper.createObjectNode().put("migration_id", "migration-1")
        request.set<ObjectNode>("workspace", workspace())
        request.set<ObjectNode>("schedules", mapper.createObjectNode().set<ObjectNode>("favorites", fixture()))
        val result = service.importLocal("u1", "a1", request)
        assertEquals(result.toString(), service.importLocal("u1", "a1", request).toString())
        assertEquals(1, workspaceData.size)
        assertEquals(1, scheduleData.size)
        assertEquals(1, receiptData.size)
        val conflict = request.deepCopy()
        conflict.withObject("workspace").put("expected_revision", 1)
        val error = assertThrows(OperatorApiException::class.java) { service.importLocal("u1", "a1", conflict) }
        assertEquals("training_workspace_migration_conflict", error.code)
    }

    @Test
    fun `schedule conflict during import rolls back workspace and creates no receipt`() {
        val request = mapper.createObjectNode().put("migration_id", "migration-failure")
        request.set<ObjectNode>("workspace", workspace())
        request.set<ObjectNode>("schedules", mapper.createObjectNode().set<ObjectNode>("favorites", fixture().put("expected_revision", 9)))
        assertThrows(OperatorApiException::class.java) { service.importLocal("u1", "a1", request) }
        assertTrue(workspaceData.isEmpty())
        assertTrue(receiptData.isEmpty())
        assertEquals(1, rollbacks)
    }

    @Test
    fun `deleting and restoring a plan retains its schedule but hides it while absent`() {
        val request = workspace()
        request.withArray(
            "plans",
        ).add(mapper.readTree("""{"id":"$PLAN","name":"主队","source":"custom","operator_ids":[],"excluded_operator_ids":[],"targets":{}}"""))
        service.putWorkspace("u1", "a1", request)
        service.putSchedule("u1", "a1", PLAN, validator.emptySchedule().put("expected_revision", 0))
        service.putWorkspace("u1", "a1", workspace().put("expected_revision", 1))
        assertThrows(OperatorApiException::class.java) { service.schedule("u1", "a1", PLAN) }
        service.putWorkspace("u1", "a1", request.put("expected_revision", 2))
        assertEquals(1, service.schedule("u1", "a1", PLAN).path("revision").asInt())
        assertNotNull(scheduleData["u1:a1:$PLAN"])
    }

    @Test
    fun `browser captured wire payload roundtrips without losing per day progress or changing quantities`() {
        val request = mapper.readTree(javaClass.getResourceAsStream("/operator-planner/browser-fixed-schedule.json")) as ObjectNode
        val saved = service.putSchedule("u1", "a1", "favorites", request)
        assertEquals(request.path("schedule"), saved.path("schedule"))
        assertEquals(request.path("manual_plans"), saved.path("manual_plans"))
        assertTrue(saved.path("schedule").path("result").path("timeline")[0].has("progress_rows"))
    }

    private fun workspace() = validator.emptyWorkspace().put("expected_revision", 0)
    private fun fixture() = mapper.readTree(javaClass.getResourceAsStream("/operator-planner/frontend-fixed-schedule.json")) as ObjectNode

    companion object {
        const val OPERATOR = "char_001_yangxiu"
        const val PLAN = "4d4e3763-2b56-4dda-9a3f-a79d4634e74b"
    }
}
