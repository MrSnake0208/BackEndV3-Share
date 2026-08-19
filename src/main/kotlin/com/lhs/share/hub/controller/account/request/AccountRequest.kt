package com.lhs.share.hub.controller.account.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AccountRequest(
    @field:NotBlank(message = "name 不能为空")
    @field:Size(min = 1, max = 64, message = "name 长度须在 1..64")
    val name: String,
)
