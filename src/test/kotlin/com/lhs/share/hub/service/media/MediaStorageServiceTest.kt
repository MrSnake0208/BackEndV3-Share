package com.lhs.share.hub.service.media

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.hub.repository.MediaAssetRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path

class MediaStorageServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var repository: MediaAssetRepository

    @BeforeEach
    fun setUp() {
        repository = mockk()
    }

    @Test
    fun `supported image signatures are stored with generated metadata`() {
        every { repository.save(any()) } answers { firstArg() }
        val cases = listOf(
            Triple("screen.jpg", "image/jpeg", jpegBytes()),
            Triple("screen.png", "image/png", pngBytes()),
            Triple("screen.webp", "image/webp", webpBytes()),
        )

        cases.forEach { (name, mime, bytes) ->
            val asset = storage().upload("user-1", file(name, mime, bytes))

            assertTrue(asset.id!!.matches(Regex("med_[0-9a-f]{16}")))
            assertEquals("user-1", asset.ownerUserId)
            assertEquals(name, asset.originalName)
            assertEquals(mime, asset.mime)
            assertEquals(bytes.size.toLong(), asset.size)
            assertTrue(asset.storagePath.endsWith(".${name.substringAfterLast('.')}"))
            assertArrayEquals(bytes, Files.readAllBytes(tempDir.resolve(asset.storagePath.substringAfterLast('/'))))
        }
    }

    @Test
    fun `empty unsupported and mismatched content are rejected`() {
        val service = storage()

        assertStatus(HttpStatus.BAD_REQUEST) {
            service.upload("user-1", file("empty.png", "image/png", ByteArray(0)))
        }
        assertStatus(HttpStatus.BAD_REQUEST) {
            service.upload("user-1", file("note.txt", "text/plain", "not an image".toByteArray()))
        }
        assertStatus(HttpStatus.BAD_REQUEST) {
            service.upload("user-1", file("wrong.jpg", "image/jpeg", pngBytes()))
        }
    }

    @Test
    fun `configured size limit is enforced`() {
        assertStatus(HttpStatus.PAYLOAD_TOO_LARGE) {
            storage(maxSize = 3).upload("user-1", file("screen.jpg", "image/jpeg", jpegBytes()))
        }
        assertFalse(Files.exists(tempDir.resolve("screen.jpg")))
    }

    @Test
    fun `repository failure removes temporary and formal files`() {
        val failure = IllegalStateException("mongo unavailable")
        every { repository.save(any()) } throws failure

        val thrown = assertThrows(IllegalStateException::class.java) {
            storage().upload("user-1", file("screen.png", "image/png", pngBytes()))
        }

        assertSame(failure, thrown)
        Files.list(tempDir).use { entries -> assertEquals(0, entries.count()) }
    }

    @Test
    fun `client filename never participates in the stored path`() {
        every { repository.save(any()) } answers { firstArg() }

        val asset = storage().upload(
            "user-1",
            file("../../outside.png", "image/png", pngBytes()),
        )

        assertEquals("../../outside.png", asset.originalName)
        assertTrue(asset.storagePath.matches(Regex("/media/med_[0-9a-f]{16}\\.png")))
        assertTrue(Files.exists(tempDir.resolve(asset.storagePath.substringAfterLast('/'))))
        assertFalse(Files.exists(tempDir.resolve("outside.png")))
    }

    private fun storage(maxSize: Long = 10 * 1024 * 1024): MediaStorageService {
        val properties = ShareProperties().apply {
            media = ShareProperties.Media(tempDir.toString(), maxSize)
        }
        return MediaStorageService(repository, properties)
    }

    private fun file(name: String, mime: String, bytes: ByteArray): MockMultipartFile =
        MockMultipartFile("file", name, mime, bytes)

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        val exception = assertThrows(ResponseStatusException::class.java, block)
        assertEquals(status.value(), exception.statusCode.value())
    }

    private fun jpegBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        0x00,
    )

    private fun webpBytes(): ByteArray = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50,
    )
}
