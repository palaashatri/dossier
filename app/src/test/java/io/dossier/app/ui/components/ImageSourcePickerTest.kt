package io.dossier.app.ui.components

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSourcePickerTest {
    @Test
    fun boundedCopyPreservesBytesAtTheLimit() {
        val input = "synthetic image bytes".toByteArray()
        val output = ByteArrayOutputStream()

        val copied = ImageSourceStorage.copyBounded(
            input = ByteArrayInputStream(input),
            output = output,
            maxBytes = input.size.toLong()
        )

        assertEquals(input.size.toLong(), copied)
        assertArrayEquals(input, output.toByteArray())
    }

    @Test(expected = ImageSourceStorage.ImageTooLargeException::class)
    fun boundedCopyRejectsBytesPastTheLimit() {
        ImageSourceStorage.copyBounded(
            input = ByteArrayInputStream(ByteArray(9)),
            output = ByteArrayOutputStream(),
            maxBytes = 8
        )
    }

    @Test
    fun ownedImageCleanupDeletesOnlyFilesUnderImageDirectory() {
        val root = Files.createTempDirectory("dossier-image-cleanup").toFile()
        try {
            val images = File(root, "images").apply { mkdirs() }
            val owned = File(images, "selected.jpg").apply { writeBytes(byteArrayOf(1)) }
            val outside = File(root, "outside.jpg").apply { writeBytes(byteArrayOf(1)) }

            assertTrue(ImageSourceStorage.deleteOwnedFile(images, owned.absolutePath))
            assertFalse(owned.exists())
            assertFalse(ImageSourceStorage.deleteOwnedFile(images, outside.absolutePath))
            assertTrue(outside.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
