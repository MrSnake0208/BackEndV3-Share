package com.lhs.share.hub.controller.report.request

import jakarta.validation.constraints.NotBlank
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
    @field:NotBlank(message = "正文不能为空")
    @field:Size(max = 1000, message = "正文长度不能超过 1000 字符")
    val content: String,
    @field:Size(max = 3, message = "图片数量不能超过 3 张")
    val mediaIds: List<String> = emptyList(),
    val clientInfoConsent: Boolean = false,
)
