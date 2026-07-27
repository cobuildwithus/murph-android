package ai.withmurph.companion.storage

import android.content.SharedPreferences
import ai.withmurph.companion.core.InstantValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesLocalStateTest {
    @Test
    fun failedHealthRevocationRestoresLiveAndPersistedAuthorization() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val requestedAt = InstantValue(100)
        val receivedAt = InstantValue(200)
        state.healthAccessRequestedAt = requestedAt
        state.lastKnownDataReceivedAt = receivedAt
        preferences.failCommits = true

        assertFalse(state.revokeHealthSetupAuthorization())

        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
    }

    @Test
    fun failedSignOutBoundaryRestoresLiveAndPersistedAuthorization() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        val requestedAt = InstantValue(300)
        val receivedAt = InstantValue(400)
        state.healthAccessRequestedAt = requestedAt
        state.lastKnownDataReceivedAt = receivedAt
        preferences.failCommits = true

        assertFalse(state.beginSignOut())

        assertFalse(state.signOutPending)
        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertFalse(reconstructed.signOutPending)
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
    }

    @Test
    fun failedSignOutBoundaryPreservesExistingTombstone() {
        val preferences = FaultInjectedPreferences()
        val state = SharedPreferencesLocalState(preferences)
        assertTrue(state.beginSignOut())
        val requestedAt = InstantValue(500)
        val receivedAt = InstantValue(600)
        state.healthAccessRequestedAt = requestedAt
        state.lastKnownDataReceivedAt = receivedAt
        preferences.failCommits = true

        assertFalse(state.beginSignOut())

        assertTrue(state.signOutPending)
        assertEquals(requestedAt, state.healthAccessRequestedAt)
        assertEquals(receivedAt, state.lastKnownDataReceivedAt)
        val reconstructed = SharedPreferencesLocalState(preferences.recreated())
        assertTrue(reconstructed.signOutPending)
        assertEquals(requestedAt, reconstructed.healthAccessRequestedAt)
        assertEquals(receivedAt, reconstructed.lastKnownDataReceivedAt)
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
}
