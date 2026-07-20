package io.dossier.app.domain.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScanSessionCancelTest {

    @Test
    fun cancelScanResetsScanningState() {
        // No scan running → cancel is a safe no-op that clears scanning flags.
        ScanSession.cancelScan()
        assertEquals(false, ScanSession.isScanning.value)
        assertEquals("SCAN_CANCELLED", ScanSession.progressText.value)
    }
}
