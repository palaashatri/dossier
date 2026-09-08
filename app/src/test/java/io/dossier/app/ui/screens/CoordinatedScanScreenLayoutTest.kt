package io.dossier.app.ui.screens

import io.dossier.app.domain.discovery.LiveScanSnapshot
import io.dossier.app.domain.discovery.ScanRunState
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinatedScanScreenLayoutTest {
    @Test
    fun diagnosticsReserveSpaceOnlyForVisibleLiveCards() {
        assertEquals(
            0f,
            scanDiagnosticsTopInset(LiveScanSnapshot()).value,
            0.001f
        )
        assertEquals(
            0f,
            scanDiagnosticsTopInset(
                LiveScanSnapshot(state = ScanRunState.Running)
            ).value,
            0.001f
        )
        assertEquals(
            96f,
            scanDiagnosticsTopInset(
                LiveScanSnapshot(
                    state = ScanRunState.Running,
                    pivotMaxDepth = 1
                )
            ).value,
            0.001f
        )
        assertEquals(
            120f,
            scanDiagnosticsTopInset(
                LiveScanSnapshot(
                    state = ScanRunState.Running,
                    pivotMaxDepth = 1,
                    recoveryStage = "DISCOVERING_USERNAMES"
                )
            ).value,
            0.001f
        )
    }
}
