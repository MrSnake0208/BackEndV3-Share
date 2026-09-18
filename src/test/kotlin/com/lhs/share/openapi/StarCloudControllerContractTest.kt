package com.lhs.share.openapi

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.star.StarLoadoutController
import com.lhs.share.hub.controller.star.StarLoadoutPresetController
import com.lhs.share.hub.controller.star.StarWorkspaceController
import com.lhs.share.hub.controller.star.response.StarLoadoutCurrentResponse
import com.lhs.share.hub.controller.star.response.StarLoadoutPresetCurrentResponse
import com.lhs.share.hub.controller.star.response.StarWorkspaceBagResponse
import com.lhs.share.hub.controller.star.response.StarWorkspaceCurrentResponse
import com.lhs.share.hub.controller.star.response.StarWorkspaceExperienceResponse
import com.lhs.share.hub.repository.entity.StarLoadoutPreset
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.hub.service.star.StarLoadoutPresetService
import com.lhs.share.hub.service.star.StarLoadoutService
import com.lhs.share.hub.service.star.StarWorkspaceService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean

class StarCloudControllerContractTest {
    private val workspaceService = mockk<StarWorkspaceService>()
    private val loadoutService = mockk<StarLoadoutService>()
    private val presetService = mockk<StarLoadoutPresetService>()
    private val helper = mockk<AuthenticationHelper>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders.standaloneSetup(
            StarWorkspaceController(workspaceService, helper),
            StarLoadoutController(loadoutService, helper),
            StarLoadoutPresetController(presetService, helper),
        ).setControllerAdvice(InventoryExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
        every { helper.requireUserId() } returns "jwt-user"
    }

    @Test
    fun `workspace GET empty and PUT use the account scoped public contract`() {
        every { workspaceService.current("jwt-user", "acc_a") } returns
            StarWorkspaceCurrentResponse.empty("acc_a")
        every { workspaceService.putCurrent("jwt-user", "acc_a", any()) } returns workspaceResponse()

        mockMvc.perform(get("/v1/star-workspace/current").param("account_id", "acc_a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.account_id").value("acc_a"))
            .andExpect(jsonPath("$.data.revision").value(0))
            .andExpect(jsonPath("$.data.updated_at").value(null as String?))
        mockMvc.perform(
            put("/v1/star-workspace/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expected_revision":0,"plan_targets":{"star_1":60},"bag":{"current_count":1,"capacity":2},"experience":{"orange":1,"purple":2,"white":3}}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.revision").value(1))
            .andExpect(jsonPath("$.data.plan_targets.star_1").value(60))

        verify {
            workspaceService.putCurrent(
                "jwt-user",
                "acc_a",
                match {
                    it.expectedRevision == 0L &&
                        it.planTargets == mapOf("star_1" to 60)
                },
            )
        }
    }

    @Test
    fun `loadout PUT returns complete snapshot without inventory copies`() {
        every { loadoutService.putCurrent("jwt-user", "acc_a", any()) } returns
            StarLoadoutCurrentResponse(
                "acc_a",
                1,
                mapOf("operator_a" to slots("main_1")),
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
            )

        mockMvc.perform(
            put("/v1/star-loadout/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expected_revision":0,"loadouts":{"operator_a":{"main1":"main_1","main2":null,"main3":null,"support1":null,"support2":null,"support3":null}}}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.loadouts.operator_a.main1").value("main_1"))
            .andExpect(jsonPath("$.data.loadouts.operator_a.name").doesNotExist())
    }

    @Test
    fun `preset GET and PUT use user-global snake case contract without account id`() {
        every { presetService.current("jwt-user") } returns StarLoadoutPresetCurrentResponse.empty()
        every { presetService.putCurrent("jwt-user", any()) } returns StarLoadoutPresetCurrentResponse(
            1,
            listOf(StarLoadoutPreset("main-1", "预设1", listOf("天府", "武曲"))),
            listOf(StarLoadoutPreset("support-1", "预设1", listOf("文曲"))),
            java.time.Instant.parse("2026-09-01T00:00:00Z"),
        )

        mockMvc.perform(get("/v1/star-loadout-presets/current"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.revision").value(0))
            .andExpect(jsonPath("$.data.main_presets").isEmpty)
            .andExpect(jsonPath("$.data.updated_at").value(null as String?))
        mockMvc.perform(
            put("/v1/star-loadout-presets/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expected_revision":0,"main_presets":[{"id":"main-1","name":"预设1","star_names":["天府","武曲"]}],"support_presets":[{"id":"support-1","name":"预设1","star_names":["文曲"]}]}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.revision").value(1))
            .andExpect(jsonPath("$.data.main_presets[0].star_names[1]").value("武曲"))
            .andExpect(jsonPath("$.data.account_id").doesNotExist())

        verify {
            presetService.putCurrent(
                "jwt-user",
                match { request ->
                    request.expectedRevision == 0L && request.mainPresets?.single()?.starNames == listOf("天府", "武曲")
                },
            )
        }
    }

    @Test
    fun `malformed cloud payloads return each endpoint's stable validation code`() {
        mockMvc.perform(
            put("/v1/star-workspace/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_workspace_invalid_snapshot"))
        mockMvc.perform(
            put("/v1/star-loadout/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_loadout_invalid_snapshot"))
        mockMvc.perform(
            put("/v1/star-loadout-presets/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_loadout_preset_invalid_snapshot"))
        mockMvc.perform(
            put("/v1/star-loadout-presets/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expected_revision":0,"account_id":"acc_a","main_presets":[],"support_presets":[]}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("star_loadout_preset_invalid_snapshot"))
    }

    @Test
    fun `preset endpoint preserves its runtime conflict code`() {
        every { presetService.putCurrent("jwt-user", any()) } throws InventoryApiException(
            HttpStatus.CONFLICT,
            "star_loadout_preset_revision_conflict",
            "Star loadout preset changed; reload before saving",
        )

        mockMvc.perform(
            put("/v1/star-loadout-presets/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_revision\":0,\"main_presets\":[],\"support_presets\":[]}"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("star_loadout_preset_revision_conflict"))
    }

    private fun workspaceResponse() = StarWorkspaceCurrentResponse(
        "acc_a",
        1,
        mapOf("star_1" to 60),
        StarWorkspaceBagResponse(1, 2),
        StarWorkspaceExperienceResponse(1, 2, 3),
        java.time.Instant.parse("2026-09-01T00:00:00Z"),
    )

    private fun slots(main1: String?) = mapOf(
        "main1" to main1,
        "main2" to null,
        "main3" to null,
        "support1" to null,
        "support2" to null,
        "support3" to null,
    )
}
