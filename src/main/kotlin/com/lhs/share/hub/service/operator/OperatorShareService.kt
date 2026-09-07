package com.lhs.share.hub.service.operator

import com.lhs.share.hub.controller.operator.response.OperatorCurrentEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorShareCombatStats
import com.lhs.share.hub.controller.operator.response.OperatorShareDisc
import com.lhs.share.hub.controller.operator.response.OperatorShareDiscLoadout
import com.lhs.share.hub.controller.operator.response.OperatorShareEntryDto
import com.lhs.share.hub.controller.operator.response.OperatorShareOddityValue
import com.lhs.share.hub.controller.operator.response.OperatorShareResponse
import com.lhs.share.hub.controller.operator.response.OperatorShareStarStone
import com.lhs.share.hub.controller.operator.response.OperatorShareViewResponse
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.OperatorAnnotationRepository
import com.lhs.share.hub.repository.entity.SubAccount
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OperatorShareService(
    private val accountRepository: SubAccountRepository,
    private val operatorService: OperatorService,
    private val catalogService: OperatorCatalogService,
    private val annotationRepository: OperatorAnnotationRepository,
) {
    fun get(userId: String, accountId: String): OperatorShareResponse = response(requireAccount(userId, accountId))

    fun create(userId: String, accountId: String): OperatorShareResponse {
        val account = requireAccount(userId, accountId)
        if (account.activeShareToken() != null) return response(account)
        return generate(account, reuseWinner = true)
    }

    fun regenerate(userId: String, accountId: String): OperatorShareResponse =
        generate(requireAccount(userId, accountId), reuseWinner = false)

    fun revoke(userId: String, accountId: String): OperatorShareResponse {
        val account = requireAccount(userId, accountId)
        if (account.activeShareToken() == null) return response(account)
        return response(accountRepository.save(account.copy(shareToken = null)))
    }

    fun view(shareCode: String): OperatorShareViewResponse {
        val account = accountRepository.findByShareToken(shareCode)
            ?.takeIf { it.shareToken == shareCode && it.activeShareToken() != null }
            ?: throw shareNotFound()
        val current = operatorService.current(account.userId, account.accountId, account.game).firstOrNull()
        val growthStates = annotationRepository.findAllByUserIdAndAccountIdOrderByOperatorIdAsc(account.userId, account.accountId)
            .associate { it.operatorId to it.growthState }
        val entries = current?.entries.orEmpty()
            .filterValues { it.starLevel > 0 }
            .mapValues { (operatorId, entry) -> entry.toShareEntry(growthStates[operatorId] ?: OperatorSubjectiveService.ACTIVE) }
        return OperatorShareViewResponse(
            game = account.game,
            catalogVersion = catalogService.currentCatalogVersion(),
            updatedAt = current?.updatedAt,
            entries = entries,
        )
    }

    private fun generate(account: SubAccount, reuseWinner: Boolean): OperatorShareResponse {
        var latest = account
        repeat(MAX_GENERATION_ATTEMPTS) {
            try {
                return response(accountRepository.save(latest.copy(shareToken = UUID.randomUUID().toString())))
            } catch (_: DuplicateKeyException) {
                latest = requireAccount(account.userId, account.accountId)
                if (reuseWinner) latest.activeShareToken()?.let { return response(latest) }
            }
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
        active = account.activeShareToken() != null,
        shareCode = account.activeShareToken(),
    )

    private fun shareNotFound() = OperatorApiException(
        HttpStatus.NOT_FOUND,
        "share_not_found",
        "Share not found",
    )

    private fun SubAccount.activeShareToken(): String? = shareToken?.takeIf(String::isNotBlank)

    private fun OperatorCurrentEntryDto.toShareEntry(growthState: String) = OperatorShareEntryDto(
        level = level,
        elite = elite,
        starLevel = starLevel,
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
