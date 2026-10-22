package com.lhs.share.openapi

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.GlobalExceptionHandler
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.inventory.MailboxReserveController
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.MailboxReserve
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.hub.service.inventory.MailboxReserveService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException

class MailboxReserveControllerContractTest {
    private val repository = mockk<SubAccountRepository>()
    private val accounts = mockk<SubAccountService>()
    private val helper = mockk<AuthenticationHelper>()
    private val account = SubAccount(userId = "owner", accountId = "a", name = "A")
    private val path = "/v1/inventory/mailbox-reserve"
    private val body = """{"expected_version":0,"white_coin_mails":[{"count":20,"coins_per_mail":90}],"fu_chuan_mail_count":10}"""
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        every { helper.requireUserId() } returns "owner"
        every { accounts.requireAccount("owner", "a") } returns account
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        mvc = MockMvcBuilders.standaloneSetup(MailboxReserveController(MailboxReserveService(accounts, repository), helper))
            .setControllerAdvice(InventoryExceptionHandler(), GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper)).build()
    }

    @Test
    fun `empty reads are private and pure and writes use authenticated owner`() {
        repeat(2) {
            mvc.perform(get(path).param("account_id", "a").param("user_id", "stranger"))
                .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.white_coin_mails").isEmpty)
                .andExpect(jsonPath("$.data.user_id").doesNotExist())
        }
        verify { repository wasNot io.mockk.Called }
        every { repository.replaceMailboxReserve("owner", "a", 0, any()) } answers {
            account.copy(mailboxReserve = lastArg<MailboxReserve>())
        }
        mvc.perform(put(path).param("account_id", "a").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.account_id").value("a"))
            .andExpect(jsonPath("$.data.white_coin_mails[0].coins_per_mail").value(90))
            .andExpect(jsonPath("$.data.fu_chuan_mail_count").value(10))
            .andExpect(jsonPath("$.data.version").value(1))
        verify(exactly = 1) { repository.replaceMailboxReserve("owner", "a", 0, any()) }
    }

    @Test
    fun `invalid numbers and fields never write`() {
        val invalidBodies = listOf(
            body.replace(":20", ":-1"), body.replace(":20", ":1.5"), body.replace(":20", ":1000001"),
            body.replace(":20", ":\"20\""), body.replace(":20", ":null"), body.replace(":90", ":2147483648"),
            body.replace("\"expected_version\":0", "\"expected_version\":9007199254740991"),
            body.replace("\"expected_version\":0", "\"expected_version\":-1"),
            body.replace("\"fu_chuan_mail_count\":10", "\"fu_chuan_mail_count\":0.5"),
            body.replace("\"count\":20", "\"count\":20,\"stamina\":60"),
            """{"expected_version":0,"white_coin_mails":[],"fu_chuan_mail_count":0,"user_id":"other"}""",
            """{"expected_version":0,"white_coin_mails":[],"fu_chuan_mail_count":1000001}""",
            """{"expected_version":0,"white_coin_mails":[${List(21) {
                """{"count":0,"coins_per_mail":90}"""
            }.joinToString(",")}],"fu_chuan_mail_count":0}""",
        )
        invalidBodies.forEach {
            mvc.perform(put(path).param("account_id", "a").contentType(MediaType.APPLICATION_JSON).content(it))
                .andExpect(status().isUnprocessableEntity)
        }
        verify { repository wasNot io.mockk.Called }
    }

    @Test
    fun `foreign or deleted account and unauthenticated requests cannot write`() {
        every { accounts.requireAccount("owner", "b") } throws InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "not found")
        mvc.perform(get(path).param("account_id", "b")).andExpect(status().isNotFound)
        mvc.perform(put(path).param("account_id", "b").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound)
        every { helper.requireUserId() } throws ResponseStatusException(HttpStatus.UNAUTHORIZED)
        mvc.perform(get(path).param("account_id", "a")).andExpect(status().isUnauthorized)
        mvc.perform(
            put(path).param("account_id", "a").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isUnauthorized)
        verify { repository wasNot io.mockk.Called }
    }

    @Test
    fun `stale version conflicts without reporting success`() {
        every { repository.replaceMailboxReserve("owner", "a", 0, any()) } returns null
        mvc.perform(put(path).param("account_id", "a").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("mailbox_reserve_conflict"))
    }
}
