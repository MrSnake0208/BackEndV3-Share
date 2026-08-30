package com.lhs.share.hub.controller.report.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 追加反馈消息请求
 *
 * @property content 消息正文(1..1000 字符)
 * @property mediaIds 关联媒体 id 列表(最多 3 个)
 */
data class FeedbackMessageAppendRequest(
    @field:NotBlank(message = "消息不能为空")
    @field:Size(max = 1000, message = "消息长度不能超过 1000 字符")
    val content: String,
    @field:Size(max = 3, message = "附件数量不能超过 3 个")
    val mediaIds: List<String> = emptyList(),
)
