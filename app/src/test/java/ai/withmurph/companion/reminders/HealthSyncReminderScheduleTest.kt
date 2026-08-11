package ai.withmurph.companion.reminders

import ai.withmurph.companion.core.HealthSyncReminderDeadline
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.LocalState
import android.app.NotificationManager
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSyncReminderScheduleTest {
    @Test
    fun admissionUsesOnlyServerClockEvidenceThenConvertsRemainingTimeToElapsedRealtime() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(12))
        val observedAt = receiptAt.plus(Duration.ofHours(24))
        val localState = eligibleState(setupAt, receiptAt, observedAt)

        val admission = requireNotNull(activeReminderAdmission(localState))

        assertEquals(Duration.ofHours(48), admission.remaining)
        assertEquals(64, admission.basisToken.length)
        assertEquals(
            7_654L + Duration.ofHours(48).toMillis(),
            elapsedRealtimeTriggerAt(
                elapsedRealtimeMillis = 7_654L,
                remaining = admission.remaining,
            ),
        )
    }

    @Test
    fun elapsedRealtimeSchedulingAppliesMinimumDelayAndClampsOverflow() {
        assertEquals(
            901_000L,
            elapsedRealtimeTriggerAt(
                elapsedRealtimeMillis = 1_000L,
                remaining = Duration.ZERO,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            elapsedRealtimeTriggerAt(
                elapsedRealtimeMillis = Long.MAX_VALUE - 10L,
                remaining = Duration.ofHours(1),
            ),
        )
    }

    @Test
    fun foregroundProcessFenceRejectsDispatchedPostAndRescheduleActions() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(12))
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = receiptAt,
            observedAt = receiptAt.plus(Duration.ofHours(72)),
        )
        val scheduledBasisToken = requireNotNull(activeReminderAdmission(localState)).basisToken

        HealthSyncReminderProcessState.didEnterForeground()
        try {
            assertEquals(
                HealthSyncReminderDeliveryAction.Cancel,
                reminderDeliveryAction(
                    localState = localState,
                    scheduledBasisToken = scheduledBasisToken,
                    appInForeground = HealthSyncReminderProcessState.isForeground,
                ),
            )

            localState.lastKnownDataReceivedAt = InstantValue(
                receiptAt.plus(Duration.ofHours(1)).toEpochMilli(),
            )
            assertEquals(
                HealthSyncReminderDeliveryAction.Cancel,
                reminderDeliveryAction(
                    localState = localState,
                    scheduledBasisToken = scheduledBasisToken,
                    appInForeground = HealthSyncReminderProcessState.isForeground,
                ),
            )
        } finally {
            HealthSyncReminderProcessState.didEnterBackground()
        }

        assertEquals(
            HealthSyncReminderDeliveryAction.Reschedule,
            reminderDeliveryAction(
                localState = localState,
                scheduledBasisToken = scheduledBasisToken,
                appInForeground = HealthSyncReminderProcessState.isForeground,
            ),
        )
    }

    @Test
    fun freshOverdueEvidenceCreatesTheMinimumInitialDeadline() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(1))
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = receiptAt,
            observedAt = receiptAt.plus(Duration.ofHours(96)),
        )
        localState.reminderEnabled = false
        val admission = requireNotNull(reminderEvidenceAdmission(localState))

        val deadline = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 7,
            elapsedRealtimeMillis = 1_000L,
            remaining = admission.remaining,
            existing = null,
        )

        assertEquals(Duration.ZERO, admission.remaining)
        assertEquals(
            1_000L + Duration.ofMinutes(15).toMillis(),
            deadline.triggerElapsedRealtimeMillis,
        )
    }

    @Test
    fun setupAuthorizationAdmissionStartsFromTheFreshSetupObservation() {
        val requestedAt = Instant.parse("2026-08-01T00:00:00Z")
        val localState = eligibleState(
            setupAt = requestedAt.minus(Duration.ofDays(7)),
            receiptAt = requestedAt.minus(Duration.ofDays(1)),
            observedAt = requestedAt,
        )

        val admission = requireNotNull(
            setupAuthorizationReminderAdmission(
                localState = localState,
                memberKey = "member-a",
                requestedAt = InstantValue(requestedAt.toEpochMilli()),
            ),
        )

        assertEquals(Duration.ofHours(72), admission.remaining)
        assertEquals(
            reminderBasisToken(
                installationId = localState.installationId,
                memberKey = "member-a",
                setupAtEpochMilliseconds = requestedAt.toEpochMilli(),
                receiptAtEpochMilliseconds = null,
            ),
            admission.basisToken,
        )
    }

    @Test
    fun offlineForegroundCycleDoesNotMoveTheExistingDeadlineLater() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(24))
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = receiptAt,
            observedAt = receiptAt.plus(Duration.ofHours(48)),
        )
        val admission = requireNotNull(activeReminderAdmission(localState))
        val remaining = Duration.ofHours(24)
        val originalDeadline = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 7,
            elapsedRealtimeMillis = 0L,
            remaining = remaining,
            existing = null,
        )

        val replacementDeadline = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 7,
            elapsedRealtimeMillis = Duration.ofHours(20).toMillis(),
            remaining = remaining,
            existing = originalDeadline,
        )
        val repeatedDeadline = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 7,
            elapsedRealtimeMillis = Duration.ofHours(23).toMillis(),
            remaining = remaining,
            existing = replacementDeadline,
        )

        assertEquals(originalDeadline, replacementDeadline)
        assertEquals(originalDeadline, repeatedDeadline)
    }

    @Test
    fun expiredDeadlineUsesTheMinimumReentryDelayInsteadOfSchedulingInThePast() {
        val basisToken = "a".repeat(64)
        val elapsedRealtimeMillis = Duration.ofHours(25).toMillis()
        val existing = HealthSyncReminderDeadline(
            basisToken = basisToken,
            bootCount = 7,
            triggerElapsedRealtimeMillis = Duration.ofHours(24).toMillis(),
        )

        val retained = retainedHealthSyncReminderDeadline(
            basisToken = basisToken,
            bootCount = 7,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            remaining = Duration.ofHours(24),
            existing = existing,
        )

        assertEquals(
            elapsedRealtimeMillis + Duration.ofMinutes(15).toMillis(),
            retained.triggerElapsedRealtimeMillis,
        )
    }

    @Test
    fun rebootWithCachedEvidenceUsesTheMinimumReentryDelay() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(24))
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = receiptAt,
            observedAt = receiptAt.plus(Duration.ofHours(48)),
        )
        val admission = requireNotNull(activeReminderAdmission(localState))
        val existing = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 7,
            elapsedRealtimeMillis = Duration.ofHours(100).toMillis(),
            remaining = admission.remaining,
            existing = null,
        )

        val afterReboot = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 8,
            elapsedRealtimeMillis = Duration.ofHours(1).toMillis(),
            remaining = admission.remaining,
            existing = existing,
        )
        assertEquals(
            Duration.ofHours(1).plus(Duration.ofMinutes(15)).toMillis(),
            afterReboot.triggerElapsedRealtimeMillis,
        )

        val afterAnotherReboot = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 9,
            elapsedRealtimeMillis = Duration.ofMinutes(10).toMillis(),
            remaining = admission.remaining,
            existing = afterReboot,
        )
        assertEquals(
            Duration.ofMinutes(25).toMillis(),
            afterAnotherReboot.triggerElapsedRealtimeMillis,
        )
    }

    @Test
    fun freshBackendEvidenceMayReanchorTheDeadlineAfterReboot() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(24))
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = receiptAt,
            observedAt = receiptAt.plus(Duration.ofHours(48)),
        )
        val admission = requireNotNull(activeReminderAdmission(localState))
        val existing = HealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 7,
            triggerElapsedRealtimeMillis = Duration.ofHours(100).toMillis(),
        )

        val refreshed = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 8,
            elapsedRealtimeMillis = Duration.ofHours(1).toMillis(),
            remaining = admission.remaining,
            existing = existing,
            mayReanchorAfterBoot = true,
        )

        assertEquals(
            Duration.ofHours(25).toMillis(),
            refreshed.triggerElapsedRealtimeMillis,
        )
    }

    @Test
    fun newReceiptExplicitlyReanchorsTheDeadline() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(24))
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = receiptAt,
            observedAt = receiptAt.plus(Duration.ofHours(48)),
        )
        val admission = requireNotNull(activeReminderAdmission(localState))
        val existing = retainedHealthSyncReminderDeadline(
            basisToken = admission.basisToken,
            bootCount = 8,
            elapsedRealtimeMillis = Duration.ofHours(1).toMillis(),
            remaining = admission.remaining,
            existing = null,
        )
        localState.lastKnownDataReceivedAt = InstantValue(
            receiptAt.plus(Duration.ofHours(1)).toEpochMilli(),
        )
        val newReceiptAdmission = requireNotNull(activeReminderAdmission(localState))
        val afterNewReceipt = retainedHealthSyncReminderDeadline(
            basisToken = newReceiptAdmission.basisToken,
            bootCount = 8,
            elapsedRealtimeMillis = Duration.ofHours(2).toMillis(),
            remaining = newReceiptAdmission.remaining,
            existing = existing,
        )
        assertFalse(afterNewReceipt.basisToken == existing.basisToken)
        assertEquals(
            Duration.ofHours(27).toMillis(),
            afterNewReceipt.triggerElapsedRealtimeMillis,
        )
    }

    @Test
    fun newerServerObservationKeepsFenceWhileNewReceiptChangesIt() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val receiptAt = setupAt.plus(Duration.ofHours(12))
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = receiptAt,
            observedAt = receiptAt.plus(Duration.ofHours(24)),
        )
        val scheduledBasisToken = requireNotNull(activeReminderAdmission(localState)).basisToken

        localState.lastKnownStatusObservedAt = InstantValue(
            receiptAt.plus(Duration.ofHours(25)).toEpochMilli(),
        )
        assertTrue(reminderFenceMatches(localState, scheduledBasisToken))
        assertEquals(
            HealthSyncReminderDeliveryAction.Post,
            reminderDeliveryAction(localState, scheduledBasisToken),
        )

        localState.lastKnownDataReceivedAt = InstantValue(
            receiptAt.plus(Duration.ofHours(1)).toEpochMilli(),
        )
        assertFalse(reminderFenceMatches(localState, scheduledBasisToken))
        assertEquals(
            HealthSyncReminderDeliveryAction.Reschedule,
            reminderDeliveryAction(localState, scheduledBasisToken),
        )
    }

    @Test
    fun fenceRejectsOptOutSignOutAndMemberSwitch() {
        val setupAt = Instant.parse("2026-08-01T00:00:00Z")
        val localState = eligibleState(
            setupAt = setupAt,
            receiptAt = null,
            observedAt = setupAt.plus(Duration.ofHours(24)),
        )
        val scheduledBasisToken = requireNotNull(activeReminderAdmission(localState)).basisToken

        localState.reminderEnabled = false
        assertFalse(reminderFenceMatches(localState, scheduledBasisToken))
        assertEquals(
            HealthSyncReminderDeliveryAction.Cancel,
            reminderDeliveryAction(localState, scheduledBasisToken),
        )

        localState.reminderEnabled = true
        localState.pendingSignOut = true
        assertFalse(reminderFenceMatches(localState, scheduledBasisToken))

        localState.pendingSignOut = false
        localState.memberKey = "member-b"
        localState.preferenceOwner = "member-b"
        assertFalse(reminderFenceMatches(localState, scheduledBasisToken))
    }

    @Test
    fun missingServerObservationCannotAdmitAnAlarm() {
        val localState = eligibleState(
            setupAt = Instant.parse("2026-08-01T00:00:00Z"),
            receiptAt = null,
            observedAt = Instant.parse("2026-08-02T00:00:00Z"),
        )
        localState.lastKnownStatusObservedAt = null

        assertNull(activeReminderAdmission(localState))
    }

    @Test
    fun channelSpecificBlockIsNotReportedAsAllowed() {
        assertTrue(notificationChannelAllowsReminders(channelImportance = null))
        assertTrue(
            notificationChannelAllowsReminders(NotificationManager.IMPORTANCE_DEFAULT),
        )
        assertFalse(notificationChannelAllowsReminders(NotificationManager.IMPORTANCE_NONE))
    }

    private fun eligibleState(
        setupAt: Instant,
        receiptAt: Instant?,
        observedAt: Instant,
    ) = FakeLocalState().apply {
        memberKey = "member-a"
        preferenceOwner = "member-a"
        reminderEnabled = true
        healthAccessRequestedAt = InstantValue(setupAt.toEpochMilli())
        lastKnownDataReceivedAt = receiptAt?.let { InstantValue(it.toEpochMilli()) }
        lastKnownStatusObservedAt = InstantValue(observedAt.toEpochMilli())
    }

    private class FakeLocalState : LocalState {
        override val installationId = "installation"
        override var memberKey: String? = null
        override var initialSetupStep: InitialSetupStep? = null
        override var healthAccessRequestedAt: InstantValue? = null
        override var healthReceiptBaselineAt: InstantValue? = null
        override var lastKnownDataReceivedAt: InstantValue? = null
        override var lastKnownStatusObservedAt: InstantValue? = null
        override var healthReconnectRequired = false
        override val signOutPending: Boolean
            get() = pendingSignOut
        override val pendingPrivySignOutMemberKey: String? = null

        var preferenceOwner: String? = null
        var reminderEnabled = false
        var pendingSignOut = false

        override fun isHealthSyncReminderEnabled(memberKey: String): Boolean =
            !pendingSignOut && reminderEnabled && memberKey == preferenceOwner

        override fun setHealthSyncReminderEnabled(
            memberKey: String,
            enabled: Boolean,
            initialDeadline: HealthSyncReminderDeadline?,
        ): Boolean {
            if (pendingSignOut || memberKey != this.memberKey) return false
            preferenceOwner = memberKey
            reminderEnabled = enabled
            return true
        }

        override fun advanceInitialSetupStep(
            expected: InitialSetupStep,
            next: InitialSetupStep,
            abandonPendingAddressBookReplacement: Boolean,
        ): Boolean {
            if (initialSetupStep != expected) return false
            initialSetupStep = next
            return true
        }

        override fun completeHealthSetupAuthorization(
            requestedAt: InstantValue,
            receiptBaselineAt: InstantValue?,
            statusObservedAt: InstantValue,
            reminderDeadline: HealthSyncReminderDeadline?,
            completesInitialSetup: Boolean,
        ): Boolean {
            healthAccessRequestedAt = requestedAt
            healthReceiptBaselineAt = receiptBaselineAt
            lastKnownStatusObservedAt = statusObservedAt
            return true
        }

        override fun requireHealthReconnect(): Boolean {
            healthReconnectRequired = true
            return true
        }

        override fun revokeHealthSetupAuthorization(): Boolean {
            healthAccessRequestedAt = null
            return true
        }

        override fun beginSignOut(
            expectedMemberKey: String?,
            privySignOutMemberKey: String?,
            preserveMemberState: Boolean,
        ): Boolean {
            if (memberKey != expectedMemberKey) return false
            pendingSignOut = true
            return true
        }

        override fun completeSignOut(expectedMemberKey: String?): Boolean {
            if (memberKey != expectedMemberKey) return false
            pendingSignOut = false
            clearMemberScopedState()
            return true
        }

        override fun clearMemberScopedState() {
            memberKey = null
            initialSetupStep = null
            healthAccessRequestedAt = null
            healthReceiptBaselineAt = null
            lastKnownDataReceivedAt = null
            lastKnownStatusObservedAt = null
            healthReconnectRequired = false
            preferenceOwner = null
            reminderEnabled = false
        }
    }
}
