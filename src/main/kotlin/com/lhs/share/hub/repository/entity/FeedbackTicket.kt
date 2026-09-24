package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.io.Serializable
import java.time.Instant

/**
 * Hub 库反馈工单实体(HubBackend.feedback_tickets)
 *
 * 嵌入消息列表 [messages],每一条消息可附带最多 3 张图片。
 * 状态机: OPEN → RESOLVED / DISMISSED
 *
 * @property id 工单 id: rpt_<hex>
 * @property type 反馈类型: BUG / FEATURE / CONTENT / ACCOUNT / REPORT / OTHER
 * @property category 反馈归属板块
 * @property area 旧版板块字段；过渡期与 category 双写
 * @property status OPEN / RESOLVED / DISMISSED
 * @property reporterUserId 提交人用户 id
 * @property handlerUserId 最近处理管理员用户 id
 * @property handledAt 最近处理时间
 * @property targetType 预留:举报目标类型(后续扩展)
 * @property targetId 预留:举报目标 id(后续扩展)
 * @property content 首条正文(用于列表预览与搜索)
 * @property adminReply 最近管理员回复预览
 * @property hasAdminReply 是否有管理员回复
 * @property lastMessageSender 最后一条消息发送方: REPORTER | ADMIN
 * @property clientInfoConsent 是否同意保存客户端信息
 * @property clientInfo 客户端信息(仅 consent=true 时保存)
 * @property diagnostics 应用诊断信息(前端版本 / commit / 构建时间);与 consent 无关,旧文档为 null
 * @property messages 消息列表
 * @property createdAt 创建时间
 * @property updatedAt 更新时间
 */
@Document("feedback_tickets")
@CompoundIndex(name = "idx_reporter_created", def = "{'reporterUserId': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_status_area_created", def = "{'status': 1, 'area': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_status_type_category_created", def = "{'status': 1, 'type': 1, 'category': 1, 'createdAt': -1}")
data class FeedbackTicket(
    @Id
    val id: String? = null,

    /** 反馈类型；FEEDBACK 仅兼容旧数据。 */
    val type: String,

    /** 反馈归属板块。 */
    val category: String? = null,

    /** 旧版反馈归属板块；新记录与 category 双写，旧文档可为空。 */
    val area: String? = null,

    /** 状态: OPEN / RESOLVED / DISMISSED */
    val status: String = "OPEN",

    /** 提交人用户 id */
    @Indexed
    val reporterUserId: String,

    /** 最近处理管理员用户 id */
    val handlerUserId: String? = null,

    /** 最近处理时间 */
    val handledAt: Instant? = null,

    /** 预留:举报目标类型 */
    val targetType: String? = null,

    /** 预留:举报目标 id */
    val targetId: String? = null,

    /** 首条正文(用于列表预览与搜索) */
    val content: String,

    /** 最近管理员回复预览 */
    val adminReply: String? = null,

    /** 是否有管理员回复 */
    val hasAdminReply: Boolean = false,

    /** 最后一条消息发送方 */
    val lastMessageSender: String = "REPORTER",

    /** 是否同意保存客户端信息 */
    val clientInfoConsent: Boolean = false,

    /** 客户端信息(仅 consent=true 时保存) */
    val clientInfo: FeedbackClientInfo? = null,

    /** 应用诊断信息(前端版本 / commit / 构建时间);与 clientInfoConsent 无关,旧文档为 null */
    val diagnostics: FeedbackDiagnostics? = null,

    /** 消息列表 */
    val messages: List<FeedbackMessage> = emptyList(),

    /** 创建时间 */
    val createdAt: Instant = Instant.now(),

    /** 更新时间 */
    val updatedAt: Instant = Instant.now(),
) : Serializable
