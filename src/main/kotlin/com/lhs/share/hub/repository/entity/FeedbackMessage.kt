package com.lhs.share.hub.repository.entity

import java.time.Instant

/**
 * 反馈工单消息(嵌入 [FeedbackTicket])
 *
 * @property id 消息 id: rpm_<hex>
 * @property senderKind REPORTER | ADMIN
 * @property authorUserId 发送人用户 id
 * @property content 消息正文
 * @property images 消息图片列表
 * @property createdAt 发送时间
 */
data class FeedbackMessage(
    val id: String,
    val senderKind: String,
    val authorUserId: String,
    val content: String,
    val images: List<FeedbackMessageImage> = emptyList(),
    val createdAt: Instant = Instant.now(),
)

/**
 * 消息中的图片引用
 *
 * @property id 媒体资产 id: med_<hex>
 * @property url 图片访问路径: /media/xxx.webp
 */
data class FeedbackMessageImage(
    val id: String,
    val url: String,
)