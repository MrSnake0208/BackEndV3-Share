package com.lhs.share.controller.request.openapi

import jakarta.validation.constraints.NotBlank

/**
 * 删除第三方 API Token 请求
 */
data class OpenApiTokenDeleteRequest(
    /**
     * 要删除的 token
     */
    @field:NotBlank(message = "token 不能为空")
    val token: String,
)
