package io.dossier.app

import io.dossier.app.data.face.FaceEmbeddingModelStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEmbeddingModelStoreTest {

    @Test
    fun acceptsOnlyKnownFaceEmbeddingModelExtensions() {
        assertTrue(FaceEmbeddingModelStore.acceptsFileName("facenet.tflite"))
        assertTrue(FaceEmbeddingModelStore.acceptsFileName("arcface.ONNX"))
        assertFalse(FaceEmbeddingModelStore.acceptsFileName("notes.txt"))
        assertFalse(FaceEmbeddingModelStore.acceptsFileName("model.task"))
    }
}
