package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.AchievementCatalog
import com.lhs.share.hub.repository.entity.CatalogPublication
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

@Repository
class AchievementCatalogRepository(@param:Qualifier("hubMongoTemplate") private val mongo: MongoTemplate) {
    data class PublishedOnly(val published: CatalogPublication)

    fun published(game: String): CatalogPublication? = mongo.findOne(
        Query(Criteria.where("_id").`is`(game)).also { it.fields().include("published") },
        PublishedOnly::class.java,
        mongo.getCollectionName(AchievementCatalog::class.java),
    )?.published

    fun find(game: String): AchievementCatalog? = mongo.findById(game, AchievementCatalog::class.java)
    fun insert(value: AchievementCatalog): AchievementCatalog = mongo.insert(value)
    fun replace(value: AchievementCatalog, expectedRevision: Long): Boolean = mongo.replace(
        Query(Criteria.where("_id").`is`(value.game).and("revision").`is`(expectedRevision)),
        value,
    ).modifiedCount == 1L
}
