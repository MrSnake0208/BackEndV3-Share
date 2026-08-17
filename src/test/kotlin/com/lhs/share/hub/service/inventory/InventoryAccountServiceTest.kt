package com.lhs.share.hub.service.inventory

import com.lhs.share.hub.repository.InventoryAccountRepository
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.entity.InventoryAccount
import com.lhs.share.openapi.OpenApiTokenService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class InventoryAccountServiceTest {
    private val accountRepository = mockk<InventoryAccountRepository>()
    private val currentRepository = mockk<InventoryCurrentRepository>()
    private val recordRepository = mockk<InventoryRecordRepository>()
    private val tokenService = mockk<OpenApiTokenService>()
    private val transactionTemplate = TransactionTemplate(
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
            override fun commit(status: TransactionStatus) = Unit
            override fun rollback(status: TransactionStatus) = Unit
        },
    )
    private val service = InventoryAccountService(accountRepository, currentRepository, recordRepository, tokenService, transactionTemplate)

    @Test
    fun `create list and rename preserve stable account id`() {
        every { accountRepository.countByUserId("u1") } returns 0
        every { accountRepository.save(any()) } answers {
            val account = firstArg<InventoryAccount>()
            if (account.id == null) account.copy(id = "mongo-id") else account
        }
        every { accountRepository.findAllByUserIdOrderByCreatedAtAsc("u1") } answers {
            listOf(InventoryAccount(id = "mongo-id", userId = "u1", accountId = "main", name = "大号"))
        }
        every { accountRepository.findByUserIdAndAccountId("u1", any()) } answers {
            InventoryAccount(id = "mongo-id", userId = "u1", accountId = secondArg(), name = "大号")
        }

        val created = service.create("u1", "新账号")
        val listed = service.list("u1")
        val renamed = service.rename("u1", created.id, "改名")

        assertTrue(created.id.matches(Regex("^acc_[0-9a-f]{32}$")))
        assertEquals("main", listed.single().id)
        assertEquals(created.id, renamed.id)
        assertEquals("改名", renamed.name)
        verify(exactly = 0) { currentRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { recordRepository.deleteAllByUserIdAndAccountId(any(), any()) }
    }

    @Test
    fun `foreign account is hidden and duplicate name returns conflict`() {
        every { accountRepository.findByUserIdAndAccountId("u1", "foreign") } returns null
        every { accountRepository.countByUserId("u1") } returns 0
        every { accountRepository.save(any()) } throws DuplicateKeyException("duplicate")

        val missing = assertThrows(InventoryApiException::class.java) { service.rename("u1", "foreign", "名称") }
        val conflict = assertThrows(InventoryApiException::class.java) { service.create("u1", "重复") }

        assertEquals(404, missing.status.value())
        assertEquals("account_not_found", missing.code)
        assertEquals(409, conflict.status.value())
        assertEquals("account_name_conflict", conflict.code)
    }

    @Test
    fun `eleventh account is rejected`() {
        every { accountRepository.countByUserId("u1") } returns 10

        val error = assertThrows(InventoryApiException::class.java) { service.create("u1", "超额账号") }

        assertEquals(409, error.status.value())
        assertEquals("account_limit_reached", error.code)
        verify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `delete cascades only the owned account`() {
        every { accountRepository.findByUserIdAndAccountId("u1", "main") } returns
            InventoryAccount(id = "mongo-id", userId = "u1", accountId = "main", name = "大号")
        every { currentRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { recordRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { tokenService.revokeByAccount("u1", "main") } just runs
        every { accountRepository.deleteById("mongo-id") } just runs

        service.delete("u1", "main")

        verify(exactly = 1) { currentRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { recordRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { tokenService.revokeByAccount("u1", "main") }
        verify(exactly = 1) { accountRepository.deleteById("mongo-id") }
    }
}
