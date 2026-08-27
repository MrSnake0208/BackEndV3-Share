package com.lhs.share.hub.controller.report.request

/**
 * 更新反馈工单状态请求
 *
 * @property status 目标状态: OPEN / RESOLVED / DISMISSED
 */
data class FeedbackStatusUpdateRequest(
    val status: String,
)