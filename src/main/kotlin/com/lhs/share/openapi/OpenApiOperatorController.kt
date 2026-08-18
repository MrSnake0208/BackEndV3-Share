package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.response.*
import com.lhs.share.hub.service.operator.OperatorAccountService
import com.lhs.share.hub.service.operator.OperatorService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/open-api/operator", produces=[MediaType.APPLICATION_JSON_VALUE])
class OpenApiOperatorController(
    private val tokenService: OpenApiTokenService,
    private val service: OperatorService,
    private val accountService: OperatorAccountService,
) {
    @GetMapping("/account") fun account(@RequestHeader(value="Authorization",required=false) authorization: String?): ApiResult<OperatorAccountResponse> { val p=tokenService.authenticateAuthorization(authorization); return success(OperatorAccountResponse.of(accountService.requireAccount(p.userId,p.accountId))) }
    @GetMapping("/current") fun current(@RequestHeader(value="Authorization",required=false) authorization: String?, @RequestParam(required=false) game: String?): ApiResult<List<OperatorCurrentResponse>> { val p=tokenService.validateAuthorization(authorization,OpenApiPermission.OPERATOR_READ); return success(service.current(p.userId,p.accountId,game)) }
    @PostMapping("/import") fun import(@RequestHeader(value="Authorization",required=false) authorization: String?, @Valid @RequestBody request: OperatorImportRequest): ApiResult<OperatorImportResult> { val p=tokenService.validateAuthorization(authorization,OpenApiPermission.OPERATOR_WRITE); return success(service.import(p.userId,p.accountId,request)) }
    @GetMapping("/export") fun export(@RequestHeader(value="Authorization",required=false) authorization: String?): OperatorExportResponse { val p=tokenService.validateAuthorization(authorization,OpenApiPermission.OPERATOR_EXPORT); return service.export(p.userId,p.accountId,null) }
}
