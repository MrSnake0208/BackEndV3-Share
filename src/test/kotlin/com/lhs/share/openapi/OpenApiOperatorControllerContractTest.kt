package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResult
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.operator.OperatorService
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
}
