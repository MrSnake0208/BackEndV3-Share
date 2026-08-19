package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.account.response.SubAccountResponse
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.controller.operator.response.OperatorExportResponse
import com.lhs.share.hub.controller.operator.response.OperatorImportResult
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.operator.OperatorService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/open-api/operator", produces = [MediaType.APPLICATION_JSON_VALUE])
class OpenApiOperatorController(
    private val tokenService: OpenApiTokenService,
    private val service: OperatorService,
    private val accountService: SubAccountService,
) {
    @GetMapping("/account")
    fun account(@RequestHeader(value = "Authorization", required = false) authorization: String?): ApiResult<SubAccountResponse> {
        val principal = tokenService.authenticateAuthorization(authorization)
        return success(SubAccountResponse.of(accountService.requireAccount(principal.userId, principal.accountId)))
    }

    @GetMapping("/current")
    fun current(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestParam(required = false) game: String?,
    ): ApiResult<List<OperatorCurrentResponse>> {
        val principal = tokenService.validateAuthorization(authorization, OpenApiPermission.OPERATOR_READ)
        return success(service.current(principal.userId, principal.accountId, game))
    }

    @PostMapping("/import")
    fun import(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: OperatorImportRequest,
    ): ApiResult<OperatorImportResult> {
        val principal = tokenService.validateAuthorization(authorization, OpenApiPermission.OPERATOR_WRITE)
        return success(service.import(principal.userId, principal.accountId, request))
    }

    @GetMapping("/export")
    fun export(@RequestHeader(value = "Authorization", required = false) authorization: String?): OperatorExportResponse {
        val principal = tokenService.validateAuthorization(authorization, OpenApiPermission.OPERATOR_EXPORT)
        return service.export(principal.userId, principal.accountId, null)
    }
}
