package com.lhs.share.openapi

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.account.AccountController
import com.lhs.share.hub.controller.account.response.SubAccountResponse
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import java.time.Instant

class AccountControllerContractTest {
    private val accountService = mockk<SubAccountService>()
    private val helper = mockk<AuthenticationHelper>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders
            .standaloneSetup(AccountController(accountService, helper))
            .setControllerAdvice(InventoryExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
    }

    @Test
    fun `JWT account CRUD uses the authenticated owner on the unified endpoint`() {
        val account = SubAccountResponse("main", "大号", Instant.EPOCH, Instant.EPOCH)
        every { helper.requireUserId() } returns "jwt-user"
        every { accountService.create("jwt-user", "大号") } returns account
        every { accountService.list("jwt-user") } returns listOf(account)
        every { accountService.rename("jwt-user", "main", "改名") } returns account.copy(name = "改名")
        every { accountService.delete("jwt-user", "main") } returns Unit

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"大号\"}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value("main"))
        mockMvc.perform(get("/v1/accounts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].name").value("大号"))
        mockMvc.perform(
            patch("/v1/accounts/main").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"改名\"}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("改名"))
        mockMvc.perform(delete("/v1/accounts/main"))
            .andExpect(status().isOk)

        verify { accountService.create("jwt-user", "大号") }
        verify { accountService.list("jwt-user") }
        verify { accountService.rename("jwt-user", "main", "改名") }
        verify { accountService.delete("jwt-user", "main") }
    }

    @Test
    fun `account errors use the inventory error shape`() {
        every { helper.requireUserId() } returns "jwt-user"
        every { accountService.delete("jwt-user", "missing") } throws
            InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

        mockMvc.perform(delete("/v1/accounts/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("account_not_found"))
    }

    @Test
    fun `create requires a non-blank name`() {
        every { helper.requireUserId() } returns "jwt-user"

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))
        verify(exactly = 0) { accountService.create(any(), any()) }
    }

    @Test
    fun `account limit errors surface as conflict`() {
        every { helper.requireUserId() } returns "jwt-user"
        every { accountService.create("jwt-user", "超额") } throws
            InventoryApiException(HttpStatus.CONFLICT, "account_limit_reached", "Account limit reached")

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"超额\"}"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("account_limit_reached"))
    }
}
