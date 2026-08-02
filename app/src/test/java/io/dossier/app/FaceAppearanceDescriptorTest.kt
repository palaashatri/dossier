package io.dossier.app

import io.dossier.app.data.face.FaceAppearanceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAppearanceDescriptorTest {
    @Test
    fun identicalDescriptorsHavePerfectCosineSimilarity() {
        val descriptor = floatArrayOf(0.2f, 0.4f, 0.8f, 0.1f)
        assertEquals(1f, FaceAppearanceDescriptor.cosineSimilarity(descriptor, descriptor), 0.0001f)
    }

    @Test
    fun unrelatedDescriptorsScoreLower() {
        val first = floatArrayOf(1f, 0f, 0f, 0f)
        val second = floatArrayOf(0f, 1f, 0f, 0f)
        assertTrue(FaceAppearanceDescriptor.cosineSimilarity(first, second) < 0.1f)
    }

    @Test
    fun mismatchedDescriptorLengthsFailClosed() {
        assertEquals(
            0f,
            FaceAppearanceDescriptor.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)),
            0.0001f
        )
    }
}
