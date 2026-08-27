package com.lhs.share.hub.controller.report.request

import jakarta.validation.constraints.Size

/**
 * 追加反馈消息请求
 *
 * @property content 消息正文(1..1000 字符)
 * @property mediaIds 关联媒体 id 列表(最多 3 个)
 */
data class FeedbackMessageAppendRequest(
    @field:Size(min = 1, max = 1000, message = "消息长度应在 1~1000 字符之间")
    val content: String,
    val mediaIds: List<String> = emptyList(),
)