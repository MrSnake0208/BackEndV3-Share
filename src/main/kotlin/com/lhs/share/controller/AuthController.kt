package com.lhs.share.controller

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.service.jwt.JwtService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 认证演示接口,展示 JWT 链路的使用方式,接入真实业务后可删除
 *
 * 真实场景中,token 应在登录/注册成功后签发,并将 subject 设为 userId,
 * 权限写入 authorities(参考 service/jwt/JwtService 的 issueAuthToken)
 */
@Tag(name = "Auth", description = "认证演示接口")
@RequestMapping("/auth")
@RestController
class AuthController(
    private val jwtService: JwtService,
    private val helper: AuthenticationHelper,
) {
    /**
     * 签发演示 token(仅用于演示 JWT 链路,勿用于生产)
     */
    @Operation(summary = "签发演示 token")
    @PostMapping("/demo-token")
    fun issueDemoToken(@RequestParam userId: String): ApiResult<Map<String, String>> {
        val authToken = jwtService.issueAuthToken(userId, null, emptyList())
        val refreshToken = jwtService.issueRefreshToken(userId, null)
        return success(
            mapOf(
                "accessToken" to authToken.value,
                "refreshToken" to refreshToken.value,
            ),
        )
    }

    /**
     * 需要登录的演示接口,请求头携带 Authorization: Bearer <token>
     */
    @RequireJwt
    @Operation(summary = "需要登录的演示接口")
    @GetMapping("/me")
    fun me(): ApiResult<Map<String, String>> = success(mapOf("userId" to helper.requireUserId()))
}
