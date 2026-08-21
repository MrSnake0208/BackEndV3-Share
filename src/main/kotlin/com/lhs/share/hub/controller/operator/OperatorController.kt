package com.lhs.share.hub.controller.operator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.operator.request.OperatorCurrentPatchRequest
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.request.OperatorV3BrowserImportRequest
import com.lhs.share.hub.controller.operator.response.OperatorCatalogResponse
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorCurrentResponse
import com.lhs.share.hub.controller.operator.response.OperatorErrorResponse
import com.lhs.share.hub.controller.operator.response.OperatorExportResponse
import com.lhs.share.hub.controller.operator.response.OperatorImportResult
import com.lhs.share.hub.controller.operator.response.OperatorRecordPageResponse
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportCommitResponse
import com.lhs.share.hub.controller.operator.response.OperatorV3ImportPreviewResponse
import com.lhs.share.hub.service.operator.OperatorCatalogService
import com.lhs.share.hub.service.operator.OperatorService
import com.lhs.share.hub.service.operator.OperatorV3ImportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@RestController
@RequestMapping("/v1/operator", produces = [MediaType.APPLICATION_JSON_VALUE])
class OperatorController(
    private val service: OperatorService,
    private val catalogService: OperatorCatalogService,
    private val helper: AuthenticationHelper,
    private val v3ImportService: OperatorV3ImportService? = null,
    private val objectMapper: ObjectMapper = ObjectMapper().findAndRegisterModules(),
) {
    @Operation(
        summary = "提交密探交换文档",
        description = "version=2 保持原有导入；version=3 与 preview 共用 Schema、账号映射和 current 局部合并规则。" +
            "v3 使用 {document, account_mapping} 包装体，或在来源 id 就是本人账号 id 时直接提交文档。",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                content = [Content(schema = Schema(oneOf = [OperatorImportResult::class, OperatorV3ImportCommitResponse::class]))],
            ),
            ApiResponse(responseCode = "403", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
            ApiResponse(responseCode = "409", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
            ApiResponse(responseCode = "422", content = [Content(schema = Schema(implementation = OperatorErrorResponse::class))]),
        ],
    )
    @PostMapping("/import", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun importDocument(
        @SwaggerRequestBody(
            required = true,
            content = [Content(schema = Schema(oneOf = [OperatorImportRequest::class, OperatorV3BrowserImportRequest::class]))],
        )
        @RequestBody request: JsonNode,
    ): ApiResult<Any> {
        val document = request.path("document").takeUnless(JsonNode::isMissingNode) ?: request
        return if (document.path("version").asInt() == 3) {
            success(requireNotNull(v3ImportService).commitBrowser(helper.requireUserId(), request))
        } else {
            val v2 = objectMapper.treeToValue(request, OperatorImportRequest::class.java)
            success(service.import(helper.requireUserId(), v2))
        }
    }

    @Operation(
        summary = "预览密探 v3 导入",
        description = "严格校验 v3 Schema、账号归属、公共图鉴和字段语义；返回逐密探差异、stale 与目标 revision，不写 current 或库存。",
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
    @PostMapping("/import/preview", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun previewImport(
        @SwaggerRequestBody(
            required = true,
            content = [Content(schema = Schema(implementation = OperatorV3BrowserImportRequest::class))],
        )
        @RequestBody request: JsonNode,
    ): ApiResult<OperatorV3ImportPreviewResponse> {
        return success(requireNotNull(v3ImportService).previewBrowser(helper.requireUserId(), request))
    }

    @GetMapping("/current")
    fun current(
        @RequestParam(name = "account_id") accountId: String,
        @RequestParam(required = false) game: String?,
    ): ApiResult<List<OperatorCurrentResponse>> = success(service.current(helper.requireUserId(), accountId, game))

    @Operation(
        summary = "局部校正密探当前养成",
        description = "只合并请求中出现的字段；star_stones 出现时完整替换六槽当前装备，空数组清空；" +
            "display_mode 按 attack/hp 局部合并且不触发 stale；无 entry 且 expected_revision=0 时创建；" +
            "不扣库存。expected_revision 冲突返回 operator_revision_conflict。",
    )
    @PatchMapping("/current/{operatorId}")
    fun patchCurrent(
        @PathVariable operatorId: String,
        @RequestParam(name = "account_id") accountId: String,
        @RequestParam game: String,
        @SwaggerRequestBody(
            required = true,
            content = [Content(schema = Schema(implementation = OperatorCurrentPatchRequest::class))],
        )
        @RequestBody request: ObjectNode,
    ): ApiResult<OperatorCurrentEntryDto> = success(
        service.patchCurrent(helper.requireUserId(), accountId, game, operatorId, request),
    )

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
