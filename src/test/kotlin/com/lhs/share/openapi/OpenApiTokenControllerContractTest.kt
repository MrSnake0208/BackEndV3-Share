package com.lhs.share.openapi

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.handler.OpenApiTokenExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.web.server.ResponseStatusException

class OpenApiTokenControllerContractTest {
    private val tokenService = mockk<OpenApiTokenService>()
    private val helper = mockk<AuthenticationHelper>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        every { helper.requireUserId() } returns "u1"
        val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders
            .standaloneSetup(OpenApiTokenController(tokenService, helper))
            .setControllerAdvice(OpenApiTokenExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
    }

    @Test
    fun `invalid generation request returns HTTP 400`() {
        mockMvc.perform(
            post("/user/open-api/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"account_id":"main","scopes":[],"remark":null}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status_code").value(400))
    }

    @Test
    fun `token limit returns HTTP 429`() {
        every { tokenService.generate("u1", "main", listOf("inventory:read"), null) } throws
            ApiResultException(429, "token limit reached")

        mockMvc.perform(
            post("/user/open-api/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"account_id":"main","scopes":["inventory:read"],"remark":null}"""),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.status_code").value(429))
    }

    @Test
    fun `missing token id returns HTTP 404`() {
        every { tokenService.delete("u1", "missing") } throws ApiResultException(404, "token 不存在")

        mockMvc.perform(delete("/user/open-api/tokens/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status_code").value(404))
    }

    @Test
    fun `missing JWT returns HTTP 401`() {
        every { helper.requireUserId() } throws ResponseStatusException(HttpStatus.UNAUTHORIZED)

        mockMvc.perform(delete("/user/open-api/tokens/token-id"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status_code").value(401))
    }
}
