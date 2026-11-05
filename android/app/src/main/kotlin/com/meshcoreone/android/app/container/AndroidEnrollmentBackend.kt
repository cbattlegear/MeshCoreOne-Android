// AndroidOnly: WP-303 Exact rendered foreground action mapped into real durable acknowledgment/witness.
package com.meshcoreone.android.app.container

import android.content.Context
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.EnrollmentRequest
import com.meshcoreone.android.core.datastore.enrollment.ForegroundAcknowledgmentStaging
import com.meshcoreone.android.core.datastore.enrollment.ForegroundAcknowledgmentWarning
import com.meshcoreone.android.core.datastore.enrollment.ForegroundWitnessAcknowledgmentVerifier
import com.meshcoreone.android.core.datastore.enrollment.NotificationEnrollmentWitness

/**
 * A real durable adapter, constructed only with a real journal implementation. It does not
 * supply a fallback journal or confer issuance, ingress, delivery or current-generation fencing.
 */
internal class AndroidEnrollmentBackend internal constructor(
    private val journal: NotificationEnrollmentCoordinator.Journal,
    private val createVerifier: (EnrollmentWarningCopy) -> ForegroundWitnessAcknowledgmentVerifier,
    private val createWitness: (ForegroundWitnessAcknowledgmentVerifier) -> NotificationEnrollmentWitness,
) : NotificationEnrollmentProducer.Backend {
    constructor(context: Context, journal: NotificationEnrollmentCoordinator.Journal) : this(
        journal,
        { copy ->
            ForegroundWitnessAcknowledgmentVerifier(
                context.applicationContext,
                warning(copy),
            )
        },
        { verifier -> NotificationEnrollmentWitness(context.applicationContext, verifier) },
    )
    private var current: Session? = null

    private class Session(
        val copy: EnrollmentWarningCopy,
        val verifier: ForegroundWitnessAcknowledgmentVerifier,
        val witness: ForegroundEnrollmentWitnessAdapter,
        val coordinator: NotificationEnrollmentCoordinator,
    )

    private fun session(copy: EnrollmentWarningCopy): Session {
        current?.takeIf { it.copy == copy }?.let { return it }
        val verifier = createVerifier(copy)
        val witness = ForegroundEnrollmentWitnessAdapter(createWitness(verifier))
        return Session(copy, verifier, witness, NotificationEnrollmentCoordinator(witness, journal))
            .also { current = it }
    }

    override suspend fun observe(copy: EnrollmentWarningCopy) = session(copy).witness.observe()

    override suspend fun resume(copy: EnrollmentWarningCopy) = session(copy).coordinator.resume()

    override fun present(
        request: EnrollmentRequest,
        copy: EnrollmentWarningCopy,
    ): NotificationEnrollmentProducer.Presentation {
        val owner = session(copy)
        val opaque = owner.verifier.present(request.toWitnessRequest())
        return object : NotificationEnrollmentProducer.Presentation {
            override val request = request
            override val copy = copy
            override fun retire() = owner.verifier.retire(opaque)
            override suspend fun invalidate() = owner.verifier.invalidate(opaque)
            override suspend fun stageExplicitAction() = when (owner.verifier.recordExplicitAction(opaque)) {
                ForegroundAcknowledgmentStaging.Staged -> NotificationEnrollmentProducer.Staging.STAGED
                ForegroundAcknowledgmentStaging.Required -> NotificationEnrollmentProducer.Staging.REQUIRED
                ForegroundAcknowledgmentStaging.Rejected -> NotificationEnrollmentProducer.Staging.REJECTED
                ForegroundAcknowledgmentStaging.AlreadyConsumed -> NotificationEnrollmentProducer.Staging.ALREADY_CONSUMED
                ForegroundAcknowledgmentStaging.Indeterminate -> NotificationEnrollmentProducer.Staging.INDETERMINATE
            }
            override suspend fun enroll() = owner.coordinator.enroll(request)
        }

        companion object {
            fun warning(copy: EnrollmentWarningCopy) = ForegroundAcknowledgmentWarning.fromRenderedCopy(
                "notification-enrollment-new-generation", 1, copy.canonical,
            )
        }
    }
}
