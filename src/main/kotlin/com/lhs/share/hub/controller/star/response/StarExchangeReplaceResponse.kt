package com.lhs.share.hub.controller.star.response

/** Result of one atomic replacement import. The returned loadout is always empty. */
data class StarExchangeReplaceResponse(
    val accountId: String,
    val inventory: StarInventorySnapshotResponse,
    val workspace: StarWorkspaceCurrentResponse,
    val loadout: StarLoadoutCurrentResponse,
)
