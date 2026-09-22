package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.FeedbackTicket
import com.lhs.share.hub.service.report.FeedbackArea
import com.lhs.share.hub.service.report.FeedbackType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
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
        manageableCategories?.let { filters += categorySetCriteria(it) }
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
