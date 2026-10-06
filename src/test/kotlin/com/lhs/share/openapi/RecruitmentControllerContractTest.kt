package com.lhs.share.openapi

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.config.security.BetaAccessPolicy
import com.lhs.share.handler.RecruitmentExceptionHandler
import com.lhs.share.hub.controller.recruitment.RecruitmentController
import com.lhs.share.hub.controller.recruitment.response.RecruitmentArchiveResponse
import com.lhs.share.hub.controller.recruitment.response.RecruitmentCommandResponse
import com.lhs.share.hub.controller.recruitment.response.RecruitmentEventPage
import com.lhs.share.hub.controller.recruitment.response.RecruitmentSummary
import com.lhs.share.hub.service.recruitment.RecruitmentAccessService
import com.lhs.share.hub.service.recruitment.RecruitmentApiException
import com.lhs.share.hub.service.recruitment.RecruitmentCatalog
import com.lhs.share.hub.service.recruitment.RecruitmentService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException

class RecruitmentControllerContractTest {
    private val service = mockk<RecruitmentService>()
    private val access = mockk<RecruitmentAccessService>()
    private val helper = mockk<AuthenticationHelper>()
    private val mapper = jacksonObjectMapper().registerModule(
        JavaTimeModule(),
    ).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private lateinit var mvc: MockMvc

    @BeforeEach fun setup() {
        every { access.requireAccess(any()) } just runs
        mvc =
            MockMvcBuilders.standaloneSetup(
                RecruitmentController(
                    service,
                    RecruitmentCatalog(mapper, mockk(relaxed = true), mockk(relaxed = true)),
                    access,
                    helper,
                    mapper,
                ),
            )
                .setControllerAdvice(RecruitmentExceptionHandler())
                .setMessageConverters(MappingJackson2HttpMessageConverter(mapper)).build()
    }

    @Test fun `catalog and recruitment endpoints are outside the global beta policy`() {
        mvc.perform(get("/v1/recruitment/catalog").param("game", "如鸢")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pools").isEmpty)
        verify {
            helper wasNot io.mockk.Called
            service wasNot io.mockk.Called
        }
        assertFalse(BetaAccessPolicy.requiresBeta("GET", "/v1/recruitment/catalog"))
        listOf("archive", "events", "commands", "import/commit").forEach {
            assertFalse(BetaAccessPolicy.requiresBeta("POST", "/v1/recruitment/$it"))
        }
    }

    @Test fun `private requests return HTTP auth conflict validation and not found error envelopes`() {
        every { helper.requireUserId() } throws ResponseStatusException(HttpStatus.UNAUTHORIZED)
        mvc.perform(get("/v1/recruitment/archive").param("account_id", "a")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("unauthorized"))
        every { helper.requireUserId() } returns "u"
        every { access.requireAccess("u") } throws
            RecruitmentApiException(HttpStatus.FORBIDDEN, "recruitment_access_required", "Access required")
        mvc.perform(get("/v1/recruitment/archive").param("account_id", "a")).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("recruitment_access_required"))
        verify(exactly = 0) { service.archive(any(), any()) }
        every { access.requireAccess("u") } just runs
        listOf(HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY).forEach { code ->
            every { service.archive("u", "a") } throws RecruitmentApiException(code, "boundary", "test")
            mvc.perform(get("/v1/recruitment/archive").param("account_id", "a")).andExpect(status().`is`(code.value()))
                .andExpect(jsonPath("$.error.code").value("boundary"))
        }
        mvc.perform(get("/v1/recruitment/archive")).andExpect(status().isUnprocessableEntity)
        mvc.perform(
            post("/v1/recruitment/commands").contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isUnprocessableEntity)
    }

    @Test fun `archive exposes complete stable slot counts including zero in snake case without records`() {
        every { helper.requireUserId() } returns "u"
        val summary = RecruitmentSummary(0, 0, 0, 0, 0, 0, 0, 0, false)
        every { service.archive("u", "a") } returns RecruitmentArchiveResponse(
            "a", "如鸢", 0, 0, null, emptyList(), emptyList(), summary, false,
            mapOf("p" to summary.copy(upAgentCounts = mapOf("catalog_p:up:A" to 2L, "catalog_p:up:B" to 0L))),
        )
        mvc.perform(get("/v1/recruitment/archive").param("account_id", "a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pool_summaries.p.up_agent_counts['catalog_p:up:A']").value(2))
            .andExpect(jsonPath("$.data.pool_summaries.p.up_agent_counts['catalog_p:up:B']").value(0))
            .andExpect(jsonPath("$.data.records").doesNotExist())
        verify(exactly = 1) { service.archive("u", "a") }
    }

    @Test fun `event sort direction is forwarded and defaults to newest first`() {
        every { helper.requireUserId() } returns "u"
        every { service.page("u", "a", null, null, 50, null, null, "desc") } returns RecruitmentEventPage(emptyList(), null, 0)
        every { service.page("u", "a", null, null, 2, null, null, "asc") } returns RecruitmentEventPage(emptyList(), null, 0)
        mvc.perform(get("/v1/recruitment/events").param("account_id", "a"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.archive_revision").value(0))
        mvc.perform(get("/v1/recruitment/events").param("account_id", "a").param("limit", "2").param("order", "asc"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.items").isEmpty)
        verify(exactly = 1) { service.page("u", "a", null, null, 50, null, null, "desc") }
        verify(exactly = 1) { service.page("u", "a", null, null, 2, null, null, "asc") }
    }

    @Test fun `commands use snake case request and response contract`() {
        every { helper.requireUserId() } returns "u"
        every {
            service.command(
                "u",
                match {
                    it.accountId == "a" && it.expectedRevision == 1L && it.requestId == "r" &&
                        it.operation == "event_delete"
                },
            )
        } returns
            RecruitmentCommandResponse(2, listOf("B"))
        mvc.perform(
            post("/v1/recruitment/commands").contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"account_id":"a","expected_revision":1,"request_id":"r","operation":"event_delete","data":{"event_id":"B"}}""",
                ),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.data.archive_revision").value(2))
            .andExpect(jsonPath("$.data.event_ids[0]").value("B"))
    }

    @Test fun `pool dialog forwards per result spans remaining pulls and stable record IDs`() {
        every { helper.requireUserId() } returns "u"
        every {
            service.command(
                "u",
                match {
                    it.accountId == "a" && it.requestId == "dialog" && it.expectedRevision == 1L &&
                        it.operation == "pool_records_save" && it.data.path("pool_id").asText() == "p" &&
                        it.data.path("remaining_pulls").asInt() == 19 && it.data.path("entries")[0].path("pull_span").asInt() == 17
                },
            )
        } returns RecruitmentCommandResponse(2, listOf("E"), poolId = "p")
        mvc.perform(
            post("/v1/recruitment/commands").contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"account_id":"a","expected_revision":1,"request_id":"dialog","operation":"pool_records_save","data":{"pool_id":"p","remaining_pulls":19,"entries":[{"event_id":"E","agent_id":"A","pull_span":17}],"deleted_event_ids":[]}}""",
                ),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.data.archive_revision").value(2))
            .andExpect(jsonPath("$.data.pool_id").value("p")).andExpect(jsonPath("$.data.event_ids[0]").value("E"))
    }

    @Test fun `command envelope rejects null decimal missing revision and unknown root fields`() {
        every { helper.requireUserId() } returns "u"
        listOf("null", "0.5", "\"0\"").forEach { revision ->
            mvc.perform(
                post("/v1/recruitment/commands").contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"account_id":"a","expected_revision":$revision,"request_id":"r",
                        "operation":"baseline_set","data":{"baseline":0}}
                        """.trimIndent(),
                    ),
            )
                .andExpect(status().isUnprocessableEntity)
        }
        listOf(
            "null",
            "[]",
            "\"text\"",
            "1",
            """{"account_id":"a","request_id":"r","operation":"baseline_set","data":{"baseline":0}}""",
            """{"account_id":"a","expected_revision":0,"request_id":"r","operation":"baseline_set","data":{"baseline":0},"extra":true}""",
        ).forEach { body ->
            mvc.perform(
                post("/v1/recruitment/commands").contentType(MediaType.APPLICATION_JSON).content(body),
            ).andExpect(status().isUnprocessableEntity)
        }
        verify { service wasNot io.mockk.Called }
    }

    @Test fun `entry dates require ISO strings or null and reject JavaTime array coercion`() {
        every { helper.requireUserId() } returns "u"
        listOf("[2026,10,1]", "1", "{}", "true", "\"2026-02-30\"", "\"2026-10-01T00:00:00Z\"").forEach { date ->
            mvc.perform(
                post("/v1/recruitment/commands").contentType(MediaType.APPLICATION_JSON).content(
                    """{"account_id":"a","expected_revision":0,"request_id":"r","operation":"event_create","data":{"pool_id":"p","mode":"historical","entries":[{"agent_id":"A","pull_span":17,"acquired_date":$date}]}}""",
                ),
            ).andExpect(status().isUnprocessableEntity)
        }
        verify { service wasNot io.mockk.Called }
        every { service.command("u", any()) } returns RecruitmentCommandResponse(1, listOf("E"))
        listOf("null", "\"2026-10-01\"").forEach { date ->
            mvc.perform(
                post("/v1/recruitment/commands").contentType(MediaType.APPLICATION_JSON).content(
                    """{"account_id":"a","expected_revision":0,"request_id":"r","operation":"event_update","data":{"event_id":"E","entry":{"agent_id":"A","pull_span":17,"acquired_date":$date}}}""",
                ),
            ).andExpect(status().isOk)
        }
        verify(exactly = 2) { service.command("u", any()) }
    }
}
