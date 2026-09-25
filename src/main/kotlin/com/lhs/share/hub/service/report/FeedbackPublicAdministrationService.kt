package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.report.request.FeedbackMergeRequest
import com.lhs.share.hub.controller.report.request.FeedbackPublicStatusRequest
import com.lhs.share.hub.controller.report.request.FeedbackPublishRequest
import com.lhs.share.hub.repository.FeedbackSupportRepository
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.entity.FeedbackSupport
import com.lhs.share.hub.repository.entity.FeedbackTicket
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant

/**
 * 反馈公开管理服务:发布 / 取消公开 / 修改公开状态 / 合并重复反馈。
 *
 * 权限沿用反馈板块管理权限([FeedbackAccessService.canManage]);所有写操作使用定向 update,
 * 只改动公开字段或合并指向,不会覆盖并发的消息/状态写入。
 */
@Service
class FeedbackPublicAdministrationService(
    private val ticketRepository: FeedbackTicketRepository,
    private val queryRepository: FeedbackTicketQueryRepository,
    private val supportRepository: FeedbackSupportRepository,
    private val accessService: FeedbackAccessService,
) {
    private val random = SecureRandom()

    companion object {
        private const val MAX_MERGE_HOPS = 50
    }

    /** 发布到反馈广场;首次发布记录 publishedAt。 */
    fun publish(adminUserId: String, ticketId: String, request: FeedbackPublishRequest): FeedbackTicket {
        val ticket = requireTicket(ticketId)
        requireManage(adminUserId, categoryOf(ticket))
        val title = request.publicTitle.trim()
        if (title.isEmpty()) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "公开标题不能为空")
        }
        val summary = request.publicSummary?.trim()?.takeIf { it.isNotEmpty() }
        val status = PublicFeedbackStatus.normalizeOrNull(request.publicStatus) ?: PublicFeedbackStatus.COLLECTING
        val now = Instant.now()
        return queryRepository.setPublicInfo(
            ticketId = ticketId,
            visibility = FeedbackVisibility.PUBLIC,
            publicTitle = title,
            publicSummary = summary,
            publicStatus = status,
            publishedAt = ticket.publishedAt ?: now,
            publicUpdatedAt = now,
        ) ?: throw ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
    }

    /** 取消公开;保留 FeedbackSupport 与已填写的公开字段,便于再次公开时恢复。 */
    fun unpublish(adminUserId: String, ticketId: String): FeedbackTicket {
        val ticket = requireTicket(ticketId)
        requireManage(adminUserId, categoryOf(ticket))
        val now = Instant.now()
        return queryRepository.setPublicInfo(
            ticketId = ticketId,
            visibility = FeedbackVisibility.PRIVATE,
            publicTitle = ticket.publicTitle,
            publicSummary = ticket.publicSummary,
            publicStatus = ticket.publicStatus,
            publishedAt = ticket.publishedAt,
            publicUpdatedAt = now,
        ) ?: throw ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
    }

    /** 修改公开开发状态;进入 COMPLETED 时记录 completedAt,离开时清空。 */
    fun updatePublicStatus(adminUserId: String, ticketId: String, request: FeedbackPublicStatusRequest): FeedbackTicket {
        val ticket = requireTicket(ticketId)
        requireManage(adminUserId, categoryOf(ticket))
        if (!FeedbackVisibility.isPublic(ticket.visibility)) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "只有已公开的反馈可以修改公开状态")
        }
        val status = PublicFeedbackStatus.normalizeOrNull(request.publicStatus)
            ?: throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "公开状态不能为空")
        val now = Instant.now()
        val completedAt = if (status == PublicFeedbackStatus.COMPLETED) ticket.completedAt ?: now else null
        return queryRepository.setPublicStatus(
            ticketId = ticketId,
            publicStatus = status,
            publicUpdatedAt = now,
            completedAt = completedAt,
        ) ?: throw ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
    }

    /**
     * 合并重复反馈:把 [sourceId] 合并到 [FeedbackMergeRequest.targetFeedbackId] 的最终主反馈。
     *
     * 只写 source.mergedIntoId,源记录继续存在;禁止自合并、循环合并与把公开反馈并入未公开反馈。
     */
    fun merge(adminUserId: String, sourceId: String, request: FeedbackMergeRequest): FeedbackTicket {
        val source = requireTicket(sourceId)
        requireManage(adminUserId, categoryOf(source))
        if (source.mergedIntoId != null) {
            throw ApiResultException(HttpStatus.CONFLICT.value(), "该反馈已经合并,不能再作为合并来源")
        }
        val targetId = request.targetFeedbackId.trim()
        if (targetId == sourceId) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "不能把反馈合并到自身")
        }
        val target = requireTicket(targetId)
        requireManage(adminUserId, categoryOf(target))
        val root = resolveRoot(target) ?: throw ApiResultException(HttpStatus.CONFLICT.value(), "目标反馈的合并链异常")
        val rootId = checkNotNull(root.id)
        if (rootId == sourceId) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "合并会形成循环")
        }
        if (FeedbackVisibility.isPublic(source.visibility) && !FeedbackVisibility.isPublic(root.visibility)) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "公开反馈不能合并到未公开反馈")
        }
        val updated = queryRepository.setMergedInto(sourceId, rootId)
            ?: throw ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $sourceId")
        queryRepository.incrementMergedCount(rootId)
        autoSupport(root, source.reporterUserId)
        return updated
    }

    // ========== 内部方法 ==========

    /** 合并后若源提交人尚未支持主反馈,补一次支持;同一用户不重复计票。 */
    private fun autoSupport(root: FeedbackTicket, reporterUserId: String) {
        val rootId = checkNotNull(root.id)
        if (supportRepository.existsByFeedbackIdAndUserId(rootId, reporterUserId)) return
        try {
            supportRepository.save(FeedbackSupport(id = generateSupportId(), feedbackId = rootId, userId = reporterUserId))
            queryRepository.incrementSupportCount(rootId)
        } catch (_: DuplicateKeyException) {
            // 并发下已记录,忽略。
        }
    }

    private fun requireTicket(ticketId: String): FeedbackTicket = ticketRepository.findById(ticketId).orElseThrow {
        ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
    }

    private fun requireManage(adminUserId: String, category: String) {
        if (!accessService.canManage(adminUserId, category)) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有 $category 反馈模块的管理权限")
        }
    }

    /** 与详情授权一致的板块解析:area(合法)优先,其次 category,最后 OTHER。 */
    private fun categoryOf(ticket: FeedbackTicket): String {
        ticket.area?.trim()?.uppercase()?.takeIf { it in FeedbackArea.all }?.let { return it }
        ticket.category?.trim()?.uppercase()?.takeIf { it in FeedbackArea.all }?.let { return it }
        return FeedbackArea.OTHER
    }

    /** 沿 mergedIntoId 解析最终主反馈;检测到环或断链返回 null。 */
    private fun resolveRoot(ticket: FeedbackTicket): FeedbackTicket? {
        var current = ticket
        val visited = mutableSetOf(checkNotNull(ticket.id))
        var hops = 0
        while (true) {
            val nextId = current.mergedIntoId ?: return current
            if (hops++ >= MAX_MERGE_HOPS || !visited.add(nextId)) return null
            current = ticketRepository.findById(nextId).orElse(null) ?: return null
        }
    }

    private fun generateSupportId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val digits = "0123456789abcdef"
        val hex = bytes.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "" + digits[v shr 4] + digits[v and 0x0F]
        }
        return "sup_" + hex
    }
}
