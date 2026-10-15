package com.lhs.share.hub.service.recruitment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.testinfra.TestMongo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Execute the actual operator script; never accept an external database URI. */
@Tag("integration")
class RecruitmentDuplicateRepairMongoTest {
    @Test
    fun `operator repair is atomic isolated guarded repeatable and reversible`() {
        val database = TestMongo.database("recr_repair")
        val uri = TestMongo.uri(database)
        assertTrue(TestMongo.isOwnedUri(uri))
        val script = Path.of("scripts/migrations/20261015-recruitment-duplicate-pools.mongo-test.js").toAbsolutePath()
        val quoted = jacksonObjectMapper().writeValueAsString(script.toString())
        val directory = Path.of("build/test-data/recruitment-duplicate-repair").toAbsolutePath()
        Files.createDirectories(directory)
        val source = jacksonObjectMapper().writeValueAsString(directory.resolve("source.json").toString())
        val output = directory.resolve("mongo.log")
        try {
            val process = ProcessBuilder(
                "mongosh",
                uri,
                "--quiet",
                "--eval",
                "(async () => { print(await require($quoted)(db, { Long, EJSON }, $source)); })()",
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "mongosh timed out; see $output")
                val log = Files.readString(output)
                assertEquals(0, process.exitValue(), log)
                assertTrue(log.contains("RECRUITMENT_REPAIR_MONGO_OK"), log)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            for (name in listOf("20261015-recruitment-duplicate-pools.js", "20261015-recruitment-duplicate-pools.run.js")) {
                Files.copy(Path.of("scripts/migrations/$name"), directory.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            for ((action, status) in listOf(
                "dry-run" to "dry_run",
                "apply" to "applied",
                "apply" to "already_applied",
                "rollback" to "rolled_back",
            )) {
                val launcher = ProcessBuilder("mongosh", uri, "--quiet", "--file", "20261015-recruitment-duplicate-pools.run.js")
                    .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
                launcher.environment().remove("YUANHUB_REPAIR_ACTION")
                if (action != "dry-run") launcher.environment()["YUANHUB_REPAIR_ACTION"] = action
                val process = launcher.start()
                try {
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "launcher timed out; see $output")
                    val log = Files.readString(output)
                    assertEquals(0, process.exitValue(), log)
                    assertTrue(log.contains("\"status\": \"$status\""), log)
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        } finally {
            TestMongo.client().use { TestMongo.dropDatabase(it, database) }
        }
    }
}
