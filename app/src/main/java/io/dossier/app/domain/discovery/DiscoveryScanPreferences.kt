package io.dossier.app.domain.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local scan-mode selection used by the current scanner compatibility
 * path. M2 will move this into a persisted ScanRequest owned by ScanCoordinator.
 */
object DiscoveryScanPreferences {
    private val _selectedMode = MutableStateFlow(ScanMode.Standard)
    val selectedMode: StateFlow<ScanMode> = _selectedMode

    fun setMode(mode: ScanMode) {
        _selectedMode.value = mode
    }

    fun reset() {
        _selectedMode.value = ScanMode.Standard
    }
}
