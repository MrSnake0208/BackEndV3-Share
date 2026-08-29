package com.lhs.share.hub.controller.report.response

import java.time.Instant

data class FeedbackAreaOptionResponse(
    val key: String,
    val label: String,
)

data class CurrentFeedbackAccessResponse(
    val superAdmin: Boolean,
    val receiveAreas: Set<String>,
    val manageAreas: Set<String>,
    val availableAreas: List<FeedbackAreaOptionResponse>,
    val receiveCategories: Set<String> = receiveAreas,
    val manageCategories: Set<String> = manageAreas,
    val availableCategories: List<FeedbackAreaOptionResponse> = availableAreas,
)

data class FeedbackAccessGrantResponse(
    val userId: String,
    val userName: String,
    val receiveAreas: Set<String>,
    val manageAreas: Set<String>,
    val updatedBy: String,
    val updatedAt: Instant,
    val receiveCategories: Set<String> = receiveAreas,
    val manageCategories: Set<String> = manageAreas,
)
