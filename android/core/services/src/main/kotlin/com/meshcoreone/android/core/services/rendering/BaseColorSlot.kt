// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/BaseColorSlot.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/**
 * Direction-tagged colour slot for chat-message text. The view layer resolves [OUTGOING] to the
 * on-bubble text colour and [INCOMING] to the primary text colour at render time, so rendering
 * models stay free of UI colour types.
 */
enum class BaseColorSlot { OUTGOING, INCOMING }
