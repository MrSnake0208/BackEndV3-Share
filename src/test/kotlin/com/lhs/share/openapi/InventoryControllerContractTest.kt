package com.lhs.share.openapi

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.inventory.InventoryController
import com.lhs.share.hub.controller.inventory.response.InventoryImportResult
import com.lhs.share.hub.service.inventory.EntityCatalogService
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.hub.service.inventory.InventoryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean

class InventoryControllerContractTest {
    private val inventoryService = mockk<InventoryService>()
    private val tokenService = mockk<OpenApiTokenService>()
    private val catalogService = mockk<EntityCatalogService>()
    private val helper = mockk<AuthenticationHelper>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                InventoryController(inventoryService, catalogService, helper),
                OpenApiInventoryController(tokenService, inventoryService),
            )
            .setControllerAdvice(InventoryExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
    }

    @Test
    fun `API token with inventory write can import`() {
        every { tokenService.validateAuthorization("Bearer write-token", OpenApiPermission.INVENTORY_WRITE) } returns "token-user"
        every { inventoryService.import("token-user", any()) } returns InventoryImportResult(accepted = 1)

        mockMvc.perform(
            post("/open-api/inventory/import")
                .header("Authorization", "Bearer write-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDocument),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(1))

        verify { inventoryService.import("token-user", match { it.records.single().recordId == "test:1" }) }
    }

    @Test
    fun `missing invalid and under-scoped API tokens return 401 or 403`() {
        every { tokenService.validateAuthorization(null, OpenApiPermission.INVENTORY_WRITE) } throws
            InventoryApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "API token is missing")
        every { tokenService.validateAuthorization("Bearer invalid", OpenApiPermission.INVENTORY_WRITE) } throws
            InventoryApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "API token is invalid")
        every { tokenService.validateAuthorization("Bearer read-only", OpenApiPermission.INVENTORY_WRITE) } throws
            InventoryApiException(HttpStatus.FORBIDDEN, "forbidden", "API token lacks the required scope")

        mockMvc.perform(post("/open-api/inventory/import").contentType(MediaType.APPLICATION_JSON).content(validDocument))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("unauthorized"))
        mockMvc.perform(
            post("/open-api/inventory/import")
                .header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDocument),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("unauthorized"))
        mockMvc.perform(
            post("/open-api/inventory/import")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDocument),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("forbidden"))
    }

    @Test
    fun `JWT inventory import remains available`() {
        every { helper.requireUserId() } returns "jwt-user"
        every { inventoryService.import("jwt-user", any()) } returns InventoryImportResult(accepted = 1)

        mockMvc.perform(post("/v1/inventory/import").contentType(MediaType.APPLICATION_JSON).content(validDocument))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(1))

        verify { inventoryService.import("jwt-user", any()) }
    }

    @Test
    fun `malformed JSON and schema violations use inventory errors`() {
        mockMvc.perform(post("/v1/inventory/import").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("invalid_json"))

        mockMvc.perform(
            post("/v1/inventory/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDocument.replace("\"baijinbi\"", "\"\"")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))

        mockMvc.perform(
            post("/v1/inventory/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDocument.replace("\"version\": 1,", "\"version\": 1, \"user_id\": \"other\",")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))
    }

    private val validDocument =
        """
        {
          "format": "myshare-inventory-exchange",
          "version": 1,
          "exported_at": "2026-08-16T10:00:00Z",
          "producer": { "platform": "test" },
          "records": [{
            "record_id": "test:1",
            "record_type": "reward_delta",
            "entity_type": "item",
            "effective_at": "2026-08-16T10:00:00Z",
            "entries": [{ "id": "baijinbi", "count": 1 }]
          }]
        }
        """.trimIndent()
}
