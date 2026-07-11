package io.dossier.app

import io.dossier.app.data.face.FaceEmbeddingModelRunner
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceEmbeddingModelRunnerTest {

    @Test
    fun cosineSimilarityScoresNormalizedEmbeddings() {
        val same = FaceEmbeddingModelRunner.cosineSimilarity(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f)
        )
        val orthogonal = FaceEmbeddingModelRunner.cosineSimilarity(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f)
        )

        assertEquals(1f, same, 0.0001f)
        assertEquals(0f, orthogonal, 0.0001f)
    }
}
