// PortedFrom: MC1/Services/DecodedPreviewCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Services/InlineImageCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Services/LinkPreviewCache.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.meshcoreone.android.core.services.content.DecodedPreviewCache
import com.meshcoreone.android.core.services.content.InlineImageCache
import com.meshcoreone.android.core.services.content.LinkPreviewCache
import java.util.concurrent.atomic.AtomicBoolean

class ContentMemoryPressure(
    context: Context,
    private val decoded: DecodedPreviewCache<*, *>,
    private val inline: InlineImageCache,
    private val previews: LinkPreviewCache,
) : ComponentCallbacks2, AutoCloseable {
    private val application = context.applicationContext
    private val closed = AtomicBoolean()

    init { application.registerComponentCallbacks(this) }

    override fun onConfigurationChanged(configuration: Configuration) = Unit

    override fun onLowMemory() = clear()

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) clear()
    }

    private fun clear() {
        if (closed.get()) return
        decoded.clear()
        inline.clearDecodedMirror()
        inline.clearRawMemory()
        previews.clearMemory()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) application.unregisterComponentCallbacks(this)
    }
}
