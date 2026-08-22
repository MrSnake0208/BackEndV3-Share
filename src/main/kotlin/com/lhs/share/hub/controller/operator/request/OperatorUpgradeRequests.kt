package com.lhs.share.hub.controller.operator.request

data class OperatorUpgradeRequest(
    val accountId: String,
    val game: String,
    val operatorId: String,
    val dimension: String,
    val target: Int,
    val expectedOperatorRevision: Long,
)

data class OperatorUpgradeExecuteRequest(
    val accountId: String,
    val game: String,
    val operatorId: String,
    val dimension: String,
    val target: Int,
    val expectedOperatorRevision: Long,
    val expectedInventoryRevision: Long,
    val previewToken: String,
) {
    fun previewRequest() = OperatorUpgradeRequest(accountId, game, operatorId, dimension, target, expectedOperatorRevision)
}
