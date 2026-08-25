package io.dossier.app.domain.case

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseStoreSecurityTest {

    @Test
    fun rejectsTraversalAbsoluteControlAndOversizedCaseIds() {
        listOf(
            "",
            " ",
            ".",
            "..",
            "../outside",
            "..\\outside",
            "/absolute",
            "C:\\absolute",
            "case\u0000id",
            "case\n-id",
            "x".repeat(CaseStoreStoragePolicy.MAX_CASE_ID_LENGTH + 1)
        ).forEach { value ->
            assertFalse("unsafe case id should be rejected: $value", CaseStoreStoragePolicy.isSafeCaseId(value))
        }
    }

    @Test
    fun acceptsUuidAndLegacySafeCaseIds() {
        assertTrue(CaseStoreStoragePolicy.isSafeCaseId("legacy-v3"))
        assertTrue(CaseStoreStoragePolicy.isSafeCaseId("case.v8_1-2"))
        assertTrue(CaseStoreStoragePolicy.isSafeCaseId("123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun confinedPathStaysInsideCanonicalCaseDirectory() {
        val root = Files.createTempDirectory("dossier-case-path").toFile()
        try {
            val inside = CaseStoreStoragePolicy.confinedPath(root, "legacy-v3", "dcase")
            assertNotNull(inside)
            assertEquals(root.canonicalFile, requireNotNull(inside).parentFile)
            assertNull(CaseStoreStoragePolicy.confinedPath(root, "../outside", "dcase"))
            assertNull(CaseStoreStoragePolicy.confinedPath(root, "legacy-v3", "../outside"))
            assertNull(CaseStoreStoragePolicy.confinedPath(root, "legacy-v3", "dcase\\outside"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scopedFilesIncludesOnlyCaseTempFilesInsideRoot() {
        val root = Files.createTempDirectory("dossier-case-scope").toFile()
        try {
            val temp = File(root, "legacy-v3.dcase.pending.tmp").apply { writeText("encrypted envelope") }
            File(root, "other-case.dcase.pending.tmp").writeText("other")
            val plan = CaseStoreStoragePolicy.scopedFiles(
                root = root,
                caseId = "legacy-v3",
                encryptedExtension = "dcase",
                legacyExtension = "json",
                backupExtension = "bak",
                tempExtension = "tmp"
            )
            assertNotNull(plan)
            assertTrue(requireNotNull(plan).files.contains(temp))
            assertEquals(0, requireNotNull(plan).unsafeFileCount)
            assertEquals(4, requireNotNull(plan).files.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearPlanRecognizesOnlyCaseStorageExtensions() {
        val root = Files.createTempDirectory("dossier-case-clear").toFile()
        try {
            val expected = listOf("one.dcase", "two.json", "three.tmp", "four.bak")
                .map { File(root, it).apply { writeText("bounded") } }
            File(root, "ignored.txt").writeText("not case storage")
            val plan = CaseStoreStoragePolicy.clearPlan(
                root,
                setOf("dcase", "json", "tmp", "bak")
            )
            assertNotNull(plan)
            assertEquals(0, requireNotNull(plan).unsafeFileCount)
            assertEquals(expected.toSet(), requireNotNull(plan).files.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteAllAttemptsEveryFileAndReportsAnyFailure() {
        val root = Files.createTempDirectory("dossier-case-delete").toFile()
        try {
            val files = listOf("first.dcase", "second.dcase", "third.dcase")
                .map { File(root, it).apply { writeText("bounded") } }
            val attempts = mutableListOf<File>()
            var syncCalls = 0
            val result = CaseStoreStoragePolicy.deleteAll(
                files = files,
                deleteFile = { file ->
                    attempts += file
                    file != files[1]
                },
                syncDirectory = {
                    syncCalls += 1
                    true
                }
            )
            assertFalse(result)
            assertEquals(files, attempts)
            assertEquals(1, syncCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteAllReturnsTrueOnlyAfterEveryDeletionAndDirectorySync() {
        val root = Files.createTempDirectory("dossier-case-delete-ok").toFile()
        try {
            val files = listOf("first.dcase", "second.dcase")
                .map { File(root, it).apply { writeText("bounded") } }
            var syncCalls = 0
            val result = CaseStoreStoragePolicy.deleteAll(
                files = files,
                deleteFile = { file -> file.delete() },
                syncDirectory = {
                    syncCalls += 1
                    true
                }
            )
            assertTrue(result)
            assertEquals(1, syncCalls)
            assertTrue(files.none(File::exists))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteAllFailsClosedWhenDirectorySyncFails() {
        val root = Files.createTempDirectory("dossier-case-delete-sync").toFile()
        try {
            val file = File(root, "case.dcase").apply { writeText("bounded") }
            val result = CaseStoreStoragePolicy.deleteAll(
                files = listOf(file),
                deleteFile = { it.delete() },
                syncDirectory = { false }
            )
            assertFalse(result)
            assertFalse(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
