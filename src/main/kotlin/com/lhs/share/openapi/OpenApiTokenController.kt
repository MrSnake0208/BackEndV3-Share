package com.lhs.share.openapi

import com.lhs.share.config.doc.OpenApiTokenDeleteResponses
import com.lhs.share.config.doc.OpenApiTokenGenerateResponses
import com.lhs.share.config.doc.OpenApiTokenListResponses
import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.request.openapi.OpenApiTokenGenerateRequest
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 第三方 API Token 管理接口
 *
 * 生成/删除/列举均需登录;权限列表公开(见 SecurityConfig.URL_PERMIT_ALL)。
 */
@Tag(name = "OpenAPI Token")
@RequestMapping("/user/open-api", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class OpenApiTokenController(
    private val tokenService: OpenApiTokenService,
    private val helper: AuthenticationHelper,
) {
    /**
     * 生成第三方 API Token(需登录)
     */
    @Operation(summary = "生成第三方 API Token")
    @RequireJwt
    @OpenApiTokenGenerateResponses
    @PostMapping("/token", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun generate(@Valid @RequestBody request: OpenApiTokenGenerateRequest): ApiResult<OpenApiTokenCreatedResponse> =
        success(tokenService.generate(helper.requireUserId(), request.accountId, request.scopes, request.remark))

    /**
     * 权限列表(公开,无需登录)
     */
    @Operation(summary = "权限列表")
    @GetMapping("/permissions")
    fun permissions(): ApiResult<List<OpenApiPermissionDto>> = success(OpenApiPermission.listAll())

    /**
     * 列举当前用户的 token(需登录)
     */
    @Operation(summary = "列举当前用户的 token")
    @RequireJwt
    @OpenApiTokenListResponses
    @GetMapping("/tokens")
    fun tokens(): ApiResult<List<OpenApiTokenListItemDto>> = success(tokenService.list(helper.requireUserId()))

    /**
     * 删除第三方 API Token(需登录)
     */
    @Operation(summary = "删除第三方 API Token")
    @RequireJwt
    @OpenApiTokenDeleteResponses
    @DeleteMapping("/tokens/{tokenId}")
    fun delete(@PathVariable tokenId: String): ApiResult<Unit> {
        tokenService.delete(helper.requireUserId(), tokenId)
        return success()
    }
}
