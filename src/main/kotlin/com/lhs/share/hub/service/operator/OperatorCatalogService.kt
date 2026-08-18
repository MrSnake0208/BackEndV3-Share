package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.JsonNode
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

    // SP 形态反向索引：本体 id -> 其 SP 形态 id 列表。惰性构建，目录写入时失效重建。
    @Volatile private var spIndexCache: Map<String, List<String>>? = null

    fun getOperator(id: String): OperatorCatalogEntity? {
        ensureSeeded()
        return repository.findByOperatorId(id)
    }
    fun exists(id: String): Boolean = getOperator(id) != null
    fun currentCatalogVersion(): String {
        ensureSeeded()
        return catalogVersion.ifEmpty { LocalDate.now().toString() }
    }
    fun catalog(): OperatorCatalogResponse {
        ensureSeeded()
        return OperatorCatalogResponse(
            catalogVersion = currentCatalogVersion(),
            operators = repository.findAllByOrderByOperatorIdAsc().map { OperatorCatalogEntryResponse.of(it) },
        )
    }

    /**
     * 管理端全量列表：返回原始实体（含 starStones / catalogVersion / createdAt 等内部字段），
     * 供管理员编辑公共图鉴使用。与只读公共图鉴 [catalog] 不同，不做任何裁剪。
     */
    fun listForAdmin(): List<OperatorCatalogEntity> {
        ensureSeeded()
        return repository.findAllByOrderByOperatorIdAsc()
    }

    /**
     * 返回指定本体密探的所有 SP 形态 id（如 char_023_shizimiao -> [char_085_shizimiaosp]）。
     * 反向索引惰性构建，目录写入时失效重建。普通密探返回空列表。
     */
    fun spFormsOf(baseId: String): List<String> {
        ensureSeeded()
        return spIndex()[baseId].orEmpty()
    }

    /** 本体 id -> SP 形态 id 列表；惰性构建，目录写入（create/update/delete）后失效。 */
    private fun spIndex(): Map<String, List<String>> {
        spIndexCache?.let { return it }
        synchronized(this) {
            spIndexCache?.let { return it }
            val m = mutableMapOf<String, MutableList<String>>()
            repository.findAllByOrderByOperatorIdAsc().forEach { e ->
                e.spOf?.let { base -> m.getOrPut(base) { mutableListOf() }.add(e.operatorId) }
            }
            val idx = m.mapValues { it.value.toList() }
            spIndexCache = idx
            return idx
        }
    }

    fun create(request: OperatorCatalogWriteRequest): OperatorCatalogEntity {
        ensureSeeded()
        if (repository.findByOperatorId(request.id) != null) {
            throw OperatorApiException(HttpStatus.CONFLICT, "operator_conflict", "Operator already exists")
        }
        validate(request)
        val version = nextCatalogVersion()
        return repository.save(request.toEntity(catalogVersion = version)).also { spIndexCache = null }
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
        return repository.save(request.toEntity(id = existing.id, createdAt = existing.createdAt, catalogVersion = version)).also { spIndexCache = null }
    }

    fun delete(operatorId: String) {
        ensureSeeded()
        val existing = repository.findByOperatorId(operatorId)
            ?: throw OperatorApiException(HttpStatus.NOT_FOUND, "operator_not_found", "Operator not found")
        repository.delete(existing)
        spIndexCache = null
        bumpCatalogVersion()
    }

    /**
     * 目录内容变化后生成新版本号并持久化。
     *
     * create/update 会随新写入行自然带上新版本号；delete 不产生新行，因此挑一条剩余行
     * 回写版本号（无剩余行时版本号只在内存更新，下次重新播种时自然刷新）。
     */
    private fun bumpCatalogVersion() {
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

    private fun OperatorCatalogWriteRequest.toEntity(id: String? = null, createdAt: Instant = Instant.now(), catalogVersion: String) =
        OperatorCatalogEntity(
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
            if (resource.exists()) {
                if (repository.count() == 0L) {
                    // 全新库：整体播种。
                    objectMapper.readTree(resource.inputStream).forEach { node ->
                        fromResource(node, version)?.let { repository.save(it) }
                    }
                } else {
                    // 已播种过的老库：只回填后来新增的字段（spOf），
                    // 不覆盖管理员改动、不重插被删除的行。约 121 行，仅首次访问执行一次。
                    objectMapper.readTree(resource.inputStream).forEach { node ->
                        val id = node.path("id").asText()
                        val spOf = node.get("spOf")?.asText()
                        if (id.isNotBlank() && spOf != null) {
                            val existing = repository.findByOperatorId(id) ?: return@forEach
                            if (existing.spOf == null) {
                                repository.save(
                                    existing.copy(
                                        spOf = spOf,
                                        createdAt = existing.createdAt,
                                        catalogVersion = existing.catalogVersion,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            catalogVersion = repository.findAllByOrderByOperatorIdAsc()
                .maxOfOrNull { it.catalogVersion }
                ?: version
            seeded = true
        }
    }

    private fun fromResource(node: JsonNode, version: String): OperatorCatalogEntity? {
        val id = node.path("id").asText()
        if (id.isBlank()) return null
        return OperatorCatalogEntity(
            operatorId = id,
            name = node.path("name").asText(id),
            alias = node.get("alias")?.asText(),
            rarity = node.path("rarity").asInt(5),
            prof = node.path("prof").map { it.asText() },
            subProf = node.path("subProf").map { it.asText() },
            games = node.path("games").map { it.asText() }.ifEmpty { SUPPORTED_GAMES },
            discs = node.path("discs").map {
                OperatorDiscCatalog(it.path("ot_name").asText(), it.get("abbreviation")?.asText(), it.get("color")?.asText(), it.get("desp")?.asText())
            },
            starStones = node.path("starStones").map {
                OperatorStarStoneCatalog(it.path("name").asText(), it.path("type").asText())
            }.ifEmpty { DEFAULT_STONES },
            spOf = node.get("spOf")?.asText(),
            catalogVersion = version,
        )
    }

    companion object {
        val SUPPORTED_GAMES = listOf("如鸢", "代号鸢")
        private val DEFAULT_STONES = listOf(OperatorStarStoneCatalog("主星石", "main"), OperatorStarStoneCatalog("辅星石", "assist"))
    }
}
