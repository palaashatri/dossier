package io.dossier.app

import io.dossier.app.domain.ai.LocalAiModelType
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiModelTest {

    @Test
    fun gemmaEdgeModels_havePublicHuggingFaceDownloadTargets() {
        assertTrue(LocalAiModelType.GEMMA_E2B.downloadable)
        assertTrue(LocalAiModelType.GEMMA_E4B.downloadable)
        assertTrue(!LocalAiModelType.PALIGEMMA.downloadable)
        assertTrue(LocalAiModelType.PALIGEMMA.url.isBlank())
        assertTrue(LocalAiModelType.PALIGEMMA.fileName.endsWith(".task"))
        assertTrue(LocalAiModelType.GEMMA_E2B.url.contains("huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"))
        assertTrue(LocalAiModelType.GEMMA_E4B.url.contains("huggingface.co/litert-community/gemma-4-E4B-it-litert-lm"))
        assertTrue(LocalAiModelType.GEMMA_E2B.fileName.endsWith(".task"))
        assertTrue(LocalAiModelType.GEMMA_E4B.fileName.endsWith(".task"))
    }
}
