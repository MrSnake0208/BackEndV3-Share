package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.FeedbackTicket
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
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

    private fun categorySetCriteria(categories: Set<String>): Criteria {
        val criteria = mutableListOf(
            Criteria.where("category").`in`(categories),
            Criteria.where("area").`in`(categories),
        )
        if ("OTHER" in categories) {
            criteria += Criteria.where("area").exists(false)
        }
        return Criteria().orOperator(*criteria.toTypedArray())
    }

    private fun typeCriteria(type: String): Criteria {
        if (type == "FEEDBACK") return Criteria.where("type").`is`(type)
        return Criteria().orOperator(
            Criteria.where("type").`is`(type),
            Criteria().andOperator(
                Criteria.where("type").`is`("FEEDBACK"),
                Criteria.where("category").`is`(type),
            ),
        )
    }
}
