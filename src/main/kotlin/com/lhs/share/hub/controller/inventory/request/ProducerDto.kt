package com.lhs.share.hub.controller.inventory.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 生产者平台信息(协议 3 producer 字段)
 */
data class ProducerDto(
    @field:NotBlank(message = "platform 不能为空")
    @field:Size(max = 64, message = "platform 最长 64 个字符")
    val platform: String,
    @field:Size(max = 64, message = "version 最长 64 个字符")
    val version: String? = null,
)
