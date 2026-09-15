package com.lhs.share.hub.controller.star.response

import com.lhs.share.hub.repository.entity.StarLoadoutCurrent
import java.time.Instant

data class StarLoadoutCurrentResponse(
    val accountId: String,
    val revision: Long,
    val loadouts: Map<String, Map<String, String?>>,
    val updatedAt: Instant?,
) {
    companion object {
        fun empty(accountId: String) = StarLoadoutCurrentResponse(accountId, 0, emptyMap(), null)

        fun of(current: StarLoadoutCurrent) = StarLoadoutCurrentResponse(
            current.accountId,
            current.revision,
            current.loadouts.associate { it.operatorId to it.slots.asMap() },
            current.updatedAt,
        )
    }
}
