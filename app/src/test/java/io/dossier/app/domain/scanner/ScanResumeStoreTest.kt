package io.dossier.app.domain.scanner

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MemoryGuardTest {

    private fun finding(i: Int) = Finding(
        type = FindingType.Email,
        value = "user$i@example.com",
        sourceUrl = null,
        evidenceSnippet = null,
        confidence = 0.5f,
        risk = RiskLevel.Low,
        remediation = ""
    )

    @Test
    fun retainsBelowCap() {
        val inList = List(10) { finding(it) }
        val result = MemoryGuard.cap(inList)
        assertEquals(10, result.retained.size)
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun dropsAboveCap() {
        val inList = List(MemoryGuard.MAX_FINDINGS + 25) { finding(it) }
        val result = MemoryGuard.cap(inList)
        assertEquals(MemoryGuard.MAX_FINDINGS, result.retained.size)
        assertEquals(25, result.droppedCount)
    }

    @Test
    fun exactlyAtCapDropsNothing() {
        val inList = List(MemoryGuard.MAX_FINDINGS) { finding(it) }
        val result = MemoryGuard.cap(inList)
        assertEquals(MemoryGuard.MAX_FINDINGS, result.retained.size)
        assertEquals(0, result.droppedCount)
    }
}

class ScanResumeStoreTest {

    @Test
    fun saveLoadRoundTripsInputAndFlag() {
        val dir = File.createTempFile("resume", "").also { it.delete(); it.mkdirs() }
        try {
            val store = ScanResumeStore(dir)
            val input = io.dossier.app.domain.model.IdentityInput(
                fullName = "Jane Doe",
                primaryUsername = "janedoe",
                usernames = listOf("janedoe", "jane_d"),
                emails = listOf("jane@example.com"),
                phones = listOf("+1-555-0100"),
                organizations = listOf("Acme"),
                locations = listOf("Berlin"),
                profileUrls = listOf("https://github.com/janedoe")
            )
            assert(store.save(input, deepResearch = true))

            val loaded = store.load()
            assertEquals(input, loaded?.first)
            assertEquals(true, loaded?.second)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadReturnsNullWhenEmpty() {
        val dir = File.createTempFile("resume2", "").also { it.delete(); it.mkdirs() }
        try {
            assertEquals(null, ScanResumeStore(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearRemovesResumePoint() {
        val dir = File.createTempFile("resume3", "").also { it.delete(); it.mkdirs() }
        try {
            val store = ScanResumeStore(dir)
            store.save(
                io.dossier.app.domain.model.IdentityInput(fullName = "X"),
                deepResearch = false
            )
            assertEquals(true, store.load() != null)
            store.clear()
            assertEquals(null, store.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
