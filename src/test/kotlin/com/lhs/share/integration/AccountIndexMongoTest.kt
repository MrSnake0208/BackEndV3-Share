package com.lhs.share.integration

import com.lhs.share.fixtures.TestFixtures
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.testinfra.TestMongo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

@Tag("integration")
class AccountIndexMongoTest {
    private val database = TestMongo.database("accounts")
    private val client = TestMongo.client()
    private val mongo = MongoTemplate(client, database)

    @BeforeEach
    fun indexesFromActualProductionEntity() {
        val resolver = MongoPersistentEntityIndexResolver(mongo.converter.mappingContext)
        resolver.resolveIndexFor(SubAccount::class.java).forEach { mongo.indexOps(SubAccount::class.java).ensureIndex(it) }
    }

    @AfterEach
    fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }

    @Test
    fun `same owner and name conflicts even across game versions under the current backend model`() {
        mongo.insert(TestFixtures.account())
        assertThrows(DuplicateKeyException::class.java) {
            mongo.insert(TestFixtures.account(accountId = "acc_test_b", game = "代号鸢"))
        }
        assertEquals(1L, mongo.count(Query(), SubAccount::class.java))
    }

    @Test
    fun `different owners may use the same name without leaking into an owner-scoped query`() {
        val a = TestFixtures.account()
        val b = TestFixtures.account(userId = TestFixtures.USER_B, accountId = "acc_test_b")
        mongo.insert(a)
        mongo.insert(b)
        assertEquals(
            listOf(a.accountId),
            mongo.find(Query(Criteria.where("userId").`is`(a.userId)), SubAccount::class.java).map {
                it.accountId
            },
        )
        assertEquals(
            listOf(b.accountId),
            mongo.find(Query(Criteria.where("userId").`is`(b.userId)), SubAccount::class.java).map {
                it.accountId
            },
        )
    }

    @Test
    fun `canonical account identity cannot be duplicated under another display name`() {
        val original = TestFixtures.account()
        mongo.insert(original)
        assertThrows(DuplicateKeyException::class.java) { mongo.insert(original.copy(id = "second-mongo-id", name = "不同名称")) }
        assertEquals(original, mongo.findById(original.id!!, SubAccount::class.java))
    }
}
