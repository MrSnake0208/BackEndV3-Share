package com.lhs.share.hub.service.operator

import com.lhs.share.hub.repository.OperatorRelationshipRepository
import com.lhs.share.hub.repository.entity.OperatorRelationships
import com.lhs.share.testinfra.TestMongo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

@Tag("integration")
class OperatorRelationshipMongoTest {
    private val database = TestMongo.database("relation")
    private val client = TestMongo.client()
    private val template = MongoTemplate(SimpleMongoClientDatabaseFactory(client, database))
    private val repository = OperatorRelationshipRepository(template)
    private val transaction = TransactionTemplate(MongoTransactionManager(template.mongoDatabaseFactory))

    @BeforeEach fun setup() {
        template.createCollection(OperatorRelationships::class.java)
    }

    @AfterEach fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }

    @Test fun `isolation scoped deletion and substantive zero`() {
        for ((user, account, game) in listOf(
            listOf("u", "a", "如鸢"),
            listOf("v", "a", "如鸢"),
            listOf("u", "b", "如鸢"),
            listOf("u", "a", "代号鸢"),
        )) {
            repository.replace(user, account, game, 0, """{"op":{"trust":0}}""")
        }
        assertTrue(repository.hasSubstantiveData("u", "a"))
        assertThrows(DuplicateKeyException::class.java) { repository.replace("u", "a", "如鸢", 0, "{}") }
        assertNull(repository.replace("u", "a", "如鸢", 8, "{}"))
        repository.deleteAccount("u", "a")
        assertNull(repository.find("u", "a", "如鸢"))
        assertNull(repository.find("u", "a", "代号鸢"))
        assertNotNull(repository.find("v", "a", "如鸢"))
        assertNotNull(repository.find("u", "b", "如鸢"))
        assertFalse(repository.hasSubstantiveData("u", "a"))
    }

    @Test fun `concurrent revision has one winner and transaction restores complete batch`() {
        repository.replace("u", "a", "如鸢", 0, "{}")
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val result = executor.invokeAll(
                (1..2).map { n ->
                    Callable {
                        barrier.await()
                        repository.replace("u", "a", "如鸢", 1, """{"op":{"trust":$n}}""")
                    }
                },
            ).map { it.get() }
            assertEquals(1, result.count { it != null })
        } finally {
            executor.shutdownNow()
        }
        val before = repository.find("u", "a", "如鸢")
        assertThrows(IllegalStateException::class.java) {
            transaction.executeWithoutResult {
                repository.replace("u", "a", "如鸢", 2, "{}")
                error("rollback")
            }
        }
        assertEquals(before, repository.find("u", "a", "如鸢"))
    }
}
