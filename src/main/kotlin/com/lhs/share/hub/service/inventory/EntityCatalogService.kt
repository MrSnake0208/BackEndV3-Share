package com.lhs.share.hub.service.inventory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.controller.inventory.response.EntityCatalogItemDto
import com.lhs.share.hub.controller.inventory.response.InventoryCatalogResponse
import com.lhs.share.hub.repository.EntityCatalogRepository
import com.lhs.share.hub.repository.entity.EntityCatalogEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger { }

/**
 * 对象目录服务(HubBackend.entity_catalog)
 *
 * 目录为全局只读字典,向后端校验 (entity_type, id) 与向前端展示统一对象名称。
 * 加载策略:首次访问时从 classpath 资源 inventory/items.json、inventory/operators.json
 * 解析全部对象,只补齐 collection 中缺失的对象,之后走只读路径。
 *
 * 该 service 绝不覆盖或删除既有数据,避免影响运维手工维护的目录。
 */
@Service
class EntityCatalogService(
    private val repository: EntityCatalogRepository,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 目录版本(默认取当次播种日期;部署方可转储后覆盖)。
     */
    @Volatile
    private var catalogVersion: String = ""

    /**
     * 首次播种标记;应用生命周期内只执行一次,保证并发请求不会重复播种。
     */
    @Volatile
    private var seeded: Boolean = false

    /**
     * 返回完整对象目录(首次调用时惰性完成播种)
     */
    fun catalog(): InventoryCatalogResponse {
        ensureSeeded()
        val entities = mutableListOf<EntityCatalogItemDto>()
        ENTITY_TYPES.forEach { type ->
            repository.findByEntityTypeOrderByEntityIdAsc(type).forEach { e ->
                entities.add(
                    EntityCatalogItemDto(
                        entityType = e.entityType,
                        id = e.entityId,
                        name = e.name,
                    ),
                )
            }
        }
        return InventoryCatalogResponse(
            catalogVersion = currentCatalogVersion(),
            entities = entities,
        )
    }

    /**
     * 校验 (entity_type, entity_id) 是否存在于目录(导入校验用)
     */
    fun exists(entityType: String, entityId: String): Boolean {
        ensureSeeded()
        return repository.findByEntityTypeAndEntityId(entityType, entityId) != null
    }

    /**
     * 惰性播种:首次访问时补齐 classpath 中存在、collection 中缺失的对象。
     */
    private fun ensureSeeded() {
        if (seeded) return
        synchronized(this) {
            if (seeded) return
            seed()
            seeded = true
        }
    }

    private fun seed() {
        itemsResource()?.let { upsertAll(parseCatalog(it, "item")) }
        operatorsResource()?.let { upsertAll(parseCatalog(it, "agent")) }
        catalogVersion = resolveCatalogVersion()
        log.info { "对象目录播种完成,版本: $catalogVersion" }
    }

    private fun currentCatalogVersion(): String = catalogVersion.ifEmpty { resolveCatalogVersion() }

    /**
     * 目录版本:默认用当次播种日期;部署方可替换资源文件中的 catalog_version 元数据。
     */
    private fun resolveCatalogVersion(): String = java.time.LocalDate.now().toString()

    /**
     * 解析单个 classpath 资源为目录实体列表。
     * 资源格式为 JSON 数组:[{ "id": "...", "name": "..." }, ...]。
     */
    private fun parseCatalog(resource: Resource, entityType: String): List<EntityCatalogEntity> {
        val root: JsonNode = objectMapper.readTree(resource.inputStream)
        if (!root.isArray) {
            log.warn { "目录资源不是 JSON 数组,跳过: ${resource.description}" }
            return emptyList()
        }
        val version = resolveCatalogVersion()
        return root.mapNotNull { node ->
            val id = node.get("id")?.asText()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = node.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: id
            EntityCatalogEntity(
                entityType = entityType,
                entityId = id,
                name = name,
                catalogVersion = version,
            )
        }
    }

    /**
     * 逐条 upsert:已存在(按 (entity_type, entity_id))则跳过,保留既有记录;
     * 不存在则插入,依靠唯一索引保证幂等。
     */
    private fun upsertAll(entities: List<EntityCatalogEntity>) {
        entities.forEach { entity ->
            if (repository.findByEntityTypeAndEntityId(entity.entityType, entity.entityId) == null) {
                repository.save(entity)
            }
        }
    }

    private fun itemsResource(): Resource? = loadResource("classpath:inventory/items.json")

    private fun operatorsResource(): Resource? = loadResource("classpath:inventory/operators.json")

    private fun loadResource(location: String): Resource? {
        return try {
            val resolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()
            resolver.getResource(location).takeIf { it.exists() }
        } catch (e: Exception) {
            log.warn(e) { "加载目录资源失败: $location" }
            null
        }
    }

    companion object {
        private val ENTITY_TYPES = listOf("item", "agent")
    }
}
