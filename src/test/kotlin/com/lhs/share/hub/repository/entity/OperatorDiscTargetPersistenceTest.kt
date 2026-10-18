package com.lhs.share.hub.repository.entity

import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import java.time.Instant

class OperatorDiscTargetPersistenceTest {
    private val conversions = MongoCustomConversions(emptyList<Any>())
    private val context = MongoMappingContext().apply {
        setSimpleTypeHolder(conversions.simpleTypeHolder)
        afterPropertiesSet()
    }
    private val converter = MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context).apply {
        setCustomConversions(conversions)
        afterPropertiesSet()
    }

    @Test
    fun `old BSON loadouts use defaults and new account documents round trip both independent targets`() {
        val legacy = Document("_id", "one").append("name", "旧命盘").append("discs", listOf(Document("otName", "A")))
        val old = converter.read(OperatorDiscLoadout::class.java, legacy)
        assertEquals("unmarked", old.status)
        assertEquals(emptyList<OperatorDisc>(), old.targetDiscs)
        assertEquals(listOf(OperatorDisc("A")), old.discs)
        for ((owner, account) in listOf("ownerA" to "accountA", "ownerA" to "accountB", "ownerB" to "accountA")) {
            val current = OperatorCurrent(
                userId = owner,
                accountId = account,
                game = "如鸢",
                updatedAt = Instant.EPOCH,
                entries = mapOf(
                    "op" to OperatorEntry(
                        elite = 1,
                        starLevel = 1,
                        level = 1,
                        discLoadouts = listOf(
                            old.copy(status = "reroll", targetDiscs = listOf(OperatorDisc(account))),
                            OperatorDiscLoadout("two", "命盘二", status = "perfect", targetDiscs = listOf(OperatorDisc(owner))),
                        ),
                    ),
                ),
            )
            val document = Document()
            converter.write(current, document)
            assertEquals(current, converter.read(OperatorCurrent::class.java, document))
        }
    }
}
