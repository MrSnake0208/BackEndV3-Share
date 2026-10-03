package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarEvent
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class ActivityCalendarRepository(@param:Qualifier("hubMongoTemplate") private val template: MongoTemplate) {
    fun list(
        game: String?,
        from: LocalDate?,
        to: LocalDate?,
        categories: Set<ActivityCalendarCategory>,
        enabled: Boolean?,
    ): List<ActivityCalendarEvent> {
        val query = Query()
        game?.let { query.addCriteria(Criteria.where("game").`is`(it)) }
        from?.let { query.addCriteria(Criteria.where("endDate").gte(it)) }
        to?.let { query.addCriteria(Criteria.where("startDate").lte(it)) }
        if (categories.isNotEmpty()) query.addCriteria(Criteria.where("category").`in`(categories))
        enabled?.let { query.addCriteria(Criteria.where("enabled").`is`(it)) }
        return template.find(query, ActivityCalendarEvent::class.java)
    }

    fun find(id: String): ActivityCalendarEvent? = template.findById(id, ActivityCalendarEvent::class.java)

    fun save(event: ActivityCalendarEvent): ActivityCalendarEvent = template.save(event)
}
