package com.lhs.share.hub.controller.report.response

import java.time.Instant

/**
 * 反馈消息响应
 *
 * @property id 消息 id: rpm_<hex>
 * @property senderKind REPORTER | ADMIN
 * @property author 发送人信息
 * @property content 消息正文
 * @property images 图片列表
 * @property files 普通文件列表
 * @property createdAt 发送时间
 */
data class FeedbackMessageResponse(
    val id: String,
    val senderKind: String,
    val author: AuthorInfo,
    val content: String,
    val images: List<ImageInfo>,
    val createdAt: Instant,
    val files: List<FileInfo> = emptyList(),
) {
    data class AuthorInfo(
        val id: String,
        val userName: String,
    )

    data class ImageInfo(
        val id: String,
        val url: String,
    )

    data class FileInfo(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val downloadUrl: String,
    )
}

/**
 * 反馈工单响应
 *
 * @property id 工单 id: rpt_<hex>
 * @property type BUG / FEATURE / CONTENT / ACCOUNT / REPORT / OTHER
 * @property category 反馈归属板块
 * @property status OPEN / RESOLVED / DISMISSED
 * @property content 首条正文
 * @property messages 消息列表
 * @property quota 配额信息
 * @property viewerIsReporter 当前查看者是否为提交人
 * @property clientInfo 客户端信息
 * @property reporter 提交人信息
 * @property handler 处理人信息
 * @property createdAt 创建时间
 * @property updatedAt 更新时间
 */
data class FeedbackReportResponse(
    val id: String,
    val type: String,
    val category: String?,
    val area: String,
    val status: String,
    val content: String,
    val messages: List<FeedbackMessageResponse>,
    val hasAdminReply: Boolean,
    val quota: QuotaInfo,
    val viewerIsReporter: Boolean,
    val viewerCanManage: Boolean,
    val clientInfo: ClientInfoResponse?,
    val reporter: UserInfo,
    val handler: UserInfo?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    data class QuotaInfo(
        val pendingCount: Int,
        val pendingLimit: Int,
        val canAppend: Boolean,
    )

    data class ClientInfoResponse(
        val consent: Boolean,
        val userAgent: String?,
        val ip: String?,
        val ipLocation: String?,
    )

    data class UserInfo(
        val id: String,
        val userName: String,
    )
}

/**
 * 反馈工单列表项
 *
 * @property id 工单 id
 * @property type 工单类型
 * @property category 分类
 * @property status 状态
 * @property content 正文预览
 * @property hasAdminReply 是否有管理员回复
 * @property lastMessageSender 最后消息发送方
 * @property reporterUserId 提交人 id
 * @property reporterName 提交人昵称
 * @property createdAt 创建时间
 * @property updatedAt 更新时间
 */
data class FeedbackReportListItem(
    val id: String,
    val type: String,
    val category: String?,
    val area: String,
    val status: String,
    val content: String,
    val hasAdminReply: Boolean,
    val lastMessageSender: String,
    val reporterUserId: String,
    val reporterName: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * 反馈工单列表响应
 */
data class FeedbackReportListResponse(
    val reports: List<FeedbackReportListItem>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val mine: Boolean,
    val sortBy: String,
    val sortOrder: String,
)
