package com.lhs.share.openapi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportPreviewResponse
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.operator.OperatorService
import com.lhs.share.hub.service.operator.OperatorV3ImportService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class OpenApiOperatorControllerContractTest {
    private val tokenService = mockk<OpenApiTokenService>()
    private val service = mockk<OperatorService>()
    private val accountService = mockk<SubAccountService>()
    private val controller = OpenApiOperatorController(tokenService, service, accountService)

    @Test
    fun `operator read scope receives extended current response without a PATCH endpoint`() {
        every { tokenService.validateAuthorization("Bearer read", OpenApiPermission.OPERATOR_READ) } returns
            OpenApiPrincipal("u1", "acc1")
        every { service.current("u1", "acc1", "代号鸢") } returns listOf(
            OperatorCurrentResponse(
                userId = "u1",
                accountId = "acc1",
                game = "代号鸢",
                fullBaselineAt = null,
                entries = mapOf(
                    "op1" to OperatorCurrentEntryDto(
                        elite = 1,
                        starLevel = 3,
                        level = 10,
                        discs = emptyList(),
                        starStones = emptyList(),
                        discLoadouts = emptyList(),
                        combatStats = null,
                        revision = 0,
                        listedBaselineAt = null,
                        updatedAt = null,
                    ),
                ),
                updatedAt = Instant.EPOCH,
            ),
        )

        val response: ApiResult<List<OperatorCurrentResponse>> = controller.current("Bearer read", "代号鸢")

        assertEquals(0, response.data?.single()?.entries?.getValue("op1")?.revision)
        verify { tokenService.validateAuthorization("Bearer read", OpenApiPermission.OPERATOR_READ) }
        verify { service.current("u1", "acc1", "代号鸢") }
    }

    @Test
    fun `scan preview requires dedicated scope and forwards token bound account`() {
        val v3 = mockk<OperatorV3ImportService>()
        val scanController = OpenApiOperatorController(tokenService, service, accountService, v3)
        val document = jacksonObjectMapper().readTree("""{"version":3}""")
        every { tokenService.validateAuthorization("Bearer scan", OpenApiPermission.OPERATOR_SCAN_WRITE) } returns
            OpenApiPrincipal("u1", "acc1")
        every { v3.previewScan("u1", "acc1", document) } returns
            OperatorV3ImportPreviewResponse(accepted = 0, partial = 0, review = 0, rejected = 0, unchanged = 0, items = emptyList())

        val response = scanController.previewScanImport("Bearer scan", document)

        assertEquals(0, response.data?.accepted)
        verify { tokenService.validateAuthorization("Bearer scan", OpenApiPermission.OPERATOR_SCAN_WRITE) }
        verify { v3.previewScan("u1", "acc1", document) }
    }
}
