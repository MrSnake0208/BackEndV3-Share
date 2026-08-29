package com.lhs.share.hub.service.report

/** 反馈工单类型；FEEDBACK 仅用于兼容第一版数据和请求。 */
object FeedbackType {
    const val BUG = "BUG"
    const val FEATURE = "FEATURE"
    const val CONTENT = "CONTENT"
    const val ACCOUNT = "ACCOUNT"
    const val REPORT = "REPORT"
    const val OTHER = "OTHER"
    const val LEGACY_FEEDBACK = "FEEDBACK"

    val all = linkedSetOf(BUG, FEATURE, CONTENT, ACCOUNT, REPORT, OTHER)
}
