package io.dossier.app.ui.screens

import io.dossier.app.domain.discovery.ScanMode

internal val ScanMode.displayName: String
    get() = when (this) {
        ScanMode.Quick -> "Quick"
        ScanMode.Standard -> "Standard"
        ScanMode.Deep -> "Deep"
        ScanMode.Exhaustive -> "Exhaustive"
    }
