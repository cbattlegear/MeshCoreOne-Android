// AndroidOnly: WP-101 Prizm Mobile Observer start/stop of its foreground service with typed refusals.
package com.meshcoreone.android.app.prizm

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent

class AndroidPrizmServiceControl(context: Context, private val log: (String) -> Unit = {}) : PrizmServiceControl {
    private val context = context.applicationContext
    private val intent get() = Intent(context, PrizmObserverService::class.java)

    override fun start(): Boolean = try {
        context.startForegroundService(intent)
        true
    } catch (refused: ForegroundServiceStartNotAllowedException) {
        log("background start not allowed"); false
    } catch (failure: SecurityException) {
        log("service start refused: ${failure.message}"); false
    } catch (failure: IllegalStateException) {
        log("service start failed: ${failure.message}"); false
    }

    override fun stop() { context.stopService(intent) }
}
