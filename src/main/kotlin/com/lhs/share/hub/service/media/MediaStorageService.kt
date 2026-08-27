package com.lhs.share.hub.service.media

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.hub.repository.MediaAssetRepository
import com.lhs.share.hub.repository.entity.MediaAsset
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant

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
     * 上传文件:校验 → 持久化 → 返回元数据
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

        // 校验 MIME 类型
        val mime = file.contentType ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "无法识别文件类型")
        if (mime !in allowedMimes) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "不支持的文件类型: $mime, 仅支持 JPG/PNG/WebP",
            )
        }

        // 校验文件大小(≤10MB)
        val maxSize = properties.media.maxSize
        if (file.size > maxSize) {
            throw ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "文件大小超过限制(${maxSize / 1024 / 1024}MB)",
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

        // 写入磁盘
        val targetPath = mediaDir.resolve(storedFileName)
        try {
            file.inputStream.use { input ->
                Files.copy(input, targetPath)
            }
        } catch (e: IOException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件写入失败")
        }

        // 持久化元数据
        val asset = MediaAsset(
            id = medId,
            ownerUserId = ownerUserId,
            originalName = file.originalFilename ?: storedFileName,
            mime = mime,
            size = file.size,
            storagePath = storagePath,
        )
        return mediaAssetRepository.save(asset)
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
}