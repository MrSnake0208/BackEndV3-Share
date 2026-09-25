package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.service.report.FeedbackArea
import com.lhs.share.hub.service.report.FeedbackType
import com.lhs.share.hub.service.report.FeedbackVisibility
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.regex.Pattern

@Repository
class FeedbackTicketQueryRepository(
    @param:Qualifier("hubMongoTemplate") private val template: MongoTemplate,
) {
    fun search(
        reporterUserId: String?,
        manageableCategories: Set<String>?,
        status: String?,
        type: String?,
        category: String?,
        keyword: String?,
        pageable: Pageable,
    ): Page<FeedbackTicket> {
        val filters = mutableListOf<Criteria>()
        reporterUserId?.let { filters += Criteria.where("reporterUserId").`is`(it) }
        manageableCategories
            ?.takeUnless { it == FeedbackArea.all }
            ?.let { filters += categorySetCriteria(it) }
        status?.let { filters += Criteria.where("status").`is`(it) }
        type?.let { filters += typeCriteria(it) }
        category?.let { filters += categorySetCriteria(setOf(it)) }
        keyword?.takeIf { it.isNotBlank() }?.let { value ->
            val pattern = Pattern.compile(Pattern.quote(value.trim()), Pattern.CASE_INSENSITIVE)
            filters += Criteria().orOperator(
                Criteria.where("content").regex(pattern),
                Criteria.where("_id").regex(pattern),
            )
        }

        val criteria = if (filters.isEmpty()) Criteria() else Criteria().andOperator(*filters.toTypedArray())
        val countQuery = Query(criteria)
        val total = template.count(countQuery, FeedbackTicket::class.java)
        val items = template.find(Query(criteria).with(pageable), FeedbackTicket::class.java)
        return PageImpl(items, pageable, total)
    }

    /**
     * 公开反馈检索: 强制 visibility=PUBLIC 且未合并;keyword 只匹配 publicTitle/publicSummary。
     */
    fun publicSearch(
        type: String?,
        publicStatus: String?,
        keyword: String?,
        completedVersionId: String?,
        sort: String,
        pageable: Pageable,
    ): Page<FeedbackTicket> {
        val filters = mutableListOf<Criteria>(
            Criteria.where("visibility").`is`(FeedbackVisibility.PUBLIC),
            Criteria.where("mergedIntoId").`is`(null),
        )
        type?.let { filters += typeCriteria(it) }
        publicStatus?.let { filters += Criteria.where("publicStatus").`is`(it) }
        completedVersionId?.let { filters += Criteria.where("completedVersionId").`is`(it) }
        keyword?.takeIf { it.isNotBlank() }?.let { value ->
            val pattern = Pattern.compile(Pattern.quote(value.trim()), Pattern.CASE_INSENSITIVE)
            filters += Criteria().orOperator(
                Criteria.where("publicTitle").regex(pattern),
                Criteria.where("publicSummary").regex(pattern),
            )
        }
        val criteria = Criteria().andOperator(*filters.toTypedArray())
        val sortSpec = when (sort) {
            "hot" -> Sort.by(Sort.Order.desc("supportCount"), Sort.Order.desc("publishedAt"))
            "updated" -> Sort.by(Sort.Order.desc("publicUpdatedAt"), Sort.Order.desc("publishedAt"))
            else -> Sort.by(Sort.Order.desc("publishedAt"))
        }
        val total = template.count(Query(criteria), FeedbackTicket::class.java)
        val items = template.find(Query(criteria).with(pageable).with(sortSpec), FeedbackTicket::class.java)
        return PageImpl(items, pageable, total)
    }

    /**
     * 相似反馈召回: 只在 PUBLIC 且未合并的反馈中按关键词 regex 粗召回,精确打分由 Service 完成。
     */
    fun searchPublicSimilar(tokens: List<String>, candidateLimit: Int): List<FeedbackTicket> {
        if (tokens.isEmpty()) return emptyList()
        val tokenCriteria = tokens.map { token ->
            val pattern = Pattern.compile(Pattern.quote(token), Pattern.CASE_INSENSITIVE)
            Criteria().orOperator(
                Criteria.where("publicTitle").regex(pattern),
                Criteria.where("publicSummary").regex(pattern),
            )
        }
        val criteria = Criteria().andOperator(
            Criteria.where("visibility").`is`(FeedbackVisibility.PUBLIC),
            Criteria.where("mergedIntoId").`is`(null),
            Criteria().orOperator(*tokenCriteria.toTypedArray()),
        )
        return template.find(Query(criteria).limit(candidateLimit), FeedbackTicket::class.java)
    }

    /** 原子 +1 支持数;visibility 已在查询中限制为 PUBLIC。 */
    fun incrementSupportCount(ticketId: String): FeedbackTicket? = template.findAndModify(
        Query(Criteria.where("_id").`is`(ticketId).and("visibility").`is`(FeedbackVisibility.PUBLIC)),
        Update().inc("supportCount", 1),
        FindAndModifyOptions.options().returnNew(true),
        FeedbackTicket::class.java,
    )

    /** 原子 -1 支持数,并保证 supportCount 不为负。 */
    fun decrementSupportCount(ticketId: String): FeedbackTicket? = template.findAndModify(
        Query(
            Criteria.where("_id").`is`(ticketId)
                .and("visibility").`is`(FeedbackVisibility.PUBLIC)
                .and("supportCount").gt(0),
        ),
        Update().inc("supportCount", -1),
        FindAndModifyOptions.options().returnNew(true),
        FeedbackTicket::class.java,
    )

    /**
     * 定向更新公开字段,避免覆盖并发的消息/状态写入。
     */
    fun setPublicInfo(
        ticketId: String,
        visibility: String,
        publicTitle: String?,
        publicSummary: String?,
        publicStatus: String?,
        publishedAt: Instant?,
        publicUpdatedAt: Instant,
    ): FeedbackTicket? = template.findAndModify(
        Query(Criteria.where("_id").`is`(ticketId)),
        Update()
            .set("visibility", visibility)
            .set("publicTitle", publicTitle)
            .set("publicSummary", publicSummary)
            .set("publicStatus", publicStatus)
            .set("publishedAt", publishedAt)
            .set("publicUpdatedAt", publicUpdatedAt),
        FindAndModifyOptions.options().returnNew(true),
        FeedbackTicket::class.java,
    )

    /** 定向更新公开状态与完成时间。 */
    fun setPublicStatus(ticketId: String, publicStatus: String, publicUpdatedAt: Instant, completedAt: Instant?): FeedbackTicket? =
        template.findAndModify(
            Query(Criteria.where("_id").`is`(ticketId)),
            Update()
                .set("publicStatus", publicStatus)
                .set("publicUpdatedAt", publicUpdatedAt)
                .set("completedAt", completedAt),
            FindAndModifyOptions.options().returnNew(true),
            FeedbackTicket::class.java,
        )

    /** 定向写入合并指向;源记录保留。 */
    fun setMergedInto(ticketId: String, targetTicketId: String): FeedbackTicket? = template.findAndModify(
        Query(Criteria.where("_id").`is`(ticketId)),
        Update().set("mergedIntoId", targetTicketId),
        FindAndModifyOptions.options().returnNew(true),
        FeedbackTicket::class.java,
    )

    /** 定向修改反馈类型。 */
    fun setType(ticketId: String, type: String): FeedbackTicket? = template.findAndModify(
        Query(Criteria.where("_id").`is`(ticketId)),
        Update().set("type", type),
        FindAndModifyOptions.options().returnNew(true),
        FeedbackTicket::class.java,
    )

    /** 定向写入目标/完成版本与展示名快照。 */
    fun setVersions(
        ticketId: String,
        targetVersionId: String?,
        targetVersionLabel: String?,
        completedVersionId: String?,
        completedVersionLabel: String?,
    ): FeedbackTicket? = template.findAndModify(
        Query(Criteria.where("_id").`is`(ticketId)),
        Update()
            .set("targetVersionId", targetVersionId)
            .set("targetVersionLabel", targetVersionLabel)
            .set("completedVersionId", completedVersionId)
            .set("completedVersionLabel", completedVersionLabel),
        FindAndModifyOptions.options().returnNew(true),
        FeedbackTicket::class.java,
    )

    /** 原子累加主反馈的合并数量。 */
    fun incrementMergedCount(ticketId: String): FeedbackTicket? = template.findAndModify(
        Query(Criteria.where("_id").`is`(ticketId)),
        Update().inc("mergedCount", 1),
        FindAndModifyOptions.options().returnNew(true),
        FeedbackTicket::class.java,
    )

    /** Match the same valid-area, valid-category, OTHER precedence as detail authorization. */
    private fun categorySetCriteria(categories: Set<String>): Criteria {
        if (categories.isEmpty()) return Criteria.where("_id").`in`(emptyList<String>())
        val knownAreas = normalizedValues(FeedbackArea.all)
        val requested = normalizedValues(categories)
        val branches = mutableListOf(
            Criteria.where("area").regex(requested),
            Criteria().andOperator(
                Criteria.where("area").not().regex(knownAreas),
                Criteria.where("category").regex(requested),
            ),
        )
        if (FeedbackArea.OTHER in categories) {
            branches += Criteria().andOperator(
                Criteria.where("area").not().regex(knownAreas),
                Criteria.where("category").not().regex(knownAreas),
            )
        }
        return Criteria().orOperator(*branches.toTypedArray())
    }

    private fun typeCriteria(type: String): Criteria {
        if (type == FeedbackType.LEGACY_FEEDBACK) {
            return Criteria().andOperator(
                Criteria.where("type").regex(normalizedValues(setOf(type))),
                Criteria.where("category").not().regex(normalizedValues(FeedbackType.all)),
            )
        }
        return Criteria().orOperator(
            Criteria.where("type").regex(normalizedValues(setOf(type))),
            Criteria().andOperator(
                Criteria.where("type").regex(normalizedValues(setOf(FeedbackType.LEGACY_FEEDBACK))),
                Criteria.where("category").regex(normalizedValues(setOf(type))),
            ),
        )
    }

    private fun normalizedValues(values: Set<String>): Pattern = Pattern.compile(
        "^\\s*(?:" + values.joinToString("|") { Pattern.quote(it) } + ")\\s*$",
        Pattern.CASE_INSENSITIVE,
    )

    /**
     * Only save mutations based on the still-current snapshot. Message count and
     * status also guard writes within the same Mongo millisecond. Missing legacy
     * timestamps/messages remain writable; no document migration is required.
     */
    fun saveIfUnchanged(previous: FeedbackTicket, updated: FeedbackTicket): FeedbackTicket? {
        val messageBoundary = if (previous.messages.isEmpty()) {
            Criteria().orOperator(Criteria.where("messages").size(0), Criteria.where("messages").`is`(null))
        } else {
            Criteria.where("messages").size(previous.messages.size)
        }
        val query = Query(
            Criteria().andOperator(
                Criteria.where("_id").`is`(checkNotNull(previous.id)),
                Criteria.where("status").`is`(previous.status),
                messageBoundary,
                Criteria().orOperator(
                    Criteria.where("updatedAt").`is`(previous.updatedAt),
                    Criteria.where("updatedAt").exists(false),
                ),
            ),
        )
        val update = Update()
            .set("messages", updated.messages)
            .set("lastMessageSender", updated.lastMessageSender)
            .set("hasAdminReply", updated.hasAdminReply)
            .set("adminReply", updated.adminReply)
            .set("status", updated.status)
            .set("handlerUserId", updated.handlerUserId)
            .set("handledAt", updated.handledAt)
            .set("updatedAt", updated.updatedAt)
        return template.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), FeedbackTicket::class.java)
    }
}
