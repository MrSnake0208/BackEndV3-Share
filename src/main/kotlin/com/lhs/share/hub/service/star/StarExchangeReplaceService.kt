package com.lhs.share.hub.service.star

import com.lhs.share.hub.controller.star.request.StarExchangeReplaceRequest
import com.lhs.share.hub.controller.star.request.StarInventorySnapshotRequest
import com.lhs.share.hub.controller.star.response.StarExchangeReplaceResponse
import com.lhs.share.hub.controller.star.response.StarInventorySnapshotResponse
import com.lhs.share.hub.controller.star.response.StarWorkspaceCurrentResponse
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.StarWorkspaceCurrentRepository
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * The only write path for a YuanStar replacement import. It deliberately clears account-scoped
 * loadouts because incoming inventory instance IDs cannot be matched to the previous snapshot.
 */
@Service
class StarExchangeReplaceService(
    private val accountService: SubAccountService,
    private val inventoryService: StarInventoryService,
    private val workspaceService: StarWorkspaceService,
    private val loadoutService: StarLoadoutService,
    private val inventoryRepository: StarInventoryCurrentRepository,
    private val workspaceRepository: StarWorkspaceCurrentRepository,
    @param:Qualifier("hubTransactionTemplate") private val transactions: TransactionTemplate,
) {
    fun replace(userId: String, request: StarExchangeReplaceRequest): StarExchangeReplaceResponse =
        requireNotNull(transactions.execute {
            accountService.requireAccount(userId, request.accountId)

            val inventoryRequest = request.inventory ?: throw invalid("inventory 不能为空")
            val workspaceRequest = request.workspace ?: throw invalid("workspace 不能为空")
            val expectedInventoryRevision = inventoryRequest.expectedRevision ?: throw invalid("inventory.expected_revision 不能为空")
            val inventoryEntries = inventoryRequest.entries ?: throw invalid("inventory.entries 不能为空")
            if (expectedInventoryRevision < 0) throw invalid("inventory.expected_revision 不能小于 0")

            val inventory = inventoryService.prepareReplacement(
                StarInventorySnapshotRequest(inventoryRequest.effectiveAt, inventoryEntries),
            )
            val workspace = workspaceService.prepareReplacement(workspaceRequest)
            validatePlanTargetReferences(workspace, inventory)

            val now = Instant.now()
            val savedInventory = starCasConflictBoundary({ throw inventoryConflict() }) {
                inventoryRepository.replace(
                    userId = userId,
                    accountId = request.accountId,
                    effectiveAt = inventory.effectiveAt,
                    entries = inventory.entries,
                    contentHash = inventory.contentHash,
                    expectedRevision = expectedInventoryRevision,
                    updatedAt = now,
                    receivedAt = now,
                ) ?: throw inventoryConflict()
            }
            val savedWorkspace = starCasConflictBoundary({ throw workspaceConflict() }) {
                workspaceRepository.replace(
                    userId = userId,
                    accountId = request.accountId,
                    expectedRevision = workspace.expectedRevision,
                    planTargets = workspace.planTargets,
                    bag = workspace.bag,
                    experience = workspace.experience,
                    now = now,
                ) ?: throw workspaceConflict()
            }
            val clearedLoadout = loadoutService.clearForReplacement(userId, request.accountId, now)

            StarExchangeReplaceResponse(
                accountId = request.accountId,
                inventory = StarInventorySnapshotResponse.of(savedInventory),
                workspace = StarWorkspaceCurrentResponse.of(savedWorkspace),
                loadout = clearedLoadout,
            )
        })

    private fun validatePlanTargetReferences(
        workspace: StarWorkspaceReplacementSnapshot,
        inventory: StarInventoryReplacementSnapshot,
    ) {
        val instanceIds = inventory.entries.asSequence().map { it.instanceId }.toHashSet()
        if (workspace.planTargets.any { it.instanceId !in instanceIds }) {
            throw InventoryApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "star_exchange_invalid_workspace_reference",
                "plan_targets must reference an instance_id from the replacement inventory",
            )
        }
    }

    private fun invalid(message: String) = InventoryApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "star_exchange_invalid_snapshot",
        message,
    )

    private fun inventoryConflict() = InventoryApiException(
        HttpStatus.CONFLICT,
        "star_inventory_revision_conflict",
        "Star inventory changed; reload before replacement import",
    )

    private fun workspaceConflict() = InventoryApiException(
        HttpStatus.CONFLICT,
        "star_workspace_revision_conflict",
        "Star workspace changed; reload before replacement import",
    )
}
