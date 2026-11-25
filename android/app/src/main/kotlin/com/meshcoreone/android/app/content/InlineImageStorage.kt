// PortedFrom: MC1Services/Sources/MC1Services/Services/InlineImageDimensionsStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.content.Context
import com.meshcoreone.android.core.services.content.InlineImageDimensionsStore
import java.io.File
import java.time.Clock

fun inlineImageDimensionsStore(context: Context, clock: Clock = Clock.systemUTC()): InlineImageDimensionsStore =
    InlineImageDimensionsStore(File(context.filesDir, "InlineImageDimensions.json"), clock)
