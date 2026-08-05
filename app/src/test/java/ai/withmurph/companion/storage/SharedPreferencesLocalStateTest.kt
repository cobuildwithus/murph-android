package ai.withmurph.companion.storage

import android.content.SharedPreferences
import ai.withmurph.companion.core.AddressBookMutation
import ai.withmurph.companion.core.InstantValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesLocalStateTest {
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
        preferences.failCommits = true

        assertFalse(state.beginSignOut())

        assertFalse(state.signOutPending)
        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(baselineAt, state.healthReceiptBaselineAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        assertEquals(observedAt, state.lastKnownStatusObservedAt)
        assertTrue(state.healthReconnectRequired)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertFalse(reconstructed.signOutPending)
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(baselineAt, reconstructed.healthReceiptBaselineAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
        assertEquals(observedAt, reconstructed.lastKnownStatusObservedAt)
        assertTrue(reconstructed.healthReconnectRequired)
    }

    @Test
    fun failedSignOutBoundaryPreservesExistingTombstone() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        assertTrue(state.beginSignOut())
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

        assertFalse(state.beginSignOut())

        assertTrue(state.signOutPending)
        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(baselineAt, state.healthReceiptBaselineAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        assertEquals(observedAt, state.lastKnownStatusObservedAt)
        assertTrue(state.healthReconnectRequired)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertTrue(reconstructed.signOutPending)
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(baselineAt, reconstructed.healthReceiptBaselineAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
        assertEquals(observedAt, reconstructed.lastKnownStatusObservedAt)
        assertTrue(reconstructed.healthReconnectRequired)
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
        preferences.failCommits = true

        assertFalse(state.beginSignOut())
        assertEquals(11, state.addressBookRevision)
        assertEquals(deletion, state.pendingAddressBookDeletion)

        preferences.failCommits = false
        assertTrue(state.beginSignOut())
        assertTrue(state.signOutPending)
        assertNull(state.addressBookRevision)
        assertNull(state.pendingAddressBookReplacement)
        assertNull(state.pendingAddressBookDeletion)
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
