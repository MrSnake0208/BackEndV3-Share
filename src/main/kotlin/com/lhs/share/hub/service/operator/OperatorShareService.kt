package com.lhs.share.hub.service.operator

import com.lhs.share.hub.controller.account.response.AccountShareResponse
import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorShareCombatStats
import com.lhs.share.hub.controller.operator.response.OperatorShareDisc
import com.lhs.share.hub.controller.operator.response.OperatorShareDiscLoadout
import com.lhs.share.hub.controller.operator.response.OperatorShareEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorShareOddityValue
import com.lhs.share.hub.controller.operator.response.OperatorShareResponse
import com.lhs.share.hub.controller.operator.response.OperatorShareStarStone
import com.lhs.share.hub.controller.operator.response.OperatorShareTag
import com.lhs.share.hub.controller.operator.response.OperatorShareViewResponse
import com.lhs.share.hub.repository.OperatorAnnotationRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.repository.entity.effectiveShareScopes
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class OperatorShareService(
    private val accountRepository: SubAccountRepository,
    private val operatorService: OperatorService,
    private val catalogService: OperatorCatalogService,
    private val annotationRepository: OperatorAnnotationRepository,
    private val organizationService: OperatorOrganizationService,
) {
    fun get(userId: String, accountId: String): OperatorShareResponse = response(requireAccount(userId, accountId))

    fun create(userId: String, accountId: String): OperatorShareResponse =
        response(change(userId, accountId) { it.effectiveShareScopes() + "operator" })

    fun regenerate(userId: String, accountId: String): OperatorShareResponse =
        response(change(userId, accountId, rotate = true) { it.effectiveShareScopes() + "operator" })

    fun revoke(userId: String, accountId: String): OperatorShareResponse {
        return response(change(userId, accountId, disabling = true) { it.effectiveShareScopes() - "operator" })
    }

    fun view(shareCode: String): OperatorShareViewResponse {
        val account = sharedAccount(shareCode, "operator")
        val current = operatorService.current(account.userId, account.accountId, account.game).firstOrNull()
        val growthStates = annotationRepository.findAllByUserIdAndAccountIdOrderByOperatorIdAsc(account.userId, account.accountId)
            .associate { it.operatorId to it.growthState }
        val entries = current?.entries.orEmpty()
            .filterValues { it.huaji?.owned ?: (it.starLevel > 0) }
            .mapValues { (operatorId, entry) -> entry.toShareEntry(growthStates[operatorId] ?: OperatorSubjectiveService.ACTIVE) }
        return OperatorShareViewResponse(
            game = account.game,
            catalogVersion = catalogService.currentCatalogVersion(),
            updatedAt = current?.updatedAt,
            entries = entries,
            tags = if ("operator_tags" in account.effectiveShareScopes()) {
                organizationService.get(account.userId, account.accountId).path("tags").mapNotNull { tag ->
                    val members = tag.path("operator_ids").map { it.asText() }.filter { it in entries }
                    if (members.isEmpty()) null else OperatorShareTag(tag.path("id").asText(), tag.path("name").asText(), members)
                }
            } else {
                emptyList()
            },
        )
    }

    fun getAccountShare(userId: String, accountId: String): AccountShareResponse = accountResponse(requireAccount(userId, accountId))

    fun setScope(userId: String, accountId: String, scope: String, enabled: Boolean): AccountShareResponse {
        if (scope !in
            setOf("operator", "star", "operator_tags")
        ) {
            throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "share_scope_invalid", "Unsupported share scope")
        }
        return accountResponse(
            change(userId, accountId, disabling = !enabled) {
                if (scope == "operator_tags" && enabled && "operator" !in it.effectiveShareScopes()) {
                    throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "share_scope_requires_operator", "请先开启密探 BOX 分享")
                }
                if (enabled) it.effectiveShareScopes() + scope else it.effectiveShareScopes() - scope
            },
        )
    }

    fun regenerateAccountShare(userId: String, accountId: String): AccountShareResponse =
        accountResponse(change(userId, accountId, rotate = true) { it.effectiveShareScopes() })

    fun revokeAccountShare(userId: String, accountId: String): AccountShareResponse =
        accountResponse(change(userId, accountId, disabling = true) { emptyList() })

    fun sharedAccount(shareCode: String, scope: String): SubAccount {
        val account = accountRepository.findByShareToken(shareCode)
            ?.takeIf { it.shareToken == shareCode && !it.shareToken.isNullOrBlank() }
            ?: throw shareNotFound()
        if (scope !in
            account.effectiveShareScopes()
        ) {
            throw OperatorApiException(HttpStatus.NOT_FOUND, "share_not_authorized", "This share view is not enabled")
        }
        return account
    }

    /** Retry only the requested scope against the latest scopes; never replay a stale whole list. */
    private fun change(
        userId: String,
        accountId: String,
        rotate: Boolean = false,
        disabling: Boolean = false,
        scopes: (SubAccount) -> List<String>,
    ): SubAccount {
        var latest = requireAccount(userId, accountId)
        val originalToken = latest.shareToken
        repeat(MAX_GENERATION_ATTEMPTS) {
            val requestedScopes = scopes(latest)
            val nextScopes = requestedScopes.filter { it != "operator_tags" || "operator" in requestedScopes }.distinct().sorted()
            if (nextScopes == latest.effectiveShareScopes() && (!rotate || nextScopes.isEmpty())) return latest
            if (disabling &&
                latest.shareToken != originalToken
            ) {
                throw OperatorApiException(HttpStatus.CONFLICT, "share_code_changed", "Share code changed; refresh before revoking it")
            }
            val token = if (nextScopes.isEmpty()) {
                null
            } else if (rotate) {
                UUID.randomUUID().toString()
            } else {
                latest.shareToken?.takeIf(String::isNotBlank)
                    ?: UUID.randomUUID().toString()
            }
            try {
                accountRepository.updateShareAuthorization(
                    latest.userId,
                    latest.accountId,
                    latest.shareToken,
                    latest.shareScopes,
                    token,
                    nextScopes,
                    Instant.now(),
                )?.let { return it }
            } catch (_: DuplicateKeyException) {
                // A unique-index collision retries with another token, without replacing the account document.
            }
            latest = requireAccount(userId, accountId)
        }
        throw OperatorApiException(
            HttpStatus.CONFLICT,
            "share_code_generation_failed",
            "Unable to generate a unique share code",
        )
    }

    private fun requireAccount(userId: String, accountId: String): SubAccount =
        accountRepository.findByUserIdAndAccountId(userId, accountId) ?: throw OperatorApiException(
            HttpStatus.NOT_FOUND,
            "account_not_found",
            "Account not found",
        )

    private fun response(account: SubAccount) = OperatorShareResponse(
        accountId = account.accountId,
        active = "operator" in account.effectiveShareScopes(),
        shareCode = account.shareToken?.takeIf { "operator" in account.effectiveShareScopes() },
    )

    private fun accountResponse(account: SubAccount) = AccountShareResponse(
        account.accountId,
        account.effectiveShareScopes().isNotEmpty(),
        account.shareToken?.takeIf { account.effectiveShareScopes().isNotEmpty() },
        account.effectiveShareScopes(),
    )

    private fun shareNotFound() = OperatorApiException(
        HttpStatus.NOT_FOUND,
        "share_not_found",
        "Share not found",
    )

    private fun OperatorCurrentEntryDto.toShareEntry(growthState: String) = OperatorShareEntryDto(
        level = level,
        elite = elite,
        starLevel = starLevel,
        huaji = huaji,
        growthState = growthState,
        discLoadouts = discLoadouts.map { loadout ->
            OperatorShareDiscLoadout(
                id = loadout.id,
                name = loadout.name,
                discs = loadout.discs.map { disc ->
                    OperatorShareDisc(disc.otName, disc.abbreviation, disc.color, disc.desp)
                },
            )
        },
        starStones = starStones.map { stone -> OperatorShareStarStone(stone.name, stone.type, stone.level) },
        combatStats = combatStats?.let {
            OperatorShareCombatStats(
                observedAttack = it.observedAttack,
                observedHp = it.observedHp,
                manualAttack = it.manualAttack,
                manualHp = it.manualHp,
                oddities = it.oddities.mapValues { (_, oddity) -> OperatorShareOddityValue(oddity.current) },
            )
        },
    )

    companion object {
        private const val MAX_GENERATION_ATTEMPTS = 3
    }
}
