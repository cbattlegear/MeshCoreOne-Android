// AndroidOnly: WP-207 Eager partial-factory ownership and awaited single-flight service teardown.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.LifecycleStage
import com.meshcoreone.android.core.contracts.domain.MonitoringOptions
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.TeardownIssue
import com.meshcoreone.android.core.contracts.domain.TeardownReport
import com.meshcoreone.android.core.contracts.notifications.NotificationPostPermit
import com.meshcoreone.android.core.model.snapshot
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.future.await

class FactoryOwnership(
    val token: SessionToken,
    val notificationPostPermit: NotificationPostPermit = NotificationPostPermit(),
) {
    private data class Child(val stage: LifecycleStage, val close: suspend () -> Unit)
    private val lock = Any()
    private val children = mutableListOf<Child>()
    private var registered: RuntimeServices? = null
    private var closing: CompletableDeferred<TeardownReport>? = null

    fun own(stage: LifecycleStage, close: suspend () -> Unit) {
        synchronized(lock) {
            check(closing == null) { "Cannot allocate after factory ownership closed" }
            children += Child(stage, close)
        }
    }

    fun register(services: RuntimeServices) {
        synchronized(lock) {
            if (services.token != token || registered != null || closing != null) {
                throw ConnectionError.FactoryOwnershipViolation()
            }
            registered = services
        }
    }

    internal fun verifyReturned(services: RuntimeServices) {
        synchronized(lock) {
            if (registered !== services || services.token != token || closing != null) {
                throw ConnectionError.FactoryOwnershipViolation()
            }
        }
    }

    internal suspend fun close(): TeardownReport {
        val (receipt, claimed) = synchronized(lock) {
            notificationPostPermit.close()
            closing?.let { it to false } ?: CompletableDeferred<TeardownReport>().also { closing = it }.let { it to true }
        }
        if (!claimed) return withContext(NonCancellable) { receipt.await() }
        val issues = mutableListOf<TeardownIssue>()
        withContext(NonCancellable) {
            notificationPostPermit.close().await()
            val (services, allocated) = synchronized(lock) {
                registered to children.asReversed().toList().also { children.clear() }
            }
            if (services != null) {
                try { issues += services.tearDown().issues }
                catch (failure: Exception) { issues += TeardownIssue(LifecycleStage.STOP_SERVICES, failure) }
            }
            for (child in allocated) {
                try { child.close() }
                catch (failure: Exception) { issues += TeardownIssue(child.stage, failure) }
            }
        }
        return TeardownReport(issues.snapshot()).also { receipt.complete(it) }
    }
}

internal class OwnedRadioServices(
    val services: RuntimeServices,
    private val ownership: FactoryOwnership,
    context: CoroutineContext,
) {
    private val lock = Any()
    private val lifecycleJob = SupervisorJob()
    private val scope = CoroutineScope(context.minusKey(Job) + lifecycleJob)
    private var startup: Deferred<Unit>? = null
    private var teardown: Deferred<TeardownReport>? = null

    suspend fun startMonitoring(options: MonitoringOptions) {
        val work = synchronized(lock) {
            check(teardown == null) { "Service generation is closing" }
            startup ?: scope.async(start = CoroutineStart.LAZY) {
                services.startMonitoring(options)
            }.also { startup = it; it.start() }
        }
        work.await()
    }

    suspend fun close(): TeardownReport {
        val work = synchronized(lock) {
            teardown ?: scope.async(start = CoroutineStart.LAZY) {
                val starting = synchronized(lock) { startup }
                if (starting != null) {
                    starting.cancelAndJoin()
                    val failure = try { starting.await(); null }
                    catch (cancelled: CancellationException) { null }
                    catch (failure: Exception) { failure }
                    if (failure != null) {
                        val report = ownership.close()
                        return@async TeardownReport((listOf(TeardownIssue(LifecycleStage.START_MONITORING, failure)) + report.issues).snapshot())
                    }
                }
                ownership.close()
            }.also { teardown = it; it.start() }
        }
        return withContext(NonCancellable) {
            try { work.await() } finally { lifecycleJob.cancel() }
        }
    }
}
