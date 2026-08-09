package ai.withmurph.companion.storage

import android.content.SharedPreferences
import ai.withmurph.companion.core.AddressBookMutation
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.InitialSetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesLocalStateTest {
    @Test
    fun initialSetupStepUsesStableValuesAndSurvivesReconstruction() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val expectedValues = listOf(
            InitialSetupStep.HealthConnect to "health_connect",
            InitialSetupStep.FriendlyNames to "friendly_names",
            InitialSetupStep.Complete to "complete",
        )

        expectedValues.forEach { (step, wireValue) ->
            assertEquals(wireValue, step.wireValue)
            state.initialSetupStep = step
            assertEquals(wireValue, preferences.getAll()["initial_setup_step"])
        }

        expectedValues.forEach { (step, wireValue) ->
            preferences.edit().putString("initial_setup_step", wireValue).commit()
            assertEquals(
                step,
                SharedPreferencesLocalState(preferences.recreated()).initialSetupStep,
            )
        }
    }

    @Test
    fun malformedInitialSetupStepIsTreatedAsMissing() {
        val preferences = FaultInjectedPreferences()
        preferences.edit().putString("initial_setup_step", "unknown_step").commit()

        assertNull(SharedPreferencesLocalState(preferences).initialSetupStep)
    }

    @Test
    fun memberScopedClearRemovesInitialSetupStep() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val installationId = state.installationId
        state.memberKey = "member-key"
        state.initialSetupStep = InitialSetupStep.FriendlyNames

        state.clearMemberScopedState()

        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertNull(reconstructed.memberKey)
        assertNull(reconstructed.initialSetupStep)
        assertEquals(installationId, reconstructed.installationId)
    }

    @Test
    fun healthSetupAuthorizationCommitsOneCompleteRestartSnapshot() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        state.lastKnownDataReceivedAt = InstantValue(50)
        state.healthReconnectRequired = true
        state.initialSetupStep = InitialSetupStep.HealthConnect

        assertTrue(
            state.completeHealthSetupAuthorization(
                requestedAt = InstantValue(100),
                receiptBaselineAt = InstantValue(75),
                statusObservedAt = InstantValue(100),
                completesInitialSetup = true,
            ),
        )

        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(InstantValue(100), reconstructed.healthAccessRequestedAt)
        assertEquals(InstantValue(75), reconstructed.healthReceiptBaselineAt)
        assertNull(reconstructed.lastKnownDataReceivedAt)
        assertEquals(InstantValue(100), reconstructed.lastKnownStatusObservedAt)
        assertFalse(reconstructed.healthReconnectRequired)
        assertEquals(InitialSetupStep.FriendlyNames, reconstructed.initialSetupStep)
    }

    @Test
    fun failedHealthSetupAuthorizationRestoresThePriorRestartSnapshot() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val requestedAt = InstantValue(200)
        val baselineAt = InstantValue(175)
        val receivedAt = InstantValue(190)
        val observedAt = InstantValue(200)
        state.healthAccessRequestedAt = requestedAt
        state.healthReceiptBaselineAt = baselineAt
        state.lastKnownDataReceivedAt = receivedAt
        state.lastKnownStatusObservedAt = observedAt
        state.healthReconnectRequired = true
        state.initialSetupStep = InitialSetupStep.HealthConnect
        preferences.failCommits = true

        assertFalse(
            state.completeHealthSetupAuthorization(
                requestedAt = InstantValue(300),
                receiptBaselineAt = null,
                statusObservedAt = InstantValue(300),
                completesInitialSetup = true,
            ),
        )

        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(baselineAt, state.healthReceiptBaselineAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        assertEquals(observedAt, state.lastKnownStatusObservedAt)
        assertTrue(state.healthReconnectRequired)
        assertEquals(InitialSetupStep.HealthConnect, state.initialSetupStep)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(baselineAt, reconstructed.healthReceiptBaselineAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
        assertEquals(observedAt, reconstructed.lastKnownStatusObservedAt)
        assertTrue(reconstructed.healthReconnectRequired)
        assertEquals(InitialSetupStep.HealthConnect, reconstructed.initialSetupStep)
    }

    @Test
    fun reconnectRequirementCommitsOneFailClosedRestartSnapshot() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        state.healthAccessRequestedAt = InstantValue(100)
        state.healthReceiptBaselineAt = InstantValue(75)
        state.lastKnownDataReceivedAt = InstantValue(90)
        state.lastKnownStatusObservedAt = InstantValue(100)

        assertTrue(state.requireHealthReconnect())

        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertNull(reconstructed.healthAccessRequestedAt)
        assertNull(reconstructed.healthReceiptBaselineAt)
        assertNull(reconstructed.lastKnownDataReceivedAt)
        assertNull(reconstructed.lastKnownStatusObservedAt)
        assertTrue(reconstructed.healthReconnectRequired)
    }

    @Test
    fun failedReconnectRequirementRestoresThePriorRestartSnapshot() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val requestedAt = InstantValue(200)
        val baselineAt = InstantValue(175)
        val receivedAt = InstantValue(190)
        val observedAt = InstantValue(200)
        state.healthAccessRequestedAt = requestedAt
        state.healthReceiptBaselineAt = baselineAt
        state.lastKnownDataReceivedAt = receivedAt
        state.lastKnownStatusObservedAt = observedAt
        preferences.failCommits = true

        assertFalse(state.requireHealthReconnect())

        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(baselineAt, state.healthReceiptBaselineAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        assertEquals(observedAt, state.lastKnownStatusObservedAt)
        assertFalse(state.healthReconnectRequired)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(baselineAt, reconstructed.healthReceiptBaselineAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
        assertEquals(observedAt, reconstructed.lastKnownStatusObservedAt)
        assertFalse(reconstructed.healthReconnectRequired)
    }

    @Test
    fun failedHealthRevocationRestoresLiveAndPersistedAuthorization() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val requestedAt = InstantValue(100)
        val baselineAt = InstantValue(150)
        val receivedAt = InstantValue(200)
        val observedAt = InstantValue(250)
        state.healthAccessRequestedAt = requestedAt
        state.healthReceiptBaselineAt = baselineAt
        state.lastKnownDataReceivedAt = receivedAt
        state.lastKnownStatusObservedAt = observedAt
        state.healthReconnectRequired = true
        preferences.failCommits = true

        assertFalse(state.revokeHealthSetupAuthorization())

        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(baselineAt, state.healthReceiptBaselineAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        assertEquals(observedAt, state.lastKnownStatusObservedAt)
        assertTrue(state.healthReconnectRequired)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(baselineAt, reconstructed.healthReceiptBaselineAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
        assertEquals(observedAt, reconstructed.lastKnownStatusObservedAt)
        assertTrue(reconstructed.healthReconnectRequired)
    }

    @Test
    fun failedSignOutBoundaryRestoresLiveAndPersistedAuthorization() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val requestedAt = InstantValue(300)
        val baselineAt = InstantValue(350)
        val receivedAt = InstantValue(400)
        val observedAt = InstantValue(450)
        state.healthAccessRequestedAt = requestedAt
        state.healthReceiptBaselineAt = baselineAt
        state.lastKnownDataReceivedAt = receivedAt
        state.lastKnownStatusObservedAt = observedAt
        state.healthReconnectRequired = true
        state.initialSetupStep = InitialSetupStep.FriendlyNames
        preferences.failCommits = true

        assertFalse(state.beginSignOut(state.memberKey))

        assertFalse(state.signOutPending)
        assertNull(state.pendingPrivySignOutMemberKey)
        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(baselineAt, state.healthReceiptBaselineAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        assertEquals(observedAt, state.lastKnownStatusObservedAt)
        assertTrue(state.healthReconnectRequired)
        assertEquals(InitialSetupStep.FriendlyNames, state.initialSetupStep)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertFalse(reconstructed.signOutPending)
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(baselineAt, reconstructed.healthReceiptBaselineAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
        assertEquals(observedAt, reconstructed.lastKnownStatusObservedAt)
        assertTrue(reconstructed.healthReconnectRequired)
        assertEquals(InitialSetupStep.FriendlyNames, reconstructed.initialSetupStep)
    }

    @Test
    fun failedSignOutBoundaryPreservesExistingTombstone() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        assertTrue(
            state.beginSignOut(
                expectedMemberKey = state.memberKey,
                privySignOutMemberKey = "member-a",
            ),
        )
        val requestedAt = InstantValue(500)
        val baselineAt = InstantValue(550)
        val receivedAt = InstantValue(600)
        val observedAt = InstantValue(650)
        state.healthAccessRequestedAt = requestedAt
        state.healthReceiptBaselineAt = baselineAt
        state.lastKnownDataReceivedAt = receivedAt
        state.lastKnownStatusObservedAt = observedAt
        state.healthReconnectRequired = true
        preferences.failCommits = true

        assertFalse(
            state.beginSignOut(
                expectedMemberKey = state.memberKey,
                privySignOutMemberKey = "member-b",
            ),
        )

        assertTrue(state.signOutPending)
        assertEquals("member-a", state.pendingPrivySignOutMemberKey)
        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(baselineAt, state.healthReceiptBaselineAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        assertEquals(observedAt, state.lastKnownStatusObservedAt)
        assertTrue(state.healthReconnectRequired)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertTrue(reconstructed.signOutPending)
        assertEquals("member-a", reconstructed.pendingPrivySignOutMemberKey)
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(baselineAt, reconstructed.healthReceiptBaselineAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
        assertEquals(observedAt, reconstructed.lastKnownStatusObservedAt)
        assertTrue(reconstructed.healthReconnectRequired)
    }

    @Test
    fun signOutBoundaryRejectsAStaleMemberOwnerWithoutMutation() {
        val state = SharedPreferencesLocalState(FaultInjectedPreferences())
        val requestedAt = InstantValue(700)
        state.memberKey = "member-a"
        state.healthAccessRequestedAt = requestedAt
        state.initialSetupStep = InitialSetupStep.FriendlyNames

        assertFalse(state.beginSignOut("member-b"))

        assertEquals("member-a", state.memberKey)
        assertFalse(state.signOutPending)
        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(InitialSetupStep.FriendlyNames, state.initialSetupStep)
    }

    @Test
    fun signOutCompletionCannotClearANewerMemberOwner() {
        val state = SharedPreferencesLocalState(FaultInjectedPreferences())
        state.memberKey = "member-a"
        assertTrue(state.beginSignOut("member-a"))
        state.memberKey = "member-b"

        assertFalse(state.completeSignOut("member-a"))

        assertEquals("member-b", state.memberKey)
        assertTrue(state.signOutPending)
    }

    @Test
    fun failedSignOutCompletionKeepsTheExpectedOwnerFencedForReconstruction() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        state.memberKey = "member-a"
        assertTrue(
            state.beginSignOut(
                expectedMemberKey = "member-a",
                privySignOutMemberKey = "member-a",
            ),
        )
        preferences.failCommits = true

        assertFalse(state.completeSignOut("member-a"))

        assertEquals("member-a", state.memberKey)
        assertTrue(state.signOutPending)
        assertEquals("member-a", state.pendingPrivySignOutMemberKey)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals("member-a", reconstructed.memberKey)
        assertTrue(reconstructed.signOutPending)
        assertEquals("member-a", reconstructed.pendingPrivySignOutMemberKey)
    }

    @Test
    fun explicitSignOutPersistsAnUnboundPrivyTargetUntilCompletion() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)

        assertTrue(
            state.beginSignOut(
                expectedMemberKey = null,
                privySignOutMemberKey = "member-a",
            ),
        )

        assertTrue(state.signOutPending)
        assertNull(state.memberKey)
        assertEquals("member-a", state.pendingPrivySignOutMemberKey)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertTrue(reconstructed.signOutPending)
        assertEquals("member-a", reconstructed.pendingPrivySignOutMemberKey)

        assertTrue(reconstructed.completeSignOut(null))
        assertFalse(reconstructed.signOutPending)
        assertNull(reconstructed.pendingPrivySignOutMemberKey)
    }

    @Test
    fun persistsOnlyAddressBookRevisionAndReplayMetadata() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val replacement = AddressBookMutation(4, MUTATION_ONE)

        assertTrue(state.recordDisabledAddressBookRevision(4))
        assertTrue(state.beginAddressBookReplacement(replacement))

        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(4, reconstructed.addressBookRevision)
        assertEquals(replacement, reconstructed.pendingAddressBookReplacement)
        assertNull(reconstructed.pendingAddressBookDeletion)
        assertTrue(
            reconstructed.completeAddressBookReplacement(
                mutationId = MUTATION_ONE,
                revision = 5,
            ),
        )
        assertEquals(5, reconstructed.addressBookRevision)
        assertNull(reconstructed.pendingAddressBookReplacement)
        assertNull(reconstructed.pendingAddressBookDeletion)

        val persistedText = preferences.getAll().entries.joinToString()
        assertFalse(persistedText.contains("+12125550123"))
        assertFalse(persistedText.contains("Anna"))
    }

    @Test
    fun pendingDeletionSurvivesReconstructionAndSupersedesReplacement() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val replacement = AddressBookMutation(7, MUTATION_ONE)
        val deletion = AddressBookMutation(7, MUTATION_TWO)
        assertTrue(state.recordAddressBookRevision(7))
        assertTrue(state.beginAddressBookReplacement(replacement))

        assertTrue(state.beginAddressBookDeletion(deletion))

        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertNull(reconstructed.pendingAddressBookReplacement)
        assertEquals(deletion, reconstructed.pendingAddressBookDeletion)
        assertTrue(reconstructed.completeAddressBookDeletion(MUTATION_TWO, 8))
        assertEquals(8, reconstructed.addressBookRevision)
        assertNull(reconstructed.pendingAddressBookDeletion)
    }

    @Test
    fun disabledStatusCanPreserveAnInterruptedReplayMarkerThenClearItAuthoritatively() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val replacement = AddressBookMutation(4, MUTATION_ONE)
        assertTrue(state.recordDisabledAddressBookRevision(4))
        assertTrue(state.beginAddressBookReplacement(replacement))

        assertTrue(state.recordAddressBookRevision(5))
        assertEquals(5, state.addressBookRevision)
        assertEquals(replacement, state.pendingAddressBookReplacement)

        assertTrue(state.recordDisabledAddressBookRevision(6))
        assertEquals(6, state.addressBookRevision)
        assertNull(state.pendingAddressBookReplacement)
        assertNull(state.pendingAddressBookDeletion)
    }

    @Test
    fun malformedOrPartialReplayMetadataFailsClosed() {
        val preferences = FaultInjectedPreferences()
        preferences.edit()
            .putInt("address_book_revision", -1)
            .putInt("address_book_replacement_base_revision", 2)
            .putString("address_book_replacement_mutation_id", "not-a-uuid")
            .putString("address_book_deletion_mutation_id", MUTATION_TWO)
            .commit()

        val state = SharedPreferencesLocalState(preferences)

        assertNull(state.addressBookRevision)
        assertNull(state.pendingAddressBookReplacement)
        assertNull(state.pendingAddressBookDeletion)
    }

    @Test
    fun mutationCompletionRequiresTheMatchingIdAndANewerRevision() {
        val state = SharedPreferencesLocalState(FaultInjectedPreferences())
        val replacement = AddressBookMutation(8, MUTATION_ONE)
        assertTrue(state.beginAddressBookReplacement(replacement))

        assertFalse(state.completeAddressBookReplacement(MUTATION_TWO, 9))
        assertFalse(state.completeAddressBookReplacement(MUTATION_ONE, 8))
        assertEquals(replacement, state.pendingAddressBookReplacement)
        assertTrue(state.completeAddressBookReplacement(MUTATION_ONE, 10))
        assertEquals(10, state.addressBookRevision)
        assertNull(state.pendingAddressBookReplacement)
    }

    @Test
    fun friendlyNamesDeferralAtomicallyClearsASettledReplacementRetry() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val replacement = AddressBookMutation(8, MUTATION_ONE)
        state.initialSetupStep = InitialSetupStep.FriendlyNames
        assertTrue(state.beginAddressBookReplacement(replacement))
        preferences.failCommits = true

        assertFalse(
            state.advanceInitialSetupStep(
                expected = InitialSetupStep.FriendlyNames,
                next = InitialSetupStep.Complete,
                abandonPendingAddressBookReplacement = true,
            ),
        )
        assertEquals(InitialSetupStep.FriendlyNames, state.initialSetupStep)
        assertEquals(replacement, state.pendingAddressBookReplacement)

        preferences.failCommits = false
        assertTrue(
            state.advanceInitialSetupStep(
                expected = InitialSetupStep.FriendlyNames,
                next = InitialSetupStep.Complete,
                abandonPendingAddressBookReplacement = true,
            ),
        )
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(InitialSetupStep.Complete, reconstructed.initialSetupStep)
        assertNull(reconstructed.pendingAddressBookReplacement)
    }

    @Test
    fun initialAddressBookSuccessCommitsRevisionAndSetupCompletionTogether() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val replacement = AddressBookMutation(2, MUTATION_ONE)
        state.initialSetupStep = InitialSetupStep.FriendlyNames
        assertTrue(state.recordAddressBookRevision(2))
        assertTrue(state.beginAddressBookReplacement(replacement))

        assertTrue(
            state.completeAddressBookReplacement(
                mutationId = MUTATION_ONE,
                revision = 3,
                completesInitialSetup = true,
            ),
        )

        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(3, reconstructed.addressBookRevision)
        assertNull(reconstructed.pendingAddressBookReplacement)
        assertEquals(InitialSetupStep.Complete, reconstructed.initialSetupStep)
    }

    @Test
    fun failedInitialAddressBookCompletionRestoresTheWholeRestartSnapshot() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val replacement = AddressBookMutation(2, MUTATION_ONE)
        state.initialSetupStep = InitialSetupStep.FriendlyNames
        assertTrue(state.recordAddressBookRevision(2))
        assertTrue(state.beginAddressBookReplacement(replacement))
        preferences.failCommits = true

        assertFalse(
            state.completeAddressBookReplacement(
                mutationId = MUTATION_ONE,
                revision = 3,
                completesInitialSetup = true,
            ),
        )

        assertEquals(2, state.addressBookRevision)
        assertEquals(replacement, state.pendingAddressBookReplacement)
        assertEquals(InitialSetupStep.FriendlyNames, state.initialSetupStep)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(2, reconstructed.addressBookRevision)
        assertEquals(replacement, reconstructed.pendingAddressBookReplacement)
        assertEquals(InitialSetupStep.FriendlyNames, reconstructed.initialSetupStep)
    }

    @Test
    fun failedAddressBookCommitRestoresLiveAndPersistedMetadata() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val replacement = AddressBookMutation(2, MUTATION_ONE)
        assertTrue(state.recordAddressBookRevision(2))
        assertTrue(state.beginAddressBookReplacement(replacement))
        preferences.failCommits = true

        assertFalse(state.completeAddressBookReplacement(MUTATION_ONE, 3))

        assertEquals(2, state.addressBookRevision)
        assertEquals(replacement, state.pendingAddressBookReplacement)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(2, reconstructed.addressBookRevision)
        assertEquals(replacement, reconstructed.pendingAddressBookReplacement)
    }

    @Test
    fun signOutBoundaryClearsAddressBookMetadataAndRestoresItOnCommitFailure() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val deletion = AddressBookMutation(11, MUTATION_TWO)
        assertTrue(state.recordAddressBookRevision(11))
        assertTrue(state.beginAddressBookDeletion(deletion))
        state.initialSetupStep = InitialSetupStep.FriendlyNames
        preferences.failCommits = true

        assertFalse(state.beginSignOut(state.memberKey))
        assertEquals(11, state.addressBookRevision)
        assertEquals(deletion, state.pendingAddressBookDeletion)
        assertEquals(InitialSetupStep.FriendlyNames, state.initialSetupStep)

        preferences.failCommits = false
        assertTrue(state.beginSignOut(state.memberKey))
        assertTrue(state.signOutPending)
        assertNull(state.addressBookRevision)
        assertNull(state.pendingAddressBookReplacement)
        assertNull(state.pendingAddressBookDeletion)
        assertNull(state.initialSetupStep)
    }

    @Test
    fun automaticMemberResetFencesRestorationButPreservesMetadataUntilCompletion() {
        val state = SharedPreferencesLocalState(FaultInjectedPreferences())
        val replacement = AddressBookMutation(12, MUTATION_ONE)
        state.memberKey = "member-a"
        state.healthAccessRequestedAt = InstantValue(800)
        state.initialSetupStep = InitialSetupStep.FriendlyNames
        assertTrue(state.recordAddressBookRevision(12))
        assertTrue(state.beginAddressBookReplacement(replacement))

        assertTrue(
            state.beginSignOut(
                expectedMemberKey = "member-a",
                preserveMemberState = true,
            ),
        )

        assertTrue(state.signOutPending)
        assertNull(state.healthAccessRequestedAt)
        assertEquals(InitialSetupStep.FriendlyNames, state.initialSetupStep)
        assertEquals(12, state.addressBookRevision)
        assertEquals(replacement, state.pendingAddressBookReplacement)

        assertTrue(state.completeSignOut("member-a"))
        assertFalse(state.signOutPending)
        assertNull(state.memberKey)
        assertNull(state.initialSetupStep)
        assertNull(state.addressBookRevision)
        assertNull(state.pendingAddressBookReplacement)
    }

    private class FaultInjectedPreferences(
        private val persistedValues: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        private val liveValues = persistedValues.toMutableMap()
        var failCommits = false

        fun recreated() = FaultInjectedPreferences(persistedValues)

        override fun getAll(): Map<String, *> = liveValues.toMap()

        override fun getString(key: String, defValue: String?): String? =
            liveValues[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            liveValues[key] as? Set<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int =
            liveValues[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long =
            liveValues[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            liveValues[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            liveValues[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = liveValues.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearsValues = false

            override fun putString(key: String, value: String?) = update(key, value)

            override fun putStringSet(key: String, values: Set<String>?) =
                update(key, values?.toSet())

            override fun putInt(key: String, value: Int) = update(key, value)

            override fun putLong(key: String, value: Long) = update(key, value)

            override fun putFloat(key: String, value: Float) = update(key, value)

            override fun putBoolean(key: String, value: Boolean) = update(key, value)

            override fun remove(key: String): SharedPreferences.Editor = apply {
                updates.remove(key)
                removals += key
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearsValues = true
                updates.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                applyToLiveValues()
                if (failCommits) return false
                persistLiveValues()
                return true
            }

            override fun apply() {
                applyToLiveValues()
                persistLiveValues()
            }

            private fun update(key: String, value: Any?): SharedPreferences.Editor = apply {
                removals.remove(key)
                updates[key] = value
            }

            private fun applyToLiveValues() {
                if (clearsValues) liveValues.clear()
                removals.forEach(liveValues::remove)
                updates.forEach { (key, value) ->
                    if (value == null) liveValues.remove(key) else liveValues[key] = value
                }
            }

            private fun persistLiveValues() {
                persistedValues.clear()
                persistedValues.putAll(liveValues)
            }
        }
    }

    private companion object {
        const val MUTATION_ONE = "00000000-0000-4000-8000-000000000001"
        const val MUTATION_TWO = "00000000-0000-4000-8000-000000000002"
    }

}
