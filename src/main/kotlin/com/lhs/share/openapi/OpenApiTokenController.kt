package com.lhs.share.openapi

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.request.openapi.OpenApiTokenDeleteRequest
import com.lhs.share.controller.request.openapi.OpenApiTokenGenerateRequest
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
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
@RequestMapping("/user/open-api")
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
    @PostMapping("/token")
    fun generate(@Valid @RequestBody request: OpenApiTokenGenerateRequest): ApiResult<TokenResponse> =
        success(tokenService.generate(helper.requireUserId(), request.scope, request.remark))

    /**
     * 权限列表(公开,无需登录)
     */
    @Operation(summary = "权限列表")
    @GetMapping("/permissions")
    fun permissions(): ApiResult<List<Map<String, Any>>> = success(OpenApiPermission.listAll())

    /**
     * 列举当前用户的 token(需登录)
     */
    @Operation(summary = "列举当前用户的 token")
    @RequireJwt
    @GetMapping("/tokens")
    fun tokens(): ApiResult<List<Map<String, Any?>>> = success(tokenService.list(helper.requireUserId()))

    /**
     * 删除第三方 API Token(需登录)
     */
    @Operation(summary = "删除第三方 API Token")
    @RequireJwt
    @PostMapping("/token/delete")
    fun delete(@Valid @RequestBody request: OpenApiTokenDeleteRequest): ApiResult<Unit> {
        tokenService.delete(helper.requireUserId(), request.token)
        return success()
    }
}
