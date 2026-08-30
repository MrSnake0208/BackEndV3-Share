package com.lhs.share.hub.service.report

import com.lhs.share.common.utils.IpUtil
import com.lhs.share.config.external.ShareProperties
import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.controller.report.request.FeedbackMessageAppendRequest
import com.lhs.share.hub.controller.report.request.FeedbackReportCreateRequest
import com.lhs.share.hub.controller.report.request.FeedbackStatusUpdateRequest
import com.lhs.share.hub.controller.report.response.FeedbackMessageResponse
import com.lhs.share.hub.controller.report.response.FeedbackReportListItem
import com.lhs.share.hub.controller.report.response.FeedbackReportListResponse
import com.lhs.share.hub.controller.report.response.FeedbackReportResponse
import com.lhs.share.hub.repository.FeedbackTicketQueryRepository
import com.lhs.share.hub.repository.FeedbackTicketRepository
import com.lhs.share.hub.repository.MediaAssetRepository
import com.lhs.share.hub.repository.entity.FeedbackClientInfo
import com.lhs.share.hub.repository.entity.FeedbackMessage
import com.lhs.share.hub.repository.entity.FeedbackMessageImage
import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.repository.entity.MediaAsset
import com.lhs.share.hub.service.HubUserInfoService
import com.lhs.share.hub.service.notification.NotificationService
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val feedbackTicketQueryRepository: FeedbackTicketQueryRepository,
    private val feedbackAccessService: FeedbackAccessService,
    private val mediaAssetRepository: MediaAssetRepository,
    private val notificationService: NotificationService,
    private val hubUserInfoService: HubUserInfoService,
    private val properties: ShareProperties,
) {
    private val log = KotlinLogging.logger { }
    private val random = SecureRandom()

    private data class NormalizedFields(
        val type: String,
        val category: String,
    )

    companion object {
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

    private fun validateArea(area: String): String {
        return try {
            FeedbackArea.requireValid(area)
        } catch (e: IllegalArgumentException) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), e.message)
        }
    }

    /**
     * 解析新格式 type/category，并兼容第一版的 type/category/area 组合。
     * 新格式的 category 是板块，旧格式的 category 是反馈性质。
     */
    private fun normalizeCreateFields(request: FeedbackReportCreateRequest): NormalizedFields {
        val type = request.type.trim().uppercase()
        val rawCategory = request.category?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val legacyArea = request.area?.let(::validateArea)

        if (type == FeedbackType.LEGACY_FEEDBACK) {
            val legacyType = rawCategory?.takeIf { it in FeedbackType.all }
            val category = legacyArea
                ?: rawCategory?.takeIf { it in FeedbackArea.all }
                ?: FeedbackArea.OTHER
            return NormalizedFields(legacyType ?: FeedbackType.LEGACY_FEEDBACK, category)
        }
        if (type !in FeedbackType.all) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的工单类型: ${request.type}, 可选: ${FeedbackType.all}")
        }

        val category = when {
            rawCategory == null -> legacyArea ?: if (type == FeedbackType.REPORT) {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "REPORT 必须指定反馈板块")
            } else {
                FeedbackArea.OTHER
            }
            rawCategory in FeedbackArea.all -> {
                if (legacyArea != null && legacyArea != rawCategory) {
                    throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "反馈板块参数冲突: category=$rawCategory, area=$legacyArea")
                }
                rawCategory
            }
            rawCategory == type && legacyArea != null -> legacyArea
            rawCategory in FeedbackType.all -> {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "反馈类型和板块参数冲突: type=$type, category=$rawCategory")
            }
            else -> {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的反馈板块: $rawCategory, 可选: ${FeedbackArea.all}")
            }
        }
        return NormalizedFields(type, category)
    }

    private fun normalizeTypeFilter(type: String): String {
        val normalized = type.trim().uppercase()
        if (normalized !in FeedbackType.all && normalized != FeedbackType.LEGACY_FEEDBACK) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的工单类型: $type, 可选: ${FeedbackType.all}")
        }
        return normalized
    }

    private fun normalizeCategoryFilter(category: String?, area: String?): String? {
        val normalizedCategory = category?.let(::validateArea)
        val normalizedArea = area?.let(::validateArea)
        if (normalizedCategory != null && normalizedArea != null && normalizedCategory != normalizedArea) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "反馈板块参数冲突: category=$category, area=$area")
        }
        return normalizedCategory ?: normalizedArea
    }

    /** 从旧文档推导新 API 语义，保留无法推断的 FEEDBACK 类型。 */
    private fun normalizedTicketFields(ticket: FeedbackTicket): NormalizedFields {
        val rawType = ticket.type.trim().uppercase()
        val rawCategory = ticket.category?.trim()?.uppercase()
        val board = ticket.area?.trim()?.uppercase()?.takeIf { it in FeedbackArea.all }
            ?: rawCategory?.takeIf { it in FeedbackArea.all }
            ?: FeedbackArea.OTHER
        val type = if (rawType == FeedbackType.LEGACY_FEEDBACK) {
            rawCategory?.takeIf { it in FeedbackType.all } ?: FeedbackType.LEGACY_FEEDBACK
        } else {
            rawType
        }
        return NormalizedFields(type, board)
    }

    /**
     * 创建反馈工单
     */
    fun create(userId: String, request: FeedbackReportCreateRequest): FeedbackReportResponse {
        val fields = normalizeCreateFields(request)

        // 校验 media_ids 归属
        val mediaAssets = validateMediaIds(userId, request.mediaIds)

        // 生成工单 id
        val ticketId = generateId("rpt_")

        // 获取客户端信息
        val clientInfo = if (request.clientInfoConsent) {
            buildClientInfo()
        } else {
            null
        }

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
            type = fields.type,
            category = fields.category,
            area = fields.category,
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
        log.info { "反馈工单创建成功: id=${saved.id}, userId=$userId, type=${fields.type}, category=${fields.category}" }
        notifyCategoryReceivers(saved)
        return toResponse(saved, userId)
    }

    /**
     * 查询反馈工单列表
     */
    fun list(
        currentUserId: String,
        page: Int,
        pageSize: Int,
        status: String?,
        type: String?,
        category: String?,
        area: String?,
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
        val normalizedType = type?.let(::normalizeTypeFilter)
        val normalizedCategory = normalizeCategoryFilter(category, area)
        if (sortBy !in setOf("createdAt", "updatedAt")) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "sortBy 只允许 createdAt 或 updatedAt")
        }
        if (!sortOrder.equals("asc", ignoreCase = true) && !sortOrder.equals("desc", ignoreCase = true)) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "sortOrder 只允许 asc 或 desc")
        }

        val grantedAreas = feedbackAccessService.manageableAreas(currentUserId)
        if (!mine && grantedAreas.isEmpty()) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有任何反馈模块的管理权限")
        }
        if (!mine && normalizedCategory != null && normalizedCategory !in grantedAreas) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有 $normalizedCategory 模块的管理权限")
        }

        val pageable = PageRequest.of(
            page - 1,
            pageSize,
            if (sortOrder.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC,
            sortBy,
        )
        val resultPage = feedbackTicketQueryRepository.search(
            reporterUserId = if (mine) currentUserId else reporterUserId,
            manageableCategories = if (mine) null else grantedAreas,
            status = normalizedStatus,
            type = normalizedType,
            category = normalizedCategory,
            keyword = keyword,
            pageable = pageable,
        )
        val userDict = hubUserInfoService.getDict(resultPage.content.map { it.reporterUserId }.toSet())
        val items = resultPage.content.map { ticket ->
            val fields = normalizedTicketFields(ticket)
            FeedbackReportListItem(
                id = checkNotNull(ticket.id),
                type = fields.type,
                category = fields.category,
                area = fields.category,
                status = ticket.status,
                content = ticket.content.take(100),
                hasAdminReply = ticket.hasAdminReply || ticket.messages.any { it.senderKind == "ADMIN" },
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
            mine = mine,
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

        val isReporter = ticket.reporterUserId == currentUserId
        val fields = normalizedTicketFields(ticket)
        if (!isReporter && !feedbackAccessService.canView(currentUserId, fields.category)) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有该反馈模块的查看权限")
        }

        return toResponse(ticket, currentUserId)
    }

    /**
     * 追加消息
     */
    fun appendMessage(currentUserId: String, ticketId: String, request: FeedbackMessageAppendRequest): FeedbackReportResponse {
        val ticket = feedbackTicketRepository.findById(ticketId).orElseThrow {
            ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
        }

        val isReporter = ticket.reporterUserId == currentUserId
        val fields = normalizedTicketFields(ticket)
        val isManager = feedbackAccessService.canManage(currentUserId, fields.category)
        if (!isReporter && !isManager) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有该反馈模块的管理权限")
        }

        // 仅 OPEN 工单可追加
        if (ticket.status != "OPEN") {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "仅 OPEN 状态的工单可追加消息")
        }

        // 提交人补充受连续消息限制；模块管理员回复会重置计数。
        if (!isManager) {
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
        val senderKind = if (isManager) "ADMIN" else "REPORTER"
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
    fun updateStatus(currentUserId: String, ticketId: String, request: FeedbackStatusUpdateRequest): FeedbackReportResponse {
        val ticket = feedbackTicketRepository.findById(ticketId).orElseThrow {
            ApiResultException(HttpStatus.NOT_FOUND.value(), "工单不存在: $ticketId")
        }

        val isReporter = ticket.reporterUserId == currentUserId
        val fields = normalizedTicketFields(ticket)
        val isManager = feedbackAccessService.canManage(currentUserId, fields.category)
        if (!isReporter && !isManager) {
            throw ApiResultException(HttpStatus.FORBIDDEN.value(), "没有该反馈模块的管理权限")
        }

        val newStatus = request.status.trim().uppercase()
        if (newStatus !in VALID_STATUSES) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的状态: $newStatus")
        }

        // 权限校验状态变更
        if (isManager) {
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
            handlerUserId = if (isManager) currentUserId else ticket.handlerUserId,
            handledAt = if (isManager) now else ticket.handledAt,
            updatedAt = now,
        )

        val saved = feedbackTicketRepository.save(updatedTicket)
        log.info { "反馈工单状态更新: ticketId=$ticketId, ${ticket.status} → $newStatus, by=$currentUserId" }

        // 管理员改状态后, 给提交人生成通知 (状态有实际变化)
        if (isManager && ticket.status != newStatus) {
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

    private fun notifyCategoryReceivers(ticket: FeedbackTicket) {
        val ticketId = checkNotNull(ticket.id)
        val category = normalizedTicketFields(ticket).category
        val categoryLabel = FeedbackArea.labels[category] ?: category
        feedbackAccessService.receiverUserIds(category)
            .filter { it != ticket.reporterUserId }
            .forEach { receiverId ->
                notificationService.create(
                    userId = receiverId,
                    kind = "FEEDBACK_ASSIGNED",
                    title = "$categoryLabel 有新反馈",
                    body = ticket.content.take(100),
                    refType = "FEEDBACK",
                    refId = ticketId,
                )
            }
    }

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
        if (mediaIds.size != mediaIds.toSet().size) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "媒体 ID 不能重复")
        }
        val assets = mediaAssetRepository.findAllById(mediaIds)
        for (asset in assets) {
            if (asset.ownerUserId != userId) {
                throw ApiResultException(HttpStatus.FORBIDDEN.value(), "媒体 ${asset.id} 不属于当前用户")
            }
            if (asset.deletedAt != null) {
                throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "媒体 ${asset.id} 已被删除")
            }
        }
        // 确保所有请求的 mediaIds 都存在
        val assetsById = assets.associateBy { it.id }
        val missingIds = mediaIds.filterNot { assetsById.containsKey(it) }
        if (missingIds.isNotEmpty()) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "媒体不存在: $missingIds")
        }
        return mediaIds.map { mediaId -> assetsById.getValue(mediaId) }
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
        val fields = normalizedTicketFields(ticket)
        val isReporter = ticket.reporterUserId == currentUserId
        val reporterInfo = hubUserInfoService.get(ticket.reporterUserId)

        // 计算配额
        val pendingCount = if (ticket.status == "OPEN" && isReporter) {
            countPendingMessagesAfterLastAdminReply(ticket)
        } else {
            0
        }
        val isManager = feedbackAccessService.canManage(currentUserId, fields.category)
        val canAppend = ticket.status == "OPEN" && (isManager || (isReporter && pendingCount < PENDING_LIMIT))

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
                    FeedbackMessageResponse.ImageInfo(id = img.id, url = toPublicMediaUrl(img.url))
                },
                createdAt = msg.createdAt,
            )
        }

        return FeedbackReportResponse(
            id = ticket.id!!,
            type = fields.type,
            category = fields.category,
            area = fields.category,
            status = ticket.status,
            content = ticket.content,
            messages = messageResponses,
            hasAdminReply = ticket.hasAdminReply || ticket.messages.any { it.senderKind == "ADMIN" },
            quota = FeedbackReportResponse.QuotaInfo(
                pendingCount = pendingCount,
                pendingLimit = PENDING_LIMIT,
                canAppend = canAppend,
            ),
            viewerIsReporter = isReporter,
            viewerCanManage = isManager,
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

    private fun toPublicMediaUrl(url: String): String {
        val normalized = url.trim()
        if (normalized.isEmpty()) return url
        if (normalized.startsWith("http://", ignoreCase = true) || normalized.startsWith("https://", ignoreCase = true)) {
            return url
        }
        if (!normalized.startsWith('/')) return url
        return properties.info.publicBaseUrl.trimEnd('/') + normalized
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
