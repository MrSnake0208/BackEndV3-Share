package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class OperatorRelationshipCatalog(mapper: ObjectMapper) {
    private val document = ClassPathResource("operator/relationships.json").inputStream.use { mapper.readTree(it) }
        .also(::validate)

    fun get(game: String): JsonNode {
        require(game in setOf("如鸢", "代号鸢"))
        return document.path("games").path(game).deepCopy<JsonNode>()
    }
    fun version(): String = document.path("version").asText()

    companion object {
        const val MAX_VALUE = 9007199254740991L
        fun validate(document: JsonNode) {
            fields(document, setOf("version", "games"))
            require(document.path("version").isTextual && document.path("version").asText().isNotBlank())
            fields(document.path("games"), setOf("如鸢", "代号鸢"))
            for (game in document.path("games")) {
                require(game.isObject && game.size() <= 1000)
                game.fields().forEach { (id, rule) ->
                    require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                    fields(rule, setOf("trust_owner_id", "trust", "rumors"))
                    require(rule.path("trust_owner_id").isTextual)
                    val owner = rule.path("trust_owner_id").asText()
                    require(game.has(owner) && game.path(owner).path("trust_owner_id").asText() == owner)
                    for (kind in listOf("trust", "rumors")) {
                        val domain = rule.path(kind)
                        if (domain.isNull) continue
                        fields(domain, setOf("max", "complete", "source", "verified_on", "milestones"))
                        require(domain.path("max").isIntegralNumber && domain.path("max").canConvertToLong())
                        require(domain.path("max").asLong() in 0..MAX_VALUE)
                        require(domain.path("complete").isBoolean)
                        require(domain.path("source").isTextual && domain.path("source").asText().isNotBlank())
                        require(domain.path("verified_on").isTextual)
                        LocalDate.parse(domain.path("verified_on").asText())
                        val milestones = domain.path("milestones")
                        require(milestones.isArray && milestones.size() <= 1000)
                        var previous = -1L
                        for (milestone in milestones) {
                            fields(milestone, setOf("at", "rewards"))
                            val at = milestone.path("at")
                            require(at.isIntegralNumber && at.canConvertToLong() && at.asLong() in 0..domain.path("max").asLong())
                            require(at.asLong() > previous)
                            previous = at.asLong()
                            val rewards = milestone.path("rewards")
                            require(rewards.isArray && rewards.size() <= 100)
                            val keys = mutableSetOf<String>()
                            for (reward in rewards) {
                                fields(reward, setOf("item_key", "name", "quantity"))
                                require(reward.path("item_key").isTextual && reward.path("item_key").asText().isNotBlank())
                                require(keys.add(reward.path("item_key").asText()))
                                require(reward.path("name").isTextual && reward.path("name").asText().isNotBlank())
                                val quantity = reward.path("quantity")
                                require(quantity.isIntegralNumber && quantity.canConvertToLong() && quantity.asLong() in 1..MAX_VALUE)
                            }
                        }
                    }
                    require(owner == id || rule.path("trust") == game.path(owner).path("trust"))
                }
            }
        }
        private fun fields(node: JsonNode, keys: Set<String>) {
            require(node.isObject && node.fieldNames().asSequence().toSet() == keys)
        }
    }
}
