// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/PreviewLoadState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/** State of link preview loading for a message. */
enum class PreviewLoadState { IDLE, LOADING, LOADED, NO_PREVIEW, DISABLED, MALWARE_WARNING }
