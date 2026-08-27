package com.lhs.share.hub.controller.media

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.external.ShareProperties
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.media.response.MediaUploadResponse
import com.lhs.share.hub.service.media.MediaStorageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 媒体文件上传接口
 *
 * 提供文件上传能力,支持 JPG/PNG/WebP 格式,单文件上限 10MB。
 * 上传后的文件通过 /media/{medId}.{ext} 静态资源路径公开访问。
 */
@Tag(name = "Media", description = "媒体文件上传")
@RequestMapping("/v1/media")
@RestController
class MediaController(
    private val mediaStorageService: MediaStorageService,
    private val helper: AuthenticationHelper,
    private val properties: ShareProperties,
) {
    /**
     * 上传媒体文件(需登录)
     *
     * @param file 上传的图片文件(multipart/form-data,字段名 file)
     * @return 上传后的文件元数据,包含可直接访问的 URL
     */
    @Operation(summary = "上传媒体文件")
    @RequireJwt
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestParam("file") file: MultipartFile): ApiResult<MediaUploadResponse> {
        val userId = helper.requireUserId()
        val asset = mediaStorageService.upload(userId, file)
        val baseUrl = properties.info.publicBaseUrl
        return success(MediaUploadResponse.of(asset, baseUrl))
    }
}