package com.lhs.share.hub.service.report

import com.lhs.share.hub.repository.FeedbackCategoryCatalogRepository
import com.lhs.share.testinfra.TestMongo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory

@Tag("integration")
class FeedbackCategoryMongoTest {
    private val database = TestMongo.database("feedback_categories")
    private val client = TestMongo.client()
    private val repository = MongoRepositoryFactory(MongoTemplate(SimpleMongoClientDatabaseFactory(client, database)))
        .getRepository(FeedbackCategoryCatalogRepository::class.java)

    @AfterEach
    fun cleanup() {
        TestMongo.dropDatabase(client, database)
        client.close()
    }

    @Test
    fun `目录持久化且并发旧版本不能覆盖新名称`() {
        val service = FeedbackCategoryService(repository)
        val category = service.create("新板块")
        val stale = repository.findById("feedback").orElseThrow()
        service.rename(category.key, "新名称")

        assertEquals("新名称", FeedbackCategoryService(repository).label(category.key))
        assertThrows(OptimisticLockingFailureException::class.java) {
            repository.save(stale.copy(categories = stale.categories.filterNot { it.key == category.key }))
        }
        assertEquals("新名称", service.label(category.key))
    }
}
