package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document

data class FeedbackCategory(val key: String, val label: String)

@Document("feedback_category_catalog")
data class FeedbackCategoryCatalog(
    @Id val id: String = "feedback",
    val categories: List<FeedbackCategory>,
    @Version val version: Long? = null,
)
