package com.lhs.share.openapi

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.star.StarInventoryController
import com.lhs.share.hub.controller.star.response.StarInventoryEntryResponse
import com.lhs.share.hub.controller.star.response.StarInventorySnapshotResponse
import com.lhs.share.hub.service.star.StarInventoryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean

class StarInventoryControllerContractTest {
    private val service = mockk<StarInventoryService>()
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
            .standaloneSetup(StarInventoryController(service, helper))
            .setControllerAdvice(InventoryExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
        every { helper.requireUserId() } returns "jwt-user"
    }

    @Test
    fun `GET returns ApiResult and empty snapshot is still HTTP 200`() {
        every { service.current("jwt-user", "acc_a") } returns StarInventorySnapshotResponse.empty("acc_a")

        mockMvc.perform(get("/v1/star-inventory/current").param("account_id", "acc_a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status_code").value(200))
            .andExpect(jsonPath("$.data.account_id").value("acc_a"))
            .andExpect(jsonPath("$.data.entries").isArray)
            .andExpect(jsonPath("$.data.entries").isEmpty)
            .andExpect(jsonPath("$.data.effective_at").value(null as String?))
            .andExpect(jsonPath("$.data.revision").value(null as String?))
            .andExpect(jsonPath("$.data.updated_at").value(null as String?))

        verify { service.current("jwt-user", "acc_a") }
    }

    @Test
    fun `PUT accepts the frontend body and returns only public snapshot fields`() {
        every { service.putCurrent("jwt-user", "acc_a", any()) } returns response()

        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status_code").value(200))
            .andExpect(jsonPath("$.data.account_id").value("acc_a"))
            .andExpect(jsonPath("$.data.entries[0].instance_id").value("main-1"))
            .andExpect(jsonPath("$.data.entries[0].name").value("天府"))
            .andExpect(jsonPath("$.data.revision").value(1))
            .andExpect(jsonPath("$.data.user_id").doesNotExist())
            .andExpect(jsonPath("$.data.content_hash").doesNotExist())
            .andExpect(jsonPath("$.data.entries[0].target_level").doesNotExist())

        verify {
            service.putCurrent(
                "jwt-user",
                "acc_a",
                match {
                    it.effectiveAt == "2026-08-31T10:00:00.000Z" &&
                        it.entries.single().instanceId == "main-1" &&
                        it.entries.single().level == 60
                },
            )
        }
    }

    @Test
    fun `missing account and invalid snapshot use stable errors`() {
        mockMvc.perform(get("/v1/star-inventory/current"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))

        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody.replace("\"orange\"", "\"red\"")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_inventory_invalid_snapshot"))

        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody.replace("\"level\": 60", "\"level\": 60, \"targetLevel\": 60")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_inventory_invalid_snapshot"))

        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody.replace("\"level\": 60", "\"level\": 0")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_inventory_invalid_snapshot"))

        verify(exactly = 0) { service.putCurrent(any(), any(), any()) }
    }

    @Test
    fun `missing entries and entry level are rejected at the HTTP boundary`() {
        val withoutEntries = """
            {"effective_at":"2026-08-31T10:00:00.000Z"}
        """.trimIndent()
        val withoutLevel = """
            {
              "effective_at": "2026-08-31T10:00:00.000Z",
              "entries": [{
                "instance_id": "main-1",
                "kind": "main",
                "name": "天府",
                "quality": "orange"
              }]
            }
        """.trimIndent()

        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(withoutEntries),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_inventory_invalid_snapshot"))
        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(withoutLevel),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_inventory_invalid_snapshot"))

        verify(exactly = 0) { service.putCurrent(any(), any(), any()) }
    }

    private fun response() = StarInventorySnapshotResponse(
        accountId = "acc_a",
        effectiveAt = java.time.Instant.parse("2026-08-31T10:00:00Z"),
        entries = listOf(StarInventoryEntryResponse("main-1", "main", "天府", "orange", 60)),
        revision = 1,
        updatedAt = java.time.Instant.parse("2026-08-31T10:00:02Z"),
    )

    private val validBody =
        """
        {
          "effective_at": "2026-08-31T10:00:00.000Z",
          "entries": [{
            "instance_id": "main-1",
            "kind": "main",
            "name": "天府",
            "quality": "orange",
            "level": 60
          }]
        }
        """.trimIndent()
}
