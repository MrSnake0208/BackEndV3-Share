package com.lhs.share.hub.service.media

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.hub.repository.MediaAssetRepository
import com.lhs.share.hub.repository.entity.MediaAsset
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Locale

/**
 * Hub 库媒体文件存储服务
 *
 * 处理文件上传校验、磁盘存储及元数据持久化。
 * 文件存储路径: {share.media.dir}/{medId}.{ext},通过 MediaStaticResourceConfig 映射到 /media/。
 */
@Service
class MediaStorageService(
    private val mediaAssetRepository: MediaAssetRepository,
    private val properties: ShareProperties,
) {
    private val random = SecureRandom()
    private val hexChars = "0123456789abcdef"

    /** 允许的 MIME 类型 */
    private val allowedMimes = setOf("image/jpeg", "image/png", "image/webp")

    /** MIME 到扩展名的映射 */
    private val mimeToExt = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
    )

    /**
     * 上传文件:校验 → 临时文件 → 正式文件 → 元数据持久化。
     *
     * @param ownerUserId 上传用户 id
     * @param file 上传的 multipart 文件
     * @return 持久化后的 [MediaAsset]
     * @throws ResponseStatusException 校验失败时抛出
     */
    fun upload(ownerUserId: String, file: MultipartFile): MediaAsset {
        // 校验文件非空
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件为空")
        }

        // 校验声明的 MIME 类型,但最终类型仍由文件签名确认
        val declaredMime = file.contentType?.trim()
        if (declaredMime.isNullOrEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "无法识别文件类型")
        }
        val mime = declaredMime.lowercase(Locale.ROOT)
        if (mime !in allowedMimes) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "不支持的文件类型: $mime, 仅支持 JPG/PNG/WebP",
            )
        }

        // 先用 multipart 元数据快速拒绝明显超限的请求,落盘后再按实际字节数复核
        val maxSize = properties.media.maxSize
        if (file.size > maxSize) {
            throw ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "文件大小超过限制(${maxSize / 1024 / 1024} MiB)",
            )
        }

        // 生成唯一 id
        val medId = generateMedId()
        val ext = mimeToExt[mime] ?: "bin"
        val storedFileName = "$medId.$ext"
        val storagePath = "/media/$storedFileName"

        // 确保存储目录存在
        val mediaDir = Path.of(properties.media.dir).toAbsolutePath()
        try {
            Files.createDirectories(mediaDir)
        } catch (e: IOException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建存储目录")
        }

        val tempPath = try {
            Files.createTempFile(mediaDir, "$medId-", ".upload")
        } catch (e: IOException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建临时文件", e)
        }
        val targetPath = mediaDir.resolve(storedFileName)
        try {
            file.inputStream.use { input ->
                Files.copy(input, tempPath, StandardCopyOption.REPLACE_EXISTING)
            }
            val storedSize = Files.size(tempPath)
            if (storedSize == 0L) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件为空")
            }
            if (storedSize > maxSize) {
                throw ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件大小超过限制(${maxSize / 1024 / 1024} MiB)",
                )
            }
            if (!hasExpectedSignature(tempPath, mime)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容与声明类型不匹配")
            }

            moveToTarget(tempPath, targetPath)

            // 持久化元数据;任一步失败都清理本次产生的文件
            val asset = MediaAsset(
                id = medId,
                ownerUserId = ownerUserId,
                originalName = file.originalFilename ?: storedFileName,
                mime = mime,
                size = storedSize,
                storagePath = storagePath,
            )
            return mediaAssetRepository.save(asset)
        } catch (e: ResponseStatusException) {
            cleanup(tempPath, targetPath)
            throw e
        } catch (e: IOException) {
            cleanup(tempPath, targetPath)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件写入失败", e)
        } catch (e: Exception) {
            cleanup(tempPath, targetPath)
            throw e
        }
    }

    private fun moveToTarget(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun hasExpectedSignature(path: Path, mime: String): Boolean {
        val headerLength = if (mime == "image/webp") WEBP_HEADER_SIZE else 8
        val header = Files.newInputStream(path).use { input -> input.readNBytes(headerLength) }
        return when (mime) {
            "image/jpeg" -> startsWith(header, JPEG_SIGNATURE)
            "image/png" -> startsWith(header, PNG_SIGNATURE)
            "image/webp" -> startsWith(header, RIFF_SIGNATURE) && startsWith(header, WEBP_SIGNATURE, 8)
            else -> false
        }
    }

    private fun startsWith(actual: ByteArray, expected: ByteArray, offset: Int = 0): Boolean {
        return actual.size >= offset + expected.size && expected.indices.all { actual[offset + it] == expected[it] }
    }

    private fun cleanup(tempPath: Path, targetPath: Path) {
        deleteQuietly(tempPath)
        deleteQuietly(targetPath)
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // 清理失败不能覆盖原始上传或元数据异常
        }
    }

    /**
     * 生成 med_<16位hex> 格式的唯一 id
     */
    private fun generateMedId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "${hexChars[v shr 4]}${hexChars[v and 0x0F]}"
        }
        return "med_$hex"
    }

    companion object {
        private const val WEBP_HEADER_SIZE = 12
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        )
        private val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
            .map { it.toByte() }
            .toByteArray()
        private val WEBP_SIGNATURE = byteArrayOf(0x57, 0x45, 0x42, 0x50)
            .map { it.toByte() }
            .toByteArray()
    }
}
