package com.lhs.share.openapi

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.inventory.InventoryController
import com.lhs.share.hub.controller.inventory.response.InventoryAccountResponse
import com.lhs.share.hub.controller.inventory.response.InventoryAgentFavoriteListResponse
import com.lhs.share.hub.controller.inventory.response.InventoryAgentFavoriteResponse
import com.lhs.share.hub.controller.inventory.response.InventoryImportResult
import com.lhs.share.hub.repository.entity.InventoryAccount
import com.lhs.share.hub.service.inventory.EntityCatalogService
import com.lhs.share.hub.service.inventory.InventoryAccountService
import com.lhs.share.hub.service.inventory.InventoryAgentFavoriteService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import java.time.Instant

class InventoryControllerContractTest {
    private val inventoryService = mockk<InventoryService>()
    private val accountService = mockk<InventoryAccountService>()
    private val favoriteService = mockk<InventoryAgentFavoriteService>()
    private val tokenService = mockk<OpenApiTokenService>()
    private val catalogService = mockk<EntityCatalogService>()
    private val helper = mockk<AuthenticationHelper>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                InventoryController(inventoryService, accountService, favoriteService, catalogService, helper),
                OpenApiInventoryController(tokenService, inventoryService, accountService),
            )
            .setControllerAdvice(InventoryExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
    }

    @Test
    fun `API token with inventory write can import`() {
        every { tokenService.validateAuthorization("Bearer write-token", OpenApiPermission.INVENTORY_WRITE) } returns
            OpenApiPrincipal("token-user", "main")
        every { inventoryService.import("token-user", "main", any()) } returns InventoryImportResult(accepted = 1)

        mockMvc.perform(
            post("/open-api/inventory/import")
                .header("Authorization", "Bearer write-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDocument),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(1))

        verify { inventoryService.import("token-user", "main", match { it.records.single().recordId == "test:1" }) }
    }

    @Test
    fun `API token exposes its bound account without an account selector`() {
        every { tokenService.authenticateAuthorization("Bearer account-token") } returns OpenApiPrincipal("token-user", "main")
        every { accountService.requireAccount("token-user", "main") } returns
            InventoryAccount(id = "a1", userId = "token-user", accountId = "main", name = "大号")

        mockMvc.perform(
            get("/open-api/inventory/account")
                .header("Authorization", "Bearer account-token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value("main"))
            .andExpect(jsonPath("$.data.name").value("大号"))
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
    fun `JWT account CRUD uses the authenticated owner`() {
        val account = InventoryAccountResponse("main", "大号", Instant.EPOCH, Instant.EPOCH)
        every { helper.requireUserId() } returns "jwt-user"
        every { accountService.create("jwt-user", "大号") } returns account
        every { accountService.list("jwt-user") } returns listOf(account)
        every { accountService.rename("jwt-user", "main", "改名") } returns account.copy(name = "改名")
        every { accountService.delete("jwt-user", "main") } returns Unit

        mockMvc.perform(post("/v1/inventory/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"大号\"}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value("main"))
        mockMvc.perform(get("/v1/inventory/accounts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].name").value("大号"))
        mockMvc.perform(
            patch("/v1/inventory/accounts/main").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"改名\"}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("改名"))
        mockMvc.perform(delete("/v1/inventory/accounts/main"))
            .andExpect(status().isOk)

        verify { accountService.create("jwt-user", "大号") }
        verify { accountService.list("jwt-user") }
        verify { accountService.rename("jwt-user", "main", "改名") }
        verify { accountService.delete("jwt-user", "main") }
    }

    @Test
    fun `JWT agent favorite endpoints use snake case responses and authenticated owner`() {
        every { helper.requireUserId() } returns "jwt-user"
        every { favoriteService.list("jwt-user", "acc_a") } returns
            InventoryAgentFavoriteListResponse("acc_a", listOf("char_038_luxun", "char_102_jianyong"))
        every { favoriteService.add("jwt-user", "acc_a", "char_102_jianyong") } returns
            InventoryAgentFavoriteResponse("acc_a", "char_102_jianyong", true)
        every { favoriteService.remove("jwt-user", "acc_a", "char_102_jianyong") } returns
            InventoryAgentFavoriteResponse("acc_a", "char_102_jianyong", false)

        mockMvc.perform(get("/v1/inventory/agent-favorites").param("account_id", "acc_a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.account_id").value("acc_a"))
            .andExpect(jsonPath("$.data.agent_ids[0]").value("char_038_luxun"))
        mockMvc.perform(put("/v1/inventory/agent-favorites/char_102_jianyong").param("account_id", "acc_a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.agent_id").value("char_102_jianyong"))
            .andExpect(jsonPath("$.data.favorite").value(true))
        mockMvc.perform(delete("/v1/inventory/agent-favorites/char_102_jianyong").param("account_id", "acc_a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.favorite").value(false))

        verify { favoriteService.list("jwt-user", "acc_a") }
        verify { favoriteService.add("jwt-user", "acc_a", "char_102_jianyong") }
        verify { favoriteService.remove("jwt-user", "acc_a", "char_102_jianyong") }
        verify(exactly = 0) { inventoryService.current(any(), any(), any()) }
        verify(exactly = 0) { inventoryService.acquired(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { inventoryService.listRecords(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { inventoryService.export(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `agent inventory ignores forged catalog metadata fields`() {
        every { helper.requireUserId() } returns "jwt-user"
        every { inventoryService.import("jwt-user", any()) } returns InventoryImportResult(accepted = 1)
        val forgedDocument =
            """
            {
              "format": "myshare-inventory-exchange",
              "version": 2,
              "exported_at": "2026-08-16T10:00:00Z",
              "producer": { "platform": "test" },
              "records": [{
                "account_id": "acc_a",
                "record_id": "agent:forged",
                "record_type": "stock_snapshot",
                "entity_type": "agent",
                "effective_at": "2026-08-16T10:00:00Z",
                "snapshot_scope": "full",
                "entries": [{
                  "id": "char_102_jianyong",
                  "name": "伪造名称",
                  "count": 12,
                  "rarity": 1,
                  "prof": "伪造属性",
                  "sub_prof": "伪造职业"
                }]
              }]
            }
            """.trimIndent()

        mockMvc.perform(post("/v1/inventory/import").contentType(MediaType.APPLICATION_JSON).content(forgedDocument))
            .andExpect(status().isOk)

        verify {
            inventoryService.import(
                "jwt-user",
                match { request ->
                    request.records.single().entries.single().let { entry ->
                        entry.id == "char_102_jianyong" && entry.name == "伪造名称" && entry.count == 12L
                    }
                },
            )
        }
        verify(exactly = 0) { catalogService.catalog() }
    }

    @Test
    fun `malformed JSON and schema violations use inventory errors`() {
        val missingAccountId = jacksonObjectMapper().readTree(validDocument)
        (missingAccountId["records"][0] as com.fasterxml.jackson.databind.node.ObjectNode).remove("account_id")

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
                .content(missingAccountId.toString()),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))

        verify(exactly = 0) { inventoryService.import(any(), any()) }

        mockMvc.perform(
            post("/v1/inventory/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDocument.replace("\"version\": 2,", "\"version\": 2, \"user_id\": \"other\",")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))
    }

    private val validDocument =
        """
        {
          "format": "myshare-inventory-exchange",
          "version": 2,
          "exported_at": "2026-08-16T10:00:00Z",
          "producer": { "platform": "test" },
          "records": [{
            "account_id": "main",
            "record_id": "test:1",
            "record_type": "reward_delta",
            "entity_type": "item",
            "effective_at": "2026-08-16T10:00:00Z",
            "entries": [{ "id": "baijinbi", "count": 1 }]
          }]
        }
        """.trimIndent()
}
