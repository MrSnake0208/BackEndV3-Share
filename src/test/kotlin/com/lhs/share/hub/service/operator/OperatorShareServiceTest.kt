package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorCombatDisplayMode
import com.lhs.share.hub.repository.entity.OperatorCombatStats
import com.lhs.share.hub.repository.entity.OperatorDisc
import com.lhs.share.hub.repository.entity.OperatorDiscLoadout
import com.lhs.share.hub.repository.entity.OperatorOddityValue
import com.lhs.share.hub.repository.entity.OperatorStarStone
import com.lhs.share.hub.repository.entity.SubAccount
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import java.time.Instant

class OperatorShareServiceTest {
    private val accountRepository = mockk<SubAccountRepository>()
    private val operatorService = mockk<OperatorService>()
    private val catalogService = mockk<OperatorCatalogService>()
    private val service = OperatorShareService(accountRepository, operatorService, catalogService)

    @Test
    fun `create is idempotent and regenerate replaces the old code`() {
        val withoutCode = account()
        every { accountRepository.findByUserIdAndAccountId("u1", "acc1") } returns withoutCode
        every { accountRepository.save(any()) } answers { firstArg<SubAccount>() }

        val created = service.create("u1", "acc1")
        assertTrue(created.active)
        assertTrue(created.shareCode!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))

        val current = account(created.shareCode)
        every { accountRepository.findByUserIdAndAccountId("u1", "acc1") } returns current
        val reused = service.create("u1", "acc1")
        val regenerated = service.regenerate("u1", "acc1")

        assertEquals(created.shareCode, reused.shareCode)
        assertNotEquals(created.shareCode, regenerated.shareCode)
        verify(exactly = 2) { accountRepository.save(any()) }
    }

    @Test
    fun `revoke is idempotent and account ownership is enforced`() {
        every { accountRepository.findByUserIdAndAccountId("u1", "acc1") } returns account("code")
        every { accountRepository.save(any()) } answers { firstArg<SubAccount>() }

        val revoked = service.revoke("u1", "acc1")
        assertEquals(false, revoked.active)
        assertEquals(null, revoked.shareCode)

        every { accountRepository.findByUserIdAndAccountId("u1", "missing") } returns null
        val error = assertThrows(OperatorApiException::class.java) { service.get("u1", "missing") }
        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals("account_not_found", error.code)
    }

    @Test
    fun `public view filters un recruited entries and omits private current fields`() {
        every { accountRepository.findByShareToken("code") } returns account("code")
        every { catalogService.currentCatalogVersion() } returns "2026-09-03"
        every { operatorService.current("u1", "acc1", "代号鸢") } returns listOf(
            OperatorCurrentResponse(
                userId = "u1",
                accountId = "acc1",
                game = "代号鸢",
                fullBaselineAt = Instant.EPOCH,
                entries = mapOf(
                    "unrecruited" to entry(starLevel = 0),
                    "recruited" to entry(starLevel = 27),
                ),
                updatedAt = Instant.parse("2026-09-03T08:00:00Z"),
            ),
        )

        val view = service.view("code")

        assertEquals("代号鸢", view.game)
        assertEquals("2026-09-03", view.catalogVersion)
        assertEquals(setOf("recruited"), view.entries.keys)
        assertEquals(27, view.entries.getValue("recruited").starLevel)
        val json = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .writeValueAsString(view)
        listOf(
            "user_id",
            "account_id",
            "revision",
            "listed_baseline_at",
            "source",
            "observed_at",
            "combat_input_signature",
            "display_mode",
        ).forEach { field -> assertTrue("$field must stay private" !in json) }
        verify { operatorService.current("u1", "acc1", "代号鸢") }
    }

    @Test
    fun `public view returns an empty response without current data`() {
        every { accountRepository.findByShareToken("code") } returns account("code")
        every { catalogService.currentCatalogVersion() } returns "2026-09-03"
        every { operatorService.current("u1", "acc1", "代号鸢") } returns emptyList()

        val view = service.view("code")

        assertTrue(view.entries.isEmpty())
        assertEquals(null, view.updatedAt)
    }

    @Test
    fun `invalid public code has one stable error and never exposes account data`() {
        every { accountRepository.findByShareToken("bad") } returns null

        val error = assertThrows(OperatorApiException::class.java) { service.view("bad") }

        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals("share_not_found", error.code)
        assertTrue(error.message!!.contains("not found"))
        verify(exactly = 0) { operatorService.current(any(), any(), any()) }
    }

    @Test
    fun `share code collisions stop after a bounded number of attempts`() {
        val account = account()
        every { accountRepository.findByUserIdAndAccountId("u1", "acc1") } returns account
        every { accountRepository.save(any()) } throws DuplicateKeyException("collision")

        val error = assertThrows(OperatorApiException::class.java) { service.create("u1", "acc1") }

        assertEquals(HttpStatus.CONFLICT, error.status)
        assertEquals("share_code_generation_failed", error.code)
        verify(exactly = 3) { accountRepository.save(any()) }
    }

    @Test
    fun `regenerate and revoke invalidate codes without crossing accounts`() {
        val accounts = mutableMapOf(
            "acc1" to account(),
            "acc2" to account("other", accountId = "acc2"),
        )
        every { accountRepository.findByUserIdAndAccountId(any(), any()) } answers { accounts[secondArg()] }
        every { accountRepository.save(any()) } answers {
            firstArg<SubAccount>().also { accounts[it.accountId] = it }
        }
        every { accountRepository.findByShareToken(any()) } answers {
            val code = firstArg<String>()
            accounts.values.firstOrNull { it.shareToken == code }
        }
        every { catalogService.currentCatalogVersion() } returns "2026-09-03"
        every { operatorService.current(any(), any(), any()) } returns emptyList()

        val oldCode = service.create("u1", "acc1").shareCode!!
        val newCode = service.regenerate("u1", "acc1").shareCode!!
        val otherCode = service.create("u1", "acc2").shareCode!!

        assertNotEquals(oldCode, newCode)
        assertNotEquals(newCode, otherCode)
        assertThrows(OperatorApiException::class.java) { service.view(oldCode) }
        service.view(otherCode)
        verify { operatorService.current("u1", "acc2", "代号鸢") }

        service.revoke("u1", "acc2")
        assertThrows(OperatorApiException::class.java) { service.view(otherCode) }
    }

    private fun account(shareToken: String? = null, accountId: String = "acc1") = SubAccount(
        id = "mongo-id",
        userId = "u1",
        accountId = accountId,
        name = "大号",
        game = "代号鸢",
        shareToken = shareToken,
    )

    private fun entry(starLevel: Int) = OperatorCurrentEntryDto(
        elite = 17,
        starLevel = starLevel,
        level = 90,
        discs = listOf(OperatorDisc("技能增伤")),
        starStones = listOf(OperatorStarStone("攻击力", "main1", 60)),
        discLoadouts = listOf(OperatorDiscLoadout("disc_1", "命盘一")),
        combatStats = OperatorCombatStats(
            observedAttack = 8186,
            observedHp = 28704,
            manualAttack = null,
            manualHp = null,
            source = "scan",
            observedAt = Instant.EPOCH,
            observedStatus = "valid",
            combatInputSignature = "private-signature",
            displayMode = OperatorCombatDisplayMode("auto", "manual"),
            oddities = mapOf("attack" to OperatorOddityValue(500)),
        ),
        revision = 8,
        listedBaselineAt = Instant.EPOCH,
        updatedAt = Instant.parse("2026-09-03T08:00:00Z"),
    )
}
