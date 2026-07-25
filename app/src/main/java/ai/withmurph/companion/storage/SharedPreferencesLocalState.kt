package ai.withmurph.companion.storage

import android.content.Context
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.LocalState
import java.util.UUID

class SharedPreferencesLocalState(context: Context) : LocalState {
    private val preferences = context.getSharedPreferences(
        "murph_companion_state",
        Context.MODE_PRIVATE,
    )

    override val installationId: String
        get() = preferences.getString(KEY_INSTALLATION_ID, null)
            ?: UUID.randomUUID().toString().also { created ->
                preferences.edit().putString(KEY_INSTALLATION_ID, created).apply()
            }

    override var memberKey: String?
        get() = preferences.getString(KEY_MEMBER_KEY, null)
        set(value) {
            preferences.edit().putString(KEY_MEMBER_KEY, value).apply()
        }

    override var healthAccessRequestedAt: InstantValue?
        get() = preferences.readInstant(KEY_HEALTH_ACCESS_REQUESTED_AT)
        set(value) {
            preferences.writeInstant(KEY_HEALTH_ACCESS_REQUESTED_AT, value)
        }

    override var lastKnownDataReceivedAt: InstantValue?
        get() = preferences.readInstant(KEY_LAST_DATA_RECEIVED_AT)
        set(value) {
            preferences.writeInstant(KEY_LAST_DATA_RECEIVED_AT, value)
        }

    override fun clearMemberScopedState() {
        preferences.edit()
            .remove(KEY_MEMBER_KEY)
            .remove(KEY_HEALTH_ACCESS_REQUESTED_AT)
            .remove(KEY_LAST_DATA_RECEIVED_AT)
            .apply()
    }

    private fun android.content.SharedPreferences.readInstant(key: String): InstantValue? =
        if (contains(key)) InstantValue(getLong(key, 0L)) else null

    private fun android.content.SharedPreferences.writeInstant(key: String, value: InstantValue?) {
        edit().apply {
            if (value == null) remove(key) else putLong(key, value.epochMilliseconds)
        }.apply()
    }

    private companion object {
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_MEMBER_KEY = "member_key"
        const val KEY_HEALTH_ACCESS_REQUESTED_AT = "health_access_requested_at"
        const val KEY_LAST_DATA_RECEIVED_AT = "last_data_received_at"
    }
}
