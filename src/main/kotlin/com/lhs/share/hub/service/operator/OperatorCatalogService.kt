package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.operator.request.OperatorCatalogWriteRequest
import com.lhs.share.hub.controller.operator.response.OperatorCatalogEntryResponse
import com.lhs.share.hub.controller.operator.response.OperatorCatalogResponse
import com.lhs.share.hub.repository.OperatorCatalogRepository
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.repository.entity.OperatorDiscCatalog
import com.lhs.share.hub.repository.entity.OperatorStarStoneCatalog
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

@Service
class OperatorCatalogService(
    private val repository: OperatorCatalogRepository,
    private val objectMapper: ObjectMapper,
) {
    @Volatile private var seeded = false
    @Volatile private var catalogVersion = ""

    fun getOperator(id: String): OperatorCatalogEntity? { ensureSeeded(); return repository.findByOperatorId(id) }
    fun exists(id: String): Boolean = getOperator(id) != null
    fun currentCatalogVersion(): String { ensureSeeded(); return catalogVersion.ifEmpty { LocalDate.now().toString() } }
    fun catalog(): OperatorCatalogResponse {
        ensureSeeded()
        return OperatorCatalogResponse(
            catalogVersion = currentCatalogVersion(),
            operators = repository.findAllByOrderByOperatorIdAsc().map { OperatorCatalogEntryResponse.of(it) },
        )
    }

    fun create(request: OperatorCatalogWriteRequest): OperatorCatalogEntity {
        ensureSeeded()
        if (repository.findByOperatorId(request.id) != null) {
            throw OperatorApiException(HttpStatus.CONFLICT, "operator_conflict", "Operator already exists")
        }
        validate(request)
        val version = nextCatalogVersion()
        return repository.save(request.toEntity(catalogVersion = version))
    }

    fun update(operatorId: String, request: OperatorCatalogWriteRequest): OperatorCatalogEntity {
        ensureSeeded()
        if (operatorId != request.id) {
            throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "Path id and body id must match")
        }
        val existing = repository.findByOperatorId(operatorId)
            ?: throw OperatorApiException(HttpStatus.NOT_FOUND, "operator_not_found", "Operator not found")
        validate(request)
        val version = nextCatalogVersion()
        return repository.save(request.toEntity(id = existing.id, createdAt = existing.createdAt, catalogVersion = version))
    }

    fun delete(operatorId: String) {
        ensureSeeded()
        val existing = repository.findByOperatorId(operatorId)
            ?: throw OperatorApiException(HttpStatus.NOT_FOUND, "operator_not_found", "Operator not found")
        repository.delete(existing)
        val version = nextCatalogVersion()
        repository.findAllByOrderByOperatorIdAsc().firstOrNull()?.let {
            repository.save(it.copy(catalogVersion = version))
        }
    }

    private fun validate(request: OperatorCatalogWriteRequest) {
        if (request.games.any { it !in SUPPORTED_GAMES }) {
            throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_game", "Unsupported game")
        }
        val discNames = request.discs.map { it.otName }
        if (discNames.toSet().size != discNames.size) {
            throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_disc", "disc ot_name must be unique")
        }
        val stoneTypes = request.starStones.map { it.type }
        if (stoneTypes.toSet().size != stoneTypes.size) {
            throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_star_stone", "star stone type must be unique")
        }
        request.spOf?.let { base ->
            if (base == request.id) {
                throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "spOf cannot reference itself")
            }
            if (getOperator(base) == null) {
                throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_operator_id", "spOf base operator not found: " + base)
            }
        }
    }

    private fun OperatorCatalogWriteRequest.toEntity(
        id: String? = null,
        createdAt: Instant = Instant.now(),
        catalogVersion: String,
    ) = OperatorCatalogEntity(
        id = id,
        operatorId = this.id,
        name = name,
        alias = alias,
        rarity = rarity,
        prof = prof,
        subProf = subProf,
        games = games,
        discs = discs.map { OperatorDiscCatalog(it.otName, it.abbreviation, it.color, it.desp) },
        starStones = starStones.map { OperatorStarStoneCatalog(it.name, it.type) },
        spOf = spOf,
        catalogVersion = catalogVersion,
        createdAt = createdAt,
    )

    private fun nextCatalogVersion(): String {
        catalogVersion = Instant.now().toString()
        return catalogVersion
    }

    private fun ensureSeeded() {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            val version = LocalDate.now().toString()
            val resource = ClassPathResource("operator/operators.json")
            if (repository.count() == 0L && resource.exists()) {
                objectMapper.readTree(resource.inputStream).forEach { node ->
                    val id = node.path("id").asText()
                    if (id.isNotBlank()) {
                        repository.save(OperatorCatalogEntity(
                            operatorId = id,
                            name = node.path("name").asText(id),
                            alias = node.get("alias")?.asText(),
                            rarity = node.path("rarity").asInt(5),
                            prof = node.path("prof").map { it.asText() },
                            subProf = node.path("subProf").map { it.asText() },
                            games = node.path("games").map { it.asText() }.ifEmpty { SUPPORTED_GAMES },
                            discs = node.path("discs").map { OperatorDiscCatalog(it.path("ot_name").asText(), it.get("abbreviation")?.asText(), it.get("color")?.asText(), it.get("desp")?.asText()) },
                            starStones = node.path("starStones").map { OperatorStarStoneCatalog(it.path("name").asText(), it.path("type").asText()) }.ifEmpty { DEFAULT_STONES },
                            spOf = node.get("spOf")?.asText(),
                            catalogVersion = version,
                        ))
                    }
                }
            }
            catalogVersion = repository.findAllByOrderByOperatorIdAsc()
                .maxOfOrNull { it.catalogVersion }
                ?: version
            seeded = true
        }
    }

    companion object {
        val SUPPORTED_GAMES = listOf("如鸢", "代号鸢")
        private val DEFAULT_STONES = listOf(OperatorStarStoneCatalog("主星石", "main"), OperatorStarStoneCatalog("辅星石", "assist"))
    }
}
