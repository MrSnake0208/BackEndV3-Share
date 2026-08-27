package com.lhs.share.hub.service.report

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.report.request.FeedbackMessageAppendRequest
import com.lhs.share.hub.controller.report.request.FeedbackReportCreateRequest
import com.lhs.share.hub.controller.report.request.FeedbackStatusUpdateRequest
import com.lhs.share.hub.controller.report.response.FeedbackMessageResponse
import com.lhs.share.hub.controller.report.response.FeedbackReportListItem
import com.lhs.share.hub.controller.report.response.FeedbackReportListResponse
import com.lhs.share.hub.controller.report.response.FeedbackReportResponse
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.MediaAssetRepository
import com.lhs.share.hub.repository.entity.FeedbackClientInfo
import com.lhs.share.hub.repository.entity.FeedbackMessage
import com.lhs.share.hub.repository.entity.FeedbackMessageImage
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.repository.entity.MediaAsset
import com.lhs.share.hub.service.HubUserInfoService
import com.lhs.share.hub.service.notification.NotificationService
import com.lhs.share.service.UserService
import com.lhs.share.common.utils.IpUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.SecureRandom
import java.time.Instant

/**
 * 反馈工单服务
 *
 * 处理反馈工单的创建、列表、详情、追加消息、状态变更等业务逻辑。
 */
@Service
class FeedbackReportService(
    private val feedbackTicketRepository: FeedbackTicketRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val notificationService: NotificationService,
    private val hubUserInfoService: HubUserInfoService,
    private val userService: UserService,
    private val properties: ShareProperties,
) {
    private val log = KotlinLogging.logger { }
    private val random = SecureRandom()

    companion object {
        /** 当前支持落库的工单类型 */
        private const val FEEDBACK_TYPE = "FEEDBACK"

        /** 允许的反馈分类 */
        private val VALID_CATEGORIES = setOf("FEATURE", "BUG", "CONTENT", "ACCOUNT", "OTHER")

        /** 允许的状态 */
        private val VALID_STATUSES = setOf("OPEN", "RESOLVED", "DISMISSED")

        /** 每用户最多待处理工单数 */
        private const val PENDING_LIMIT = 3

        /** 消息中最多图片数 */
        private const val MAX_MEDIA_PER_MESSAGE = 3

        /** 管理员可设的状态 */
        private val ADMIN_ALLOWED_STATUSES = setOf("OPEN", "RESOLVED", "DISMISSED")

        /** 提交人可设的状态(只能关闭自己的 OPEN) */
        private val REPORTER_ALLOWED_STATUSES = setOf("RESOLVED")
    }

    /**
     * 创建反馈工单只接受规范的 FEEDBACK 类型和分类值。
     */
    private fun validateCreateTypeAndCategory(request: FeedbackReportCreateRequest): String {
        if (request.type != FEEDBACK_TYPE) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的工单类型: ${request.type}")
        }
        val category = request.category
        if (category == null || category !in VALID_CATEGORIES) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的分类: $category, 可选: $VALID_CATEGORIES")
        }
        return category
    }

    /**
     * 创建反馈工单
     */
    fun create(userId: String, request: FeedbackReportCreateRequest): FeedbackReportResponse {
        val type = FEEDBACK_TYPE
        val category = validateCreateTypeAndCategory(request)

        // 校验 media_ids 归属
        val mediaAssets = validateMediaIds(userId, request.mediaIds)

        // 生成工单 id
        val ticketId = generateId("rpt_")

        // 获取客户端信息
        val clientInfo = if (request.clientInfoConsent) {
            buildClientInfo()
        } else null

        // 构建首条消息
        val firstMessage = FeedbackMessage(
            id = generateId("rpm_"),
            senderKind = "REPORTER",
            authorUserId = userId,
            content = request.content,
            images = mediaAssets.map { FeedbackMessageImage(id = it.id!!, url = it.storagePath) },
            createdAt = Instant.now(),
        )

        val now = Instant.now()
        val ticket = FeedbackTicket(
            id = ticketId,
            type = type,
            category = category,
            status = "OPEN",
            reporterUserId = userId,
            content = request.content,
            clientInfoConsent = request.clientInfoConsent,
            clientInfo = clientInfo,
            messages = listOf(firstMessage),
            createdAt = now,
            updatedAt = now,
        )

        val saved = feedbackTicketRepository.save(ticket)
        log.info { "反馈工单创建成功: id=${saved.id}, userId=$userId, category=$category" }
        return toResponse(saved, userId)
    }

    /**
     * 查询反馈工单列表
     */
    fun list(
        currentUserId: String,
        isAdmin: Boolean,
        page: Int,
        pageSize: Int,
        status: String?,
        type: String?,
        mine: Boolean,
        reporterUserId: String?,
        keyword: String?,
        sortBy: String,
        sortOrder: String,
    ): FeedbackReportListResponse {
        if (page < 1) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "page 必须大于等于 1")
        }
        if (pageSize !in 1..100) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "pageSize 必须在 1..100 之间")
        }

        val normalizedStatus = status?.trim()?.uppercase()
        if (normalizedStatus != null && normalizedStatus !in VALID_STATUSES) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的状态: $status")
        }
        val normalizedType = type?.trim()?.uppercase()
        if (normalizedType != null && normalizedType != FEEDBACK_TYPE) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的工单类型: $type")
        }
        if (sortBy !in setOf("createdAt", "updatedAt")) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "sortBy 只允许 createdAt 或 updatedAt")
        }
        if (!sortOrder.equals("asc", ignoreCase = true) && !sortOrder.equals("desc", ignoreCase = true)) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "sortOrder 只允许 asc 或 desc")
        }

        val actualMine = if (!isAdmin) true else mine
        val actualReporterUserId = if (actualMine) currentUserId else reporterUserId
        val escapedKeyword = keyword?.takeIf { it.isNotBlank() }?.let { Regex.escape(it.trim()) }

        val pageable = PageRequest.of(
            page - 1,
            pageSize,
            if (sortOrder.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC,
            sortBy,
        )

        val resultPage: Page<FeedbackTicket> = when {
            // 搜索关键词
            escapedKeyword != null && actualReporterUserId != null ->
                feedbackTicketRepository.searchByReporterUserIdAndKeywordOrderByCreatedAtDesc(
                    actualReporterUserId, escapedKeyword, pageable,
                )
            escapedKeyword != null ->
                feedbackTicketRepository.searchByKeywordOrderByCreatedAtDesc(escapedKeyword, pageable)

            // 按用户+状态+类型
            actualReporterUserId != null && normalizedStatus != null && normalizedType != null ->
                feedbackTicketRepository.findByReporterUserIdAndStatusAndTypeOrderByCreatedAtDesc(
                    actualReporterUserId, normalizedStatus, normalizedType, pageable,
                )
            // 按用户+状态
            actualReporterUserId != null && normalizedStatus != null ->
                feedbackTicketRepository.findByReporterUserIdAndStatusOrderByCreatedAtDesc(
                    actualReporterUserId, normalizedStatus, pageable,
                )
            // 按用户+类型
            actualReporterUserId != null && normalizedType != null ->
                feedbackTicketRepository.findByReporterUserIdAndTypeOrderByCreatedAtDesc(
                    actualReporterUserId, normalizedType, pageable,
                )
            // 按用户
            actualReporterUserId != null ->
                feedbackTicketRepository.findByReporterUserIdOrderByCreatedAtDesc(
                    actualReporterUserId, pageable,
                )
            // 按状态+类型
            normalizedStatus != null && normalizedType != null ->
                feedbackTicketRepository.findByStatusAndTypeOrderByCreatedAtDesc(normalizedStatus, normalizedType, pageable)
            // 按状态
            normalizedStatus != null ->
                feedbackTicketRepository.findByStatusOrderByCreatedAtDesc(normalizedStatus, pageable)
            // 按类型
            normalizedType != null ->
                feedbackTicketRepository.findByTypeOrderByCreatedAtDesc(normalizedType, pageable)
            // 全部
            else ->
                feedbackTicketRepository.findAllByOrderByCreatedAtDesc(pageable)
        }

        val userIds = resultPage.content.map { it.reporterUserId }.toSet()
        val userDict = hubUserInfoService.getDict(userIds)

        val items = resultPage.content.map { ticket ->
            FeedbackReportListItem(
                id = ticket.id!!,
                type = ticket.type,
                category = ticket.category,
                status = ticket.status,
                content = ticket.content.take(100),
                hasAdminReply = ticket.hasAdminReply,
                lastMessageSender = ticket.lastMessageSender,
                reporterUserId = ticket.reporterUserId,
                reporterName = userDict[ticket.reporterUserId]?.userName,
                createdAt = ticket.createdAt,
                updatedAt = ticket.updatedAt,
            )
        }

        return FeedbackReportListResponse(
            reports = items,
            total = resultPage.totalElements,
            page = page,
            pageSize = pageSize,
            mine = actualMine,
            sortBy = sortBy,
            sortOrder = sortOrder,
        )
    }

    /**
     * 获取工单详情
     */
    fun getById(currentUserId: String, ticketId: String): FeedbackReportResponse {
        val ticket = feedbackTicketRepository.findById(ticketId).orElseThrow {
            ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
        }

        // 权限: 提交人本人或管理员
        val isReporter = ticket.reporterUserId == currentUserId
        val isAdmin = userService.hasAdminPrivileges(currentUserId)
        if (!isReporter && !isAdmin) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "无权查看该工单")
        }

        return toResponse(ticket, currentUserId)
    }

    /**
     * 追加消息
     */
    fun appendMessage(
        currentUserId: String,
        ticketId: String,
        request: FeedbackMessageAppendRequest,
        isAdmin: Boolean,
    ): FeedbackReportResponse {
        val ticket = feedbackTicketRepository.findById(ticketId).orElseThrow {
            ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
        }

        // 权限: 提交人本人或管理员
        val isReporter = ticket.reporterUserId == currentUserId
        if (!isReporter && !isAdmin) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "无权操作该工单")
        }

        // 仅 OPEN 工单可追加
        if (ticket.status != "OPEN") {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "仅 OPEN 状态的工单可追加消息")
        }

        // 非管理员: 检查连续补充限制
        if (!isAdmin) {
            val pendingCount = countPendingMessagesAfterLastAdminReply(ticket)
            if (pendingCount >= PENDING_LIMIT) {
                throw ApiResultException(
                    HttpStatus.BAD_REQUEST.value(),
                    "连续补充已达上限($PENDING_LIMIT 条), 请等待管理员回复后继续补充",
                )
            }
        }

        // 校验 media_ids 归属
        val mediaAssets = validateMediaIds(currentUserId, request.mediaIds)

        // 构建消息
        val senderKind = if (isAdmin) "ADMIN" else "REPORTER"
        val message = FeedbackMessage(
            id = generateId("rpm_"),
            senderKind = senderKind,
            authorUserId = currentUserId,
            content = request.content,
            images = mediaAssets.map { FeedbackMessageImage(id = it.id!!, url = it.storagePath) },
            createdAt = Instant.now(),
        )

        val now = Instant.now()
        val updatedMessages = ticket.messages + message
        val updatedTicket = ticket.copy(
            messages = updatedMessages,
            lastMessageSender = senderKind,
            updatedAt = now,
            hasAdminReply = if (senderKind == "ADMIN") true else ticket.hasAdminReply,
            adminReply = if (senderKind == "ADMIN") request.content.take(200) else ticket.adminReply,
        )

        val saved = feedbackTicketRepository.save(updatedTicket)
        log.info { "反馈工单消息追加: ticketId=$ticketId, sender=$senderKind" }

        // 管理员回复后, 给提交人生成通知
        if (senderKind == "ADMIN") {
            notificationService.create(
                userId = ticket.reporterUserId,
                kind = "FEEDBACK_REPLY",
                title = "你的反馈有新回复",
                body = "管理员回复了你的反馈: \"${request.content.take(100)}\"",
                refType = "FEEDBACK",
                refId = ticketId,
            )
        }

        return toResponse(saved, currentUserId)
    }

    /**
     * 更新工单状态
     */
    fun updateStatus(
        currentUserId: String,
        ticketId: String,
        request: FeedbackStatusUpdateRequest,
        isAdmin: Boolean,
    ): FeedbackReportResponse {
        val ticket = feedbackTicketRepository.findById(ticketId).orElseThrow {
            ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
        }

        // 权限: 提交人本人或管理员
        val isReporter = ticket.reporterUserId == currentUserId
        if (!isReporter && !isAdmin) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "无权操作该工单")
        }

        val newStatus = request.status.trim().uppercase()
        if (newStatus !in VALID_STATUSES) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的状态: $newStatus")
        }

        // 权限校验状态变更
        if (isAdmin) {
            if (newStatus !in ADMIN_ALLOWED_STATUSES) {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "管理员不允许设置状态: $newStatus")
            }
        } else {
            // 提交人只能关闭自己的 OPEN 工单
            if (newStatus !in REPORTER_ALLOWED_STATUSES) {
                throw ApiResultException(HttpStatus.FORBIDDEN.value(), "提交人只能将工单设为 RESOLVED")
            }
            if (ticket.status != "OPEN") {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "仅 OPEN 状态的工单可自行关闭")
            }
        }

        // 状态无变化, 不更新
        if (ticket.status == newStatus) {
            return toResponse(ticket, currentUserId)
        }

        val now = Instant.now()
        val updatedTicket = ticket.copy(
            status = newStatus,
            handlerUserId = if (isAdmin) currentUserId else ticket.handlerUserId,
            handledAt = if (isAdmin) now else ticket.handledAt,
            updatedAt = now,
        )

        val saved = feedbackTicketRepository.save(updatedTicket)
        log.info { "反馈工单状态更新: ticketId=$ticketId, ${ticket.status} → $newStatus, by=$currentUserId" }

        // 管理员改状态后, 给提交人生成通知 (状态有实际变化)
        if (isAdmin && ticket.status != newStatus) {
            val statusLabel = when (newStatus) {
                "RESOLVED" -> "已处理"
                "DISMISSED" -> "已忽略"
                else -> newStatus
            }
            notificationService.create(
                userId = ticket.reporterUserId,
                kind = "FEEDBACK_STATUS_UPDATED",
                title = "你的反馈状态已更新",
                body = "管理员将你的反馈状态更新为: $statusLabel",
                refType = "FEEDBACK",
                refId = ticketId,
            )
        }

        return toResponse(saved, currentUserId)
    }

    // ========== 内部方法 ==========

    /**
     * 校验媒体 id 列表: 最多 3 个, 必须是当前用户自己的未删除媒体
     */
    private fun validateMediaIds(userId: String, mediaIds: List<String>): List<MediaAsset> {
        if (mediaIds.isEmpty()) return emptyList()
        if (mediaIds.size > MAX_MEDIA_PER_MESSAGE) {
            throw ApiResultException(
                HttpStatus.BAD_REQUEST.value(),
                "图片数量超过上限($MAX_MEDIA_PER_MESSAGE 张)",
            )
        }
        val assets = mediaAssetRepository.findAllById(mediaIds)
        for (asset in assets) {
            if (asset.ownerUserId != userId) {
                throw ApiResultException(HttpStatus.FORBIDDEN.value(), "媒体 $asset.id 不属于当前用户")
            }
            if (asset.deletedAt != null) {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "媒体 ${asset.id} 已被删除")
            }
        }
        // 确保所有请求的 mediaIds 都存在
        val foundIds = assets.map { it.id }.toSet()
        val missingIds = mediaIds.filter { it !in foundIds }
        if (missingIds.isNotEmpty()) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "媒体不存在: $missingIds")
        }
        return assets
    }

    /**
     * 统计最后一次管理员回复后, 提交人连续补充的消息数
     */
    private fun countPendingMessagesAfterLastAdminReply(ticket: FeedbackTicket): Int {
        val lastAdminIndex = ticket.messages.lastIndexOfLast { it.senderKind == "ADMIN" }
        if (lastAdminIndex < 0) {
            // 首条消息已包含在工单创建请求中, 只统计后续补充消息
            return (ticket.messages.count { it.senderKind == "REPORTER" } - 1).coerceAtLeast(0)
        }
        // 统计最后一条管理员回复之后的提交人消息数
        return ticket.messages.drop(lastAdminIndex + 1).count { it.senderKind == "REPORTER" }
    }

    /**
     * 构建客户端信息
     */
    private fun buildClientInfo(): FeedbackClientInfo {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        return FeedbackClientInfo(
            userAgent = request?.getHeader("User-Agent"),
            ip = request?.let { IpUtil.getIpAddr(it) },
            ipLocation = null, // 第一期暂不实现 IP 地理定位
        )
    }

    /**
     * 生成带前缀的唯一 id
     */
    private fun generateId(prefix: String): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "${"0123456789abcdef"[v shr 4]}${"0123456789abcdef"[v and 0x0F]}"
        }
        return "$prefix$hex"
    }

    /**
     * 将实体转换为响应 DTO
     */
    private fun toResponse(ticket: FeedbackTicket, currentUserId: String): FeedbackReportResponse {
        val isReporter = ticket.reporterUserId == currentUserId
        val reporterInfo = hubUserInfoService.get(ticket.reporterUserId)
        val handlerInfo = ticket.handlerUserId?.let { hubUserInfoService.get(it) }

        // 计算配额
        val pendingCount = if (ticket.status == "OPEN" && isReporter) {
            countPendingMessagesAfterLastAdminReply(ticket)
        } else 0
        val isAdmin = userService.hasAdminPrivileges(currentUserId)
        val canAppend = ticket.status == "OPEN" && (isAdmin || (isReporter && pendingCount < PENDING_LIMIT))

        val messageResponses = ticket.messages.map { msg ->
            val authorInfo = hubUserInfoService.get(msg.authorUserId)
            FeedbackMessageResponse(
                id = msg.id,
                senderKind = msg.senderKind,
                author = FeedbackMessageResponse.AuthorInfo(
                    id = msg.authorUserId,
                    userName = authorInfo?.userName ?: "未知用户",
                ),
                content = msg.content,
                images = msg.images.map { img ->
                    FeedbackMessageResponse.ImageInfo(id = img.id, url = img.url)
                },
                createdAt = msg.createdAt,
            )
        }

        return FeedbackReportResponse(
            id = ticket.id!!,
            type = ticket.type,
            category = ticket.category,
            status = ticket.status,
            content = ticket.content,
            messages = messageResponses,
            quota = FeedbackReportResponse.QuotaInfo(
                pendingCount = pendingCount,
                pendingLimit = PENDING_LIMIT,
                canAppend = canAppend,
            ),
            viewerIsReporter = isReporter,
            clientInfo = ticket.clientInfo?.let {
                FeedbackReportResponse.ClientInfoResponse(
                    consent = ticket.clientInfoConsent,
                    userAgent = it.userAgent,
                    ip = it.ip,
                    ipLocation = it.ipLocation,
                )
            },
            reporter = FeedbackReportResponse.UserInfo(
                id = ticket.reporterUserId,
                userName = reporterInfo?.userName ?: "未知用户",
            ),
            handler = ticket.handlerUserId?.let { hid ->
                val hi = hubUserInfoService.get(hid)
                FeedbackReportResponse.UserInfo(
                    id = hid,
                    userName = hi?.userName ?: "未知用户",
                )
            },
            createdAt = ticket.createdAt,
            updatedAt = ticket.updatedAt,
        )
    }
}

/**
 * 查找列表中最后一个匹配条件的元素的索引
 */
private fun <T> List<T>.lastIndexOfLast(predicate: (T) -> Boolean): Int {
    for (i in indices.reversed()) {
        if (predicate(this[i])) return i
    }
    return -1
}