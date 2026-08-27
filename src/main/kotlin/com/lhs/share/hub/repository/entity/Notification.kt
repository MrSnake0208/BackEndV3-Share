package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.io.Serializable
import java.time.Instant

/**
 * 站内通知
 *
 * 存储在 HubBackend 的 notifications 集合。
 * 管理员回复/更新状态后，系统给提交人生成通知。
 */
@Document("notifications")
@CompoundIndexes(
    CompoundIndex(name = "user_id_created_at_desc", def = "{'userId': 1, 'createdAt': -1}"),
    CompoundIndex(name = "user_id_read_at", def = "{'userId': 1, 'readAt': 1}"),
)
data class Notification(
    @Id
    val id: String? = null, // ntf_<hex>
    /**
     * 通知所属用户 id
     */
    val userId: String,
    /**
     * 通知类型：FEEDBACK_REPLY | FEEDBACK_STATUS_UPDATED
     */
    val kind: String,
    val title: String,
    val body: String,
    /**
     * 关联对象类型：FEEDBACK
     */
    val refType: String,
    /**
     * 关联对象 id：rpt_<hex>
     */
    val refId: String,
    /**
     * 阅读时间，null 表示未读
     */
    val readAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
) : Serializable