package io.dossier.app.domain.scanner

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackgroundScanResultStoreTest {
    private val root = Files.createTempDirectory("dossier-result-store-test").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun atomicEnvelopeReplaceKeepsOneTargetAndSyncsDirectory() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val store = BackgroundScanResultStore(context, syncer)
        val target = File(root, "background_scan_latest.dscan")
        target.writeText("old-envelope")

        store.writeEnvelopeAtomically(target, "new-envelope")

        assertEquals("new-envelope", target.readText())
        assertEquals(listOf(root.canonicalFile), syncer.synced.map(File::getCanonicalFile))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun atomicEnvelopeWriteCreatesMissingParent() {
        val syncer = RecordingDirectorySyncer()
        val context = FakeContext(root)
        val store = BackgroundScanResultStore(context, syncer)
        val nested = File(root, "nested/result.dscan")

        store.writeEnvelopeAtomically(nested, "encrypted-envelope")

        assertTrue(nested.isFile)
        assertEquals("encrypted-envelope", nested.readText())
        assertEquals(requireNotNull(nested.parentFile).canonicalFile, syncer.synced.single().canonicalFile)
    }

    private class RecordingDirectorySyncer : DirectorySyncer {
        val synced = mutableListOf<File>()

        override fun sync(dir: File) {
            synced += dir
        }
    }

    private class FakeContext(private val root: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }
}
