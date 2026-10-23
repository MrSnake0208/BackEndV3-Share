package com.lhs.share.openapi

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.BetaAccessPolicy
import com.lhs.share.hub.controller.star.request.StarScanReportRequest
import com.lhs.share.hub.service.star.StarScanService
import com.lhs.share.hub.service.star.StarScanUploadResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiStarScanControllerContractTest {
    @Test fun `scan uses dedicated scope and token account without granting state write`() {
        val tokens = mockk<OpenApiTokenService>()
        val scans = mockk<StarScanService>()
        val controller = OpenApiStarScanController(tokens, scans)
        val report = StarScanReportRequest()
        every { tokens.validateAuthorization("Bearer scan", OpenApiPermission.STAR_SCAN_WRITE) } returns OpenApiPrincipal("u", "a")
        every { scans.upload("u", "a", report) } returns StarScanUploadResponse("r", "needs_review")
        assertEquals("needs_review", controller.upload("Bearer scan", report).data!!.status)
        every { tokens.validateAuthorization(null, OpenApiPermission.STAR_SCAN_WRITE) } throws IllegalStateException("unauthorized")
        assertThrows(IllegalStateException::class.java) { controller.upload(null, report) }
        verify(exactly = 1) { scans.upload(any(), any(), any()) }
        assertTrue(BetaAccessPolicy.requiresBeta("POST", "/v1/star/scans/r/apply"))
        assertTrue(BetaAccessPolicy.requiresBeta("GET", "/v1/star/scans/pending"))
        assertEquals(30002, OpenApiPermission.codeByKey("star:scan:write"))
    }

    @Test fun `scan rejects embedded images and client supplied plans at decoding boundary`() {
        val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        for (field in listOf("images", "plan_targets", "loadouts")) {
            assertThrows(Exception::class.java) { mapper.readValue("{\"$field\":[]}", StarScanReportRequest::class.java) }
        }
    }
}
