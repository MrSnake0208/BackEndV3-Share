package com.lhs.share.hub.controller.report.request

import jakarta.validation.constraints.Size

/**
 * 创建反馈工单请求
 *
 * @property type 工单类型: FEEDBACK(第一期仅支持此值)
 * @property category 分类(仅 FEEDBACK 必填): FEATURE / BUG / CONTENT / ACCOUNT / OTHER
 * @property content 正文(1..1000 字符)
 * @property mediaIds 关联媒体 id 列表(最多 3 个)
 * @property clientInfoConsent 是否同意附带浏览器信息
 */
data class FeedbackReportCreateRequest(
    val type: String = "FEEDBACK",
    val category: String? = null,
    @field:Size(min = 1, max = 1000, message = "正文长度应在 1~1000 字符之间")
    val content: String,
    val mediaIds: List<String> = emptyList(),
    val clientInfoConsent: Boolean = false,
)