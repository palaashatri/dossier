package io.dossier.app

import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.ReverseVideoLookupService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReverseVideoLookupServiceTest {

    @Test
    fun sampleTimestamps_spreadsFramesAcrossDuration() {
        val timestamps = ReverseVideoLookupService.sampleTimestamps(durationMs = 12_000L, maxFrames = 5)

        assertEquals(listOf(2_000L, 4_000L, 6_000L, 8_000L, 10_000L), timestamps)
    }

    @Test
    fun sampleTimestamps_handlesShortAndUnknownVideos() {
        assertEquals(listOf(400L), ReverseVideoLookupService.sampleTimestamps(durationMs = 800L))
        assertEquals(listOf(0L), ReverseVideoLookupService.sampleTimestamps(durationMs = null))
        assertEquals(emptyList<Long>(), ReverseVideoLookupService.sampleTimestamps(durationMs = 10_000L, maxFrames = 0))
    }

    @Test
    fun mergeFrameText_dedupesAndReturnsNullWhenBlank() {
        val merged = ReverseVideoLookupService.mergeFrameText(
            listOf("Times Square\nBroadway", "  times square\nbroadway  ", "Subway Entrance")
        )

        assertEquals("Times Square\nBroadway\n\nSubway Entrance", merged)
        assertNull(ReverseVideoLookupService.mergeFrameText(listOf(" ", "\n")))
    }

    @Test
    fun mergeFrameLabels_keepsHighestConfidencePerLabel() {
        val labels = ReverseVideoLookupService.mergeFrameLabels(
            listOf(
                listOf(
                    ReverseImageLookupResult.ImageLabel("Street", 0.54f),
                    ReverseImageLookupResult.ImageLabel("Signage", 0.91f)
                ),
                listOf(
                    ReverseImageLookupResult.ImageLabel("street", 0.77f),
                    ReverseImageLookupResult.ImageLabel("Building", 0.62f)
                )
            )
        )

        assertEquals(listOf("Signage", "street", "Building"), labels.map { it.text })
        assertEquals(0.77f, labels[1].confidence, 0.001f)
    }
}
