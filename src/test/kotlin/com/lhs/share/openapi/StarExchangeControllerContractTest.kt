package com.lhs.share.openapi

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.star.StarExchangeController
import com.lhs.share.hub.controller.star.response.StarExchangeReplaceResponse
import com.lhs.share.hub.controller.star.response.StarInventorySnapshotResponse
import com.lhs.share.hub.controller.star.response.StarLoadoutCurrentResponse
import com.lhs.share.hub.controller.star.response.StarWorkspaceBagResponse
import com.lhs.share.hub.controller.star.response.StarWorkspaceCurrentResponse
import com.lhs.share.hub.controller.star.response.StarWorkspaceExperienceResponse
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.hub.service.star.StarExchangeReplaceService
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
import java.time.Instant

class StarExchangeControllerContractTest {
    private val service = mockk<StarExchangeReplaceService>()
    private val helper = mockk<AuthenticationHelper>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders.standaloneSetup(StarExchangeController(service, helper))
            .setControllerAdvice(InventoryExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
        every { helper.requireUserId() } returns "jwt-user"
    }

    @Test
    fun `replacement endpoint accepts only inventory workspace and account payload`() {
        every { service.replace("jwt-user", any()) } returns response()

        mockMvc.perform(post("/v1/star-exchange/replace").contentType(MediaType.APPLICATION_JSON).content(validBody))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.account_id").value("acc_a"))
            .andExpect(jsonPath("$.data.inventory.entries[0].instance_id").value("new-main"))
            .andExpect(jsonPath("$.data.workspace.plan_targets.new-main").value(60))
            .andExpect(jsonPath("$.data.loadout.loadouts").isEmpty)
        verify {
            service.replace(
                "jwt-user",
                match {
                    it.accountId == "acc_a" &&
                        it.inventory?.expectedRevision == 3L &&
                        it.workspace?.expectedRevision == 4L &&
                        it.workspace?.planTargets == mapOf("new-main" to 60)
                },
            )
        }
    }

    @Test
    fun `replacement endpoint rejects a client supplied loadout`() {
        mockMvc.perform(
            post("/v1/star-exchange/replace")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody.replace("\n}", ",\"loadout\":{}}")),
        )
            .andExpect(status().isUnprocessableEntity)
        verify(exactly = 0) { service.replace(any(), any()) }
    }

    @Test
    fun `replacement endpoint preserves runtime conflict and validation error codes`() {
        every { service.replace("jwt-user", any()) } throws InventoryApiException(
            HttpStatus.CONFLICT,
            "star_workspace_revision_conflict",
            "Star workspace changed; reload before saving",
        )

        mockMvc.perform(post("/v1/star-exchange/replace").contentType(MediaType.APPLICATION_JSON).content(validBody))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("star_workspace_revision_conflict"))

        every { service.replace("jwt-user", any()) } throws InventoryApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "star_exchange_invalid_workspace_reference",
            "plan_targets must reference a replacement inventory instance_id",
        )

        mockMvc.perform(post("/v1/star-exchange/replace").contentType(MediaType.APPLICATION_JSON).content(validBody))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_exchange_invalid_workspace_reference"))
    }

    private fun response() = StarExchangeReplaceResponse(
        "acc_a",
        StarInventorySnapshotResponse(
            "acc_a",
            Instant.parse("2026-09-17T01:00:00Z"),
            listOf(com.lhs.share.hub.controller.star.response.StarInventoryEntryResponse("new-main", "main", "天府", "orange", 60)),
            4,
            Instant.parse("2026-09-17T01:00:01Z"),
        ),
        StarWorkspaceCurrentResponse(
            "acc_a",
            5,
            mapOf("new-main" to 60),
            StarWorkspaceBagResponse(1, 100),
            StarWorkspaceExperienceResponse(1, 2, 3),
            Instant.parse("2026-09-17T01:00:01Z"),
        ),
        StarLoadoutCurrentResponse("acc_a", 2, emptyMap(), Instant.parse("2026-09-17T01:00:01Z")),
    )

    private val validBody =
        """
        {
          "account_id":"acc_a",
          "inventory":{"expected_revision":3,"effective_at":"2026-09-17T01:00:00Z","entries":[{"instance_id":"new-main","kind":"main","name":"天府","quality":"orange","level":60}]},
          "workspace":{"expected_revision":4,"plan_targets":{"new-main":60},"bag":{"current_count":1,"capacity":100},"experience":{"orange":1,"purple":2,"white":3}}
        }
        """.trimIndent()
}
