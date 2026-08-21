package com.lhs.share.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.lhs.share.config.doc.RequireOpenApiToken
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.account.response.SubAccountResponse
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.request.OperatorV3ExchangeDocumentRequest
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.controller.operator.response.OperatorErrorResponse
import com.lhs.share.hub.controller.operator.response.OperatorExportResponse
import com.lhs.share.hub.controller.operator.response.OperatorImportResult
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportCommitResponse
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportPreviewResponse
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.operator.OperatorService
import com.lhs.share.hub.service.operator.OperatorV3ImportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/open-api/operator", produces = [MediaType.APPLICATION_JSON_VALUE])
class OpenApiOperatorController(
    private val tokenService: OpenApiTokenService,
    private val service: OperatorService,
    private val accountService: SubAccountService,
    private val v3ImportService: OperatorV3ImportService? = null,
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

    @Operation(
        summary = "预览自动采集密探 v3 文档",
        description = "需要 operator:scan:write；只允许单来源 operator_snapshot、source_kind=scan、snapshot_scope=listed。来源账号始终映射到 token 绑定账号。",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                content = [Content(schema = Schema(implementation = OperatorV3ImportPreviewResponse::class))],
            ),
            ApiResponse(responseCode = "403", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
            ApiResponse(responseCode = "422", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
        ],
    )
    @RequireOpenApiToken
    @PostMapping("/scan-import/preview", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun previewScanImport(
        @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @SwaggerRequestBody(
            required = true,
            content = [Content(schema = Schema(implementation = OperatorV3ExchangeDocumentRequest::class))],
        )
        @RequestBody document: JsonNode,
    ): ApiResult<OperatorV3ImportPreviewResponse> {
        val principal = tokenService.validateAuthorization(authorization, OpenApiPermission.OPERATOR_SCAN_WRITE)
        return success(requireNotNull(v3ImportService).previewScan(principal.userId, principal.accountId, document))
    }

    @Operation(
        summary = "提交自动采集密探 v3 文档",
        description = "与浏览器导入共用 Schema、preview、commit 和 current merge；不扣库存，不接受 annotation/full/manual。",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", content = [Content(schema = Schema(implementation = OperatorV3ImportCommitResponse::class))]),
            ApiResponse(responseCode = "403", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
            ApiResponse(responseCode = "409", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
            ApiResponse(responseCode = "422", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
        ],
    )
    @RequireOpenApiToken
    @PostMapping("/scan-import/commit", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun commitScanImport(
        @Parameter(hidden = true) @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @SwaggerRequestBody(
            required = true,
            content = [Content(schema = Schema(implementation = OperatorV3ExchangeDocumentRequest::class))],
        )
        @RequestBody document: JsonNode,
    ): ApiResult<OperatorV3ImportCommitResponse> {
        val principal = tokenService.validateAuthorization(authorization, OpenApiPermission.OPERATOR_SCAN_WRITE)
        return success(requireNotNull(v3ImportService).commitScan(principal.userId, principal.accountId, document))
    }

    @GetMapping("/export")
    fun export(@RequestHeader(value = "Authorization", required = false) authorization: String?): OperatorExportResponse {
        val principal = tokenService.validateAuthorization(authorization, OpenApiPermission.OPERATOR_EXPORT)
        return service.export(principal.userId, principal.accountId, null)
    }
}
