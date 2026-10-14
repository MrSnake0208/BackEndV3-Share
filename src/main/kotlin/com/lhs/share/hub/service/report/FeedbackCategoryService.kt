package com.lhs.share.hub.service.report

import com.lhs.share.controller.response.ApiResultException
import com.lhs.share.hub.repository.FeedbackCategoryCatalogRepository
import com.lhs.share.hub.repository.entity.FeedbackCategory
import com.lhs.share.hub.repository.entity.FeedbackCategoryCatalog
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.Locale
import java.util.UUID

@Service
class FeedbackCategoryService(private val repository: FeedbackCategoryCatalogRepository) {
    fun list(): List<FeedbackCategory> = snapshot().categories

    fun keys(): Set<String> = list().mapTo(linkedSetOf()) { it.key }

    fun label(key: String): String? = list().find { it.key == key }?.label

    fun requireValid(key: String): String {
        val normalized = key.trim().uppercase(Locale.ROOT)
        if (normalized !in keys()) throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "无效的反馈板块: $key")
        return normalized
    }

    fun create(label: String): FeedbackCategory {
        val current = snapshot()
        val normalized = validateLabel(label, current.categories)
        val category = FeedbackCategory("CUSTOM_${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)}", normalized)
        save(current.copy(categories = current.categories + category))
        return category
    }

    fun rename(key: String, label: String): Pair<FeedbackCategory, FeedbackCategory> {
        val current = snapshot()
        val existing = current.categories.find { it.key == key }
            ?: throw ApiResultException(HttpStatus.NOT_FOUND.value(), "反馈板块不存在: $key")
        val normalized = validateLabel(label, current.categories.filterNot { it.key == key })
        val renamed = existing.copy(label = normalized)
        if (renamed != existing) save(current.copy(categories = current.categories.map { if (it.key == key) renamed else it }))
        return existing to renamed
    }

    private fun snapshot(): FeedbackCategoryCatalog {
        val current = repository.findById("feedback").orElseGet {
            FeedbackCategoryCatalog(categories = FeedbackArea.labels.map { (key, label) -> FeedbackCategory(key, label) })
        }
        // 只补缺失日历；复用已建同名板块，不改历史标识、授权，也不在读取时写库。
        val calendar = current.categories.find { it.key == FeedbackArea.CALENDAR || it.builtinKey == FeedbackArea.CALENDAR }
            ?: current.categories.find { it.label.trim() == FeedbackArea.labels.getValue(FeedbackArea.CALENDAR) }
        if (calendar != null) {
            if (calendar.key == FeedbackArea.CALENDAR || calendar.builtinKey == FeedbackArea.CALENDAR) return current
            val registered = calendar.copy(builtinKey = FeedbackArea.CALENDAR)
            return current.copy(categories = current.categories.map { if (it == calendar) registered else it })
        }
        val added = FeedbackCategory(FeedbackArea.CALENDAR, FeedbackArea.labels.getValue(FeedbackArea.CALENDAR))
        return current.copy(categories = current.categories + added)
    }

    private fun validateLabel(label: String, others: List<FeedbackCategory>): String {
        val normalized = label.trim()
        if (normalized.isEmpty() || normalized.length > 24) {
            throw ApiResultException(HttpStatus.BAD_REQUEST.value(), "反馈板块名称需为 1–24 个字符")
        }
        if (others.any { it.label.lowercase(Locale.ROOT) == normalized.lowercase(Locale.ROOT) }) {
            throw ApiResultException(HttpStatus.CONFLICT.value(), "反馈板块名称已存在")
        }
        return normalized
    }

    private fun save(catalog: FeedbackCategoryCatalog) {
        try {
            repository.save(catalog)
        } catch (e: OptimisticLockingFailureException) {
            throw ApiResultException(HttpStatus.CONFLICT.value(), "反馈板块目录已更新，请刷新后重试")
        } catch (e: DuplicateKeyException) {
            throw ApiResultException(HttpStatus.CONFLICT.value(), "反馈板块目录已更新，请刷新后重试")
        }
    }
}
