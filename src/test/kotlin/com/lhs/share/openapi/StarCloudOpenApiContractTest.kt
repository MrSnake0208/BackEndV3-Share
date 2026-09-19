package com.lhs.share.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.config.doc.SpringDocConfig
import com.lhs.share.config.external.ShareProperties
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.hub.controller.star.StarExchangeController
import com.lhs.share.hub.controller.star.StarLoadoutPresetController
import com.lhs.share.hub.service.star.StarExchangeReplaceService
import com.lhs.share.hub.service.star.StarLoadoutPresetService
import com.lhs.share.service.DataTransferService
import com.lhs.share.service.jwt.JwtService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springdoc.core.configuration.SpringDocConfiguration
import org.springdoc.core.configuration.SpringDocKotlinConfiguration
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [StarExchangeController::class, StarLoadoutPresetController::class])
@AutoConfigureMockMvc(addFilters = false)
@EnableConfigurationProperties(ShareProperties::class)
@Import(
    SpringDocConfig::class,
    SpringDocConfiguration::class,
    SpringDocConfigProperties::class,
    SpringDocKotlinConfiguration::class,
    SpringDocWebMvcConfiguration::class,
)
@TestPropertySource(properties = ["share.info.public-base-url=https://star.example.test"])
class StarCloudOpenApiContractTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var exchangeService: StarExchangeReplaceService

    @MockitoBean
    lateinit var presetService: StarLoadoutPresetService

    @MockitoBean
    lateinit var authenticationHelper: AuthenticationHelper

    @MockitoBean
    lateinit var stringRedisTemplate: StringRedisTemplate

    @MockitoBean
    lateinit var dataTransferService: DataTransferService

    @MockitoBean
    lateinit var jwtService: JwtService

    @Test
    fun `generated OpenAPI documents replacement and preset conflict validation codes`() {
        val root = objectMapper.readTree(
            mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString,
        )

        val replacementResponses = root.at("/paths/~1v1~1star-exchange~1replace/post/responses")
        assertTrue(replacementResponses.has("409"))
        assertTrue(replacementResponses.has("422"))
        assertTrue(replacementResponses["409"]["description"].asText().contains("star_inventory_revision_conflict"))
        assertTrue(replacementResponses["409"]["description"].asText().contains("star_workspace_revision_conflict"))
        assertTrue(replacementResponses["422"]["description"].asText().contains("star_exchange_invalid_workspace_reference"))

        val presetResponses = root.at("/paths/~1v1~1star-loadout-presets~1current/put/responses")
        assertTrue(presetResponses.has("409"))
        assertTrue(presetResponses.has("422"))
        assertTrue(presetResponses["409"]["description"].asText().contains("star_loadout_preset_revision_conflict"))
        assertTrue(presetResponses["422"]["description"].asText().contains("star_loadout_preset_invalid_snapshot"))
    }
}
