package com.lhs.share.hub.controller.operator

import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.response.OperatorCatalogResponse
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.controller.operator.response.OperatorExportResponse
import com.lhs.share.hub.controller.operator.response.OperatorImportResult
import com.lhs.share.hub.controller.operator.response.OperatorRecordPageResponse
import com.lhs.share.hub.service.operator.OperatorCatalogService
import com.lhs.share.hub.service.operator.OperatorService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@RestController
@RequestMapping("/v1/operator", produces = [MediaType.APPLICATION_JSON_VALUE])
class OperatorController(
    private val service: OperatorService,
    private val catalogService: OperatorCatalogService,
    private val helper: AuthenticationHelper,
) {
    @PostMapping("/import")
    fun import(@Valid @RequestBody request: OperatorImportRequest): ApiResult<OperatorImportResult> =
        success(service.import(helper.requireUserId(), request))

    @GetMapping("/current")
    fun current(
        @RequestParam(name = "account_id") accountId: String,
        @RequestParam(required = false) game: String?,
    ): ApiResult<List<OperatorCurrentResponse>> = success(service.current(helper.requireUserId(), accountId, game))

    @GetMapping("/records")
    fun records(
        @RequestParam(name = "account_id") accountId: String,
        @RequestParam(required = false) game: String?,
        @RequestParam(required = false) from: OffsetDateTime?,
        @RequestParam(required = false) to: OffsetDateTime?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ApiResult<OperatorRecordPageResponse> =
        success(service.listRecords(helper.requireUserId(), accountId, game, from?.toInstant(), to?.toInstant(), cursor, limit))

    @DeleteMapping("/records/{recordId}")
    fun deleteRecord(@PathVariable recordId: String, @RequestParam(name = "account_id") accountId: String): ApiResult<Boolean> {
        service.deleteRecord(helper.requireUserId(), accountId, recordId)
        return success(true)
    }

    @GetMapping("/export")
    fun export(
        @RequestParam(name = "account_id", required = false) accountId: String?,
        @RequestParam(required = false) scope: String?,
    ): OperatorExportResponse = service.export(helper.requireUserId(), accountId, scope)

    /**
     * 公共开放 API（图鉴）：无需登录，返回全局只读密探目录。
     * 管理端（新增/修改/删除）见 [AdminOperatorCatalogController]。
    */
    @Operation(
        summary = "密探公共图鉴",
        description = "返回 special_oddity_name、服务端按 rarity 派生的只读 oddity_schema，以及 incomplete_fields",
    )
    @GetMapping("/catalog")
    fun catalog(): ApiResult<OperatorCatalogResponse> = success(catalogService.catalog())
}
