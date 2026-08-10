package ai.withmurph.companion.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import ai.withmurph.companion.core.AddressBookMutation
import ai.withmurph.companion.core.HealthSyncReminderDeadline
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.InitialSetupStep
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.core.PendingHealthSyncFailure
import ai.withmurph.companion.core.PendingExternalHandoff
import ai.withmurph.companion.core.UNKNOWN_HEALTH_RESOURCE_KEY
import java.util.UUID

class SharedPreferencesLocalState internal constructor(
    private val preferences: SharedPreferences,
) : LocalState {
    constructor(context: Context) : this(
        context.getSharedPreferences(
            "murph_companion_state",
            Context.MODE_PRIVATE,
        ),
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

    override var initialSetupStep: InitialSetupStep?
        get() = InitialSetupStep.fromWireValue(
            preferences.getString(KEY_INITIAL_SETUP_STEP, null),
        )
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    remove(KEY_INITIAL_SETUP_STEP)
                } else {
                    putString(KEY_INITIAL_SETUP_STEP, value.wireValue)
                }
            }.apply()
        }

    override var healthAccessRequestedAt: InstantValue?
        get() = preferences.readInstant(KEY_HEALTH_ACCESS_REQUESTED_AT)
        set(value) {
            preferences.writeInstant(KEY_HEALTH_ACCESS_REQUESTED_AT, value)
        }

    override var healthReceiptBaselineAt: InstantValue?
        get() = preferences.readInstant(KEY_HEALTH_RECEIPT_BASELINE_AT)
        set(value) {
            preferences.writeInstant(KEY_HEALTH_RECEIPT_BASELINE_AT, value)
        }

    override var lastKnownDataReceivedAt: InstantValue?
        get() = preferences.readInstant(KEY_LAST_DATA_RECEIVED_AT)
        set(value) {
            preferences.writeInstant(KEY_LAST_DATA_RECEIVED_AT, value)
        }

    override var lastKnownStatusObservedAt: InstantValue?
        get() = preferences.readInstant(KEY_LAST_STATUS_OBSERVED_AT)
        set(value) {
            preferences.writeInstant(KEY_LAST_STATUS_OBSERVED_AT, value)
        }

    override var healthReconnectRequired: Boolean
        get() = preferences.getBoolean(KEY_HEALTH_RECONNECT_REQUIRED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_HEALTH_RECONNECT_REQUIRED, value).apply()
        }

    override val pendingHealthSyncFailure: PendingHealthSyncFailure?
        get() {
            preferences.getStringSet(
                KEY_PENDING_HEALTH_SYNC_FAILURE_RESOURCE_FLOORS,
                null,
            )?.let { encodedFloors ->
                val decodedFloors = encodedFloors
                    .map(::decodePendingHealthSyncFailureFloor)
                if (decodedFloors.any { it == null }) {
                    return invalidPendingHealthSyncFailure()
                }
                val receiptFloorsByResource = decodedFloors.filterNotNull().toMap()
                if (receiptFloorsByResource.size != encodedFloors.size) {
                    return invalidPendingHealthSyncFailure()
                }
                return runCatching {
                    PendingHealthSyncFailure(receiptFloorsByResource)
                }.getOrElse { invalidPendingHealthSyncFailure() }
            }
            val resourceKeys = preferences
                .getStringSet(KEY_PENDING_HEALTH_SYNC_FAILURE_RESOURCES, null)
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val receiptFloorAt = preferences.readInstant(
                KEY_PENDING_HEALTH_SYNC_FAILURE_RECEIPT_FLOOR,
            ) ?: return null
            return runCatching {
                PendingHealthSyncFailure(
                    resourceKeys = if (UNKNOWN_HEALTH_RESOURCE_KEY in resourceKeys) {
                        setOf(UNKNOWN_HEALTH_RESOURCE_KEY)
                    } else {
                        resourceKeys
                    },
                    receiptFloorAt = receiptFloorAt,
                )
            }.getOrElse { invalidPendingHealthSyncFailure() }
        }

    override val healthSyncReminderDeadline: HealthSyncReminderDeadline?
        get() {
            val basisToken = preferences.getString(
                KEY_HEALTH_SYNC_REMINDER_DEADLINE_BASIS,
                null,
            ) ?: return null
            if (
                !preferences.contains(KEY_HEALTH_SYNC_REMINDER_DEADLINE_BOOT_COUNT) ||
                !preferences.contains(KEY_HEALTH_SYNC_REMINDER_DEADLINE_TRIGGER)
            ) {
                return null
            }
            return runCatching {
                HealthSyncReminderDeadline(
                    basisToken = basisToken,
                    bootCount = preferences.getInt(
                        KEY_HEALTH_SYNC_REMINDER_DEADLINE_BOOT_COUNT,
                        -1,
                    ),
                    triggerElapsedRealtimeMillis = preferences.getLong(
                        KEY_HEALTH_SYNC_REMINDER_DEADLINE_TRIGGER,
                        -1L,
                    ),
                )
            }.getOrNull()
        }

    override val signOutPending: Boolean
        get() = preferences.getBoolean(KEY_SIGN_OUT_PENDING, false)

    override val pendingPrivySignOutMemberKey: String?
        get() = preferences.getString(KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY, null)

    override val pendingExternalHandoff: PendingExternalHandoff?
        get() = when (preferences.getString(KEY_PENDING_EXTERNAL_HANDOFF, null)) {
            PENDING_EXTERNAL_HANDOFF_ACCOUNT_DELETION ->
                PendingExternalHandoff.AccountDeletion
            else -> null
        }

    override val addressBookRevision: Int?
        get() = preferences.readNonNegativeInt(KEY_ADDRESS_BOOK_REVISION)

    override val pendingAddressBookReplacement: AddressBookMutation?
        get() = preferences.readMutation(
            KEY_ADDRESS_BOOK_REPLACEMENT_BASE_REVISION,
            KEY_ADDRESS_BOOK_REPLACEMENT_MUTATION_ID,
        )

    override val pendingAddressBookDeletion: AddressBookMutation?
        get() = preferences.readMutation(
            KEY_ADDRESS_BOOK_DELETION_BASE_REVISION,
            KEY_ADDRESS_BOOK_DELETION_MUTATION_ID,
        )

    override fun advanceInitialSetupStep(
        expected: InitialSetupStep,
        next: InitialSetupStep,
        abandonPendingAddressBookReplacement: Boolean,
    ): Boolean {
        if (initialSetupStep != expected) return false
        return commitAddressBookChange {
            writeInitialSetupStep(next)
            if (abandonPendingAddressBookReplacement) removeAddressBookReplacement()
        }
    }

    override fun isHealthSyncReminderEnabled(memberKey: String): Boolean =
        !signOutPending &&
            memberKey == this.memberKey &&
            preferences.getBoolean(KEY_HEALTH_SYNC_REMINDER_ENABLED, false)

    @SuppressLint("ApplySharedPref")
    override fun setHealthSyncReminderEnabled(
        memberKey: String,
        enabled: Boolean,
        initialDeadline: HealthSyncReminderDeadline?,
    ): Boolean {
        if (signOutPending || memberKey != this.memberKey) return false
        val wasPresent = preferences.contains(KEY_HEALTH_SYNC_REMINDER_ENABLED)
        val wasEnabled = preferences.getBoolean(KEY_HEALTH_SYNC_REMINDER_ENABLED, false)
        val previousDeadline = healthSyncReminderDeadline
        val committed = preferences.edit().apply {
            if (enabled) {
                putBoolean(KEY_HEALTH_SYNC_REMINDER_ENABLED, true)
                if (initialDeadline != null) {
                    writeHealthSyncReminderDeadline(initialDeadline)
                } else {
                    removeHealthSyncReminderDeadline()
                }
            } else {
                remove(KEY_HEALTH_SYNC_REMINDER_ENABLED)
                removeHealthSyncReminderDeadline()
            }
        }.commit()
        if (!committed) {
            preferences.edit().apply {
                if (wasPresent) {
                    putBoolean(KEY_HEALTH_SYNC_REMINDER_ENABLED, wasEnabled)
                } else {
                    remove(KEY_HEALTH_SYNC_REMINDER_ENABLED)
                }
                writeHealthSyncReminderDeadline(previousDeadline)
            }.commit()
        }
        return committed
    }

    @SuppressLint("ApplySharedPref")
    override fun persistHealthSyncReminderDeadline(
        memberKey: String,
        deadline: HealthSyncReminderDeadline,
    ): Boolean {
        if (!isHealthSyncReminderEnabled(memberKey)) return false
        val previousDeadline = healthSyncReminderDeadline
        val committed = preferences.edit()
            .writeHealthSyncReminderDeadline(deadline)
            .commit()
        if (!committed) {
            preferences.edit().writeHealthSyncReminderDeadline(previousDeadline).commit()
        }
        return committed
    }

    override fun recordAddressBookRevision(revision: Int): Boolean {
        require(revision >= 0)
        return commitAddressBookChange {
            putInt(KEY_ADDRESS_BOOK_REVISION, revision)
        }
    }

    override fun recordDisabledAddressBookRevision(revision: Int): Boolean {
        require(revision >= 0)
        return commitAddressBookChange {
            putInt(KEY_ADDRESS_BOOK_REVISION, revision)
            removeAddressBookReplacement()
            removeAddressBookDeletion()
        }
    }

    override fun beginAddressBookReplacement(mutation: AddressBookMutation): Boolean =
        commitAddressBookChange {
            putInt(KEY_ADDRESS_BOOK_REPLACEMENT_BASE_REVISION, mutation.baseRevision)
            putString(KEY_ADDRESS_BOOK_REPLACEMENT_MUTATION_ID, mutation.mutationId)
            removeAddressBookDeletion()
        }

    override fun completeAddressBookReplacement(
        mutationId: String,
        revision: Int,
        completesInitialSetup: Boolean,
    ): Boolean {
        val pending = pendingAddressBookReplacement ?: return false
        if (pending.mutationId != mutationId || revision <= pending.baseRevision) return false
        if (completesInitialSetup && initialSetupStep != InitialSetupStep.FriendlyNames) {
            return false
        }
        return commitAddressBookChange {
            putInt(KEY_ADDRESS_BOOK_REVISION, revision)
            removeAddressBookReplacement()
            removeAddressBookDeletion()
            if (completesInitialSetup) {
                writeInitialSetupStep(InitialSetupStep.Complete)
            }
        }
    }

    override fun abandonAddressBookReplacement(mutationId: String): Boolean {
        if (pendingAddressBookReplacement?.mutationId != mutationId) return false
        return commitAddressBookChange { removeAddressBookReplacement() }
    }

    override fun beginAddressBookDeletion(mutation: AddressBookMutation): Boolean =
        commitAddressBookChange {
            putInt(KEY_ADDRESS_BOOK_DELETION_BASE_REVISION, mutation.baseRevision)
            putString(KEY_ADDRESS_BOOK_DELETION_MUTATION_ID, mutation.mutationId)
            removeAddressBookReplacement()
        }

    override fun completeAddressBookDeletion(
        mutationId: String,
        revision: Int,
    ): Boolean {
        val pending = pendingAddressBookDeletion ?: return false
        if (pending.mutationId != mutationId || revision <= pending.baseRevision) return false
        return commitAddressBookChange {
            putInt(KEY_ADDRESS_BOOK_REVISION, revision)
            removeAddressBookReplacement()
            removeAddressBookDeletion()
        }
    }

    override fun abandonAddressBookDeletion(mutationId: String): Boolean {
        if (pendingAddressBookDeletion?.mutationId != mutationId) return false
        return commitAddressBookChange { removeAddressBookDeletion() }
    }

    @SuppressLint("ApplySharedPref")
    override fun recordPendingHealthSyncFailure(failure: PendingHealthSyncFailure): Boolean {
        if (healthAccessRequestedAt == null || signOutPending) return false
        val merged = pendingHealthSyncFailure?.mergedWith(failure) ?: failure
        return replacePendingHealthSyncFailure(merged)
    }

    @SuppressLint("ApplySharedPref")
    override fun replacePendingHealthSyncFailure(
        failure: PendingHealthSyncFailure?,
    ): Boolean {
        if (failure != null && (healthAccessRequestedAt == null || signOutPending)) return false
        val previous = pendingHealthSyncFailure
        if (previous == failure) return true
        val committed = preferences.edit()
            .writePendingHealthSyncFailure(failure)
            .commit()
        if (!committed) {
            preferences.edit().writePendingHealthSyncFailure(previous).commit()
        }
        return committed
    }

    @SuppressLint("ApplySharedPref")
    override fun clearPendingHealthSyncFailure(): Boolean {
        return replacePendingHealthSyncFailure(null)
    }

    @SuppressLint("ApplySharedPref")
    override fun completeHealthSetupAuthorization(
        requestedAt: InstantValue,
        receiptBaselineAt: InstantValue?,
        statusObservedAt: InstantValue,
        reminderDeadline: HealthSyncReminderDeadline?,
        completesInitialSetup: Boolean,
    ): Boolean {
        val previousRequestedAt = healthAccessRequestedAt
        val previousReceiptBaselineAt = healthReceiptBaselineAt
        val previousReceivedAt = lastKnownDataReceivedAt
        val previousStatusObservedAt = lastKnownStatusObservedAt
        val previousReconnectRequired = healthReconnectRequired
        val previousPendingHealthSyncFailure = pendingHealthSyncFailure
        val previousReminderDeadline = healthSyncReminderDeadline
        val previousSetupStep = initialSetupStep
        val reminderEnabled = currentReminderPreference()
        if (reminderEnabled && reminderDeadline == null) return false
        if (completesInitialSetup && previousSetupStep != InitialSetupStep.HealthConnect) {
            return false
        }
        val committed = preferences.edit().apply {
            writeInstant(KEY_HEALTH_ACCESS_REQUESTED_AT, requestedAt)
            writeInstant(KEY_HEALTH_RECEIPT_BASELINE_AT, receiptBaselineAt)
            remove(KEY_LAST_DATA_RECEIVED_AT)
            writeInstant(KEY_LAST_STATUS_OBSERVED_AT, statusObservedAt)
            remove(KEY_HEALTH_RECONNECT_REQUIRED)
            removePendingHealthSyncFailure()
            writeHealthSyncReminderDeadline(
                if (reminderEnabled) reminderDeadline else null,
            )
            if (completesInitialSetup) {
                writeInitialSetupStep(InitialSetupStep.FriendlyNames)
            }
        }.commit()
        if (!committed) {
            restoreAuthorizationSnapshot(
                previousRequestedAt,
                previousReceiptBaselineAt,
                previousReceivedAt,
                previousStatusObservedAt,
                previousReconnectRequired,
                previousReminderDeadline,
                setupStep = previousSetupStep,
                pendingHealthSyncFailure = previousPendingHealthSyncFailure,
            )
        }
        return committed
    }

    @SuppressLint("ApplySharedPref")
    override fun requireHealthReconnect(): Boolean {
        val requestedAt = healthAccessRequestedAt
        val receiptBaselineAt = healthReceiptBaselineAt
        val receivedAt = lastKnownDataReceivedAt
        val statusObservedAt = lastKnownStatusObservedAt
        val reconnectRequired = healthReconnectRequired
        val previousPendingHealthSyncFailure = pendingHealthSyncFailure
        val reminderDeadline = healthSyncReminderDeadline
        val committed = preferences.edit()
            .remove(KEY_HEALTH_ACCESS_REQUESTED_AT)
            .remove(KEY_HEALTH_RECEIPT_BASELINE_AT)
            .remove(KEY_LAST_DATA_RECEIVED_AT)
            .remove(KEY_LAST_STATUS_OBSERVED_AT)
            .putBoolean(KEY_HEALTH_RECONNECT_REQUIRED, true)
            .removePendingHealthSyncFailure()
            .removeHealthSyncReminderDeadline()
            .commit()
        if (!committed) {
            restoreAuthorizationSnapshot(
                requestedAt,
                receiptBaselineAt,
                receivedAt,
                statusObservedAt,
                reconnectRequired,
                reminderDeadline,
                pendingHealthSyncFailure = previousPendingHealthSyncFailure,
            )
        }
        return committed
    }

    @SuppressLint("ApplySharedPref")
    override fun revokeHealthSetupAuthorization(): Boolean {
        val requestedAt = healthAccessRequestedAt
        val receiptBaselineAt = healthReceiptBaselineAt
        val receivedAt = lastKnownDataReceivedAt
        val statusObservedAt = lastKnownStatusObservedAt
        val reconnectRequired = healthReconnectRequired
        val previousPendingHealthSyncFailure = pendingHealthSyncFailure
        val reminderDeadline = healthSyncReminderDeadline
        val committed = preferences.edit()
            .remove(KEY_HEALTH_ACCESS_REQUESTED_AT)
            .remove(KEY_HEALTH_RECEIPT_BASELINE_AT)
            .remove(KEY_LAST_DATA_RECEIVED_AT)
            .remove(KEY_LAST_STATUS_OBSERVED_AT)
            .remove(KEY_HEALTH_RECONNECT_REQUIRED)
            .removePendingHealthSyncFailure()
            .removeHealthSyncReminderDeadline()
            .commit()
        if (!committed) {
            restoreAuthorizationSnapshot(
                requestedAt,
                receiptBaselineAt,
                receivedAt,
                statusObservedAt,
                reconnectRequired,
                reminderDeadline,
                pendingHealthSyncFailure = previousPendingHealthSyncFailure,
            )
        }
        return committed
    }

    @SuppressLint("ApplySharedPref")
    override fun beginSignOut(
        expectedMemberKey: String?,
        privySignOutMemberKey: String?,
        preserveMemberState: Boolean,
        pendingExternalHandoff: PendingExternalHandoff?,
    ): Boolean {
        if (memberKey != expectedMemberKey) return false
        val requestedAt = healthAccessRequestedAt
        val receiptBaselineAt = healthReceiptBaselineAt
        val receivedAt = lastKnownDataReceivedAt
        val statusObservedAt = lastKnownStatusObservedAt
        val reconnectRequired = healthReconnectRequired
        val reminderEnabled = currentReminderPreference()
        val reminderDeadline = healthSyncReminderDeadline
        val wasSignOutPending = signOutPending
        val previousPrivySignOutMemberKey = pendingPrivySignOutMemberKey
        val previousExternalHandoff = this.pendingExternalHandoff
        val setupStep = initialSetupStep
        val addressBookSnapshot = readAddressBookSnapshot()
        // Persist the teardown request before SDK work is cancelled. Health authority
        // remains committed until Vital's durable workers are proven terminal and its
        // identity is signed out; the WorkManager factory rejects new or restarted
        // Vital work whenever this tombstone is present.
        val editor = preferences.edit()
            .putBoolean(KEY_SIGN_OUT_PENDING, true)
            .remove(KEY_HEALTH_SYNC_REMINDER_ENABLED)
            .removeHealthSyncReminderDeadline()
        if (privySignOutMemberKey == null) {
            editor.remove(KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY)
        } else {
            editor.putString(KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY, privySignOutMemberKey)
        }
        editor.writePendingExternalHandoff(pendingExternalHandoff)
        if (!preserveMemberState) {
            editor
                .remove(KEY_INITIAL_SETUP_STEP)
                .removeAddressBookMetadata()
        }
        val committed = editor.commit()
        if (!committed) {
            restoreAuthorizationSnapshot(
                requestedAt,
                receiptBaselineAt,
                receivedAt,
                statusObservedAt,
                reconnectRequired,
                reminderDeadline,
                reminderEnabled,
                wasSignOutPending,
                previousPrivySignOutMemberKey,
                previousExternalHandoff,
                setupStep,
                addressBookSnapshot,
            )
        }
        return committed
    }

    @SuppressLint("ApplySharedPref")
    override fun completeExternalHandoff(expected: PendingExternalHandoff): Boolean {
        if (pendingExternalHandoff != expected || signOutPending) return false
        val committed = preferences.edit()
            .remove(KEY_PENDING_EXTERNAL_HANDOFF)
            .commit()
        if (!committed) {
            preferences.edit().writePendingExternalHandoff(expected).commit()
        }
        return committed
    }

    @SuppressLint("ApplySharedPref")
    override fun completeSignOut(expectedMemberKey: String?): Boolean {
        if (memberKey != expectedMemberKey) return false
        val privySignOutMemberKey = pendingPrivySignOutMemberKey
        val committed = preferences.edit()
            .remove(KEY_MEMBER_KEY)
            .remove(KEY_HEALTH_ACCESS_REQUESTED_AT)
            .remove(KEY_HEALTH_RECEIPT_BASELINE_AT)
            .remove(KEY_LAST_DATA_RECEIVED_AT)
            .remove(KEY_LAST_STATUS_OBSERVED_AT)
            .remove(KEY_HEALTH_RECONNECT_REQUIRED)
            .removePendingHealthSyncFailure()
            .remove(KEY_INITIAL_SETUP_STEP)
            .remove(KEY_HEALTH_SYNC_REMINDER_ENABLED)
            .removeHealthSyncReminderDeadline()
            .remove(KEY_SIGN_OUT_PENDING)
            .remove(KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY)
            .removeAddressBookMetadata()
            .commit()
        if (!committed) {
            // SharedPreferences updates memory before reporting a disk failure.
            // Reassert the expected owner and fail-closed tombstone for retry.
            preferences.edit().apply {
                if (expectedMemberKey == null) {
                    remove(KEY_MEMBER_KEY)
                } else {
                    putString(KEY_MEMBER_KEY, expectedMemberKey)
                }
                putBoolean(KEY_SIGN_OUT_PENDING, true)
                if (privySignOutMemberKey == null) {
                    remove(KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY)
                } else {
                    putString(KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY, privySignOutMemberKey)
                }
            }.commit()
        }
        return committed
    }

    override fun clearMemberScopedState() {
        preferences.edit()
            .remove(KEY_MEMBER_KEY)
            .remove(KEY_HEALTH_ACCESS_REQUESTED_AT)
            .remove(KEY_HEALTH_RECEIPT_BASELINE_AT)
            .remove(KEY_LAST_DATA_RECEIVED_AT)
            .remove(KEY_LAST_STATUS_OBSERVED_AT)
            .remove(KEY_HEALTH_RECONNECT_REQUIRED)
            .removePendingHealthSyncFailure()
            .remove(KEY_INITIAL_SETUP_STEP)
            .remove(KEY_HEALTH_SYNC_REMINDER_ENABLED)
            .removeHealthSyncReminderDeadline()
            .removeAddressBookMetadata()
            .apply()
    }

    private fun android.content.SharedPreferences.readInstant(key: String): InstantValue? =
        if (contains(key)) InstantValue(getLong(key, 0L)) else null

    private fun android.content.SharedPreferences.writeInstant(key: String, value: InstantValue?) {
        edit().apply {
            writeInstant(key, value)
        }.apply()
    }

    @SuppressLint("ApplySharedPref")
    private fun restoreAuthorizationSnapshot(
        requestedAt: InstantValue?,
        receiptBaselineAt: InstantValue?,
        receivedAt: InstantValue?,
        statusObservedAt: InstantValue?,
        reconnectRequired: Boolean,
        reminderDeadline: HealthSyncReminderDeadline?,
        reminderEnabled: Boolean? = null,
        pendingSignOut: Boolean? = null,
        pendingPrivySignOutMemberKey: String? = null,
        pendingExternalHandoff: PendingExternalHandoff? = this.pendingExternalHandoff,
        setupStep: InitialSetupStep? = initialSetupStep,
        addressBookSnapshot: AddressBookSnapshot? = null,
        pendingHealthSyncFailure: PendingHealthSyncFailure? = this.pendingHealthSyncFailure,
    ) {
        preferences.edit().apply {
            writeInstant(KEY_HEALTH_ACCESS_REQUESTED_AT, requestedAt)
            writeInstant(KEY_HEALTH_RECEIPT_BASELINE_AT, receiptBaselineAt)
            writeInstant(KEY_LAST_DATA_RECEIVED_AT, receivedAt)
            writeInstant(KEY_LAST_STATUS_OBSERVED_AT, statusObservedAt)
            putBoolean(KEY_HEALTH_RECONNECT_REQUIRED, reconnectRequired)
            writePendingHealthSyncFailure(pendingHealthSyncFailure)
            writeHealthSyncReminderDeadline(reminderDeadline)
            writeInitialSetupStep(setupStep)
            if (reminderEnabled != null) {
                if (reminderEnabled) {
                    putBoolean(KEY_HEALTH_SYNC_REMINDER_ENABLED, true)
                } else {
                    remove(KEY_HEALTH_SYNC_REMINDER_ENABLED)
                }
            }
            if (pendingSignOut != null) {
                if (pendingSignOut) {
                    putBoolean(KEY_SIGN_OUT_PENDING, true)
                } else {
                    remove(KEY_SIGN_OUT_PENDING)
                }
                if (pendingPrivySignOutMemberKey == null) {
                    remove(KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY)
                } else {
                    putString(
                        KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY,
                        pendingPrivySignOutMemberKey,
                    )
                }
                writePendingExternalHandoff(pendingExternalHandoff)
            }
            addressBookSnapshot?.let { writeAddressBookSnapshot(it) }
        }.commit()
    }

    @SuppressLint("ApplySharedPref")
    private fun commitAddressBookChange(
        change: SharedPreferences.Editor.() -> Unit,
    ): Boolean {
        val snapshot = readAddressBookSnapshot()
        val setupStep = initialSetupStep
        val committed = preferences.edit().apply(change).commit()
        if (!committed) {
            preferences.edit().apply {
                writeAddressBookSnapshot(snapshot)
                writeInitialSetupStep(setupStep)
            }.commit()
        }
        return committed
    }

    private fun readAddressBookSnapshot(): AddressBookSnapshot = AddressBookSnapshot(
        revision = addressBookRevision,
        replacement = pendingAddressBookReplacement,
        deletion = pendingAddressBookDeletion,
    )

    private fun SharedPreferences.readNonNegativeInt(key: String): Int? =
        if (contains(key)) getInt(key, -1).takeIf { it >= 0 } else null

    private fun SharedPreferences.readMutation(
        baseRevisionKey: String,
        mutationIdKey: String,
    ): AddressBookMutation? {
        val baseRevision = readNonNegativeInt(baseRevisionKey) ?: return null
        val mutationId = getString(mutationIdKey, null) ?: return null
        return runCatching { AddressBookMutation(baseRevision, mutationId) }.getOrNull()
    }

    private fun SharedPreferences.Editor.writeAddressBookSnapshot(snapshot: AddressBookSnapshot) {
        removeAddressBookMetadata()
        snapshot.revision?.let { putInt(KEY_ADDRESS_BOOK_REVISION, it) }
        snapshot.replacement?.let { mutation ->
            putInt(KEY_ADDRESS_BOOK_REPLACEMENT_BASE_REVISION, mutation.baseRevision)
            putString(KEY_ADDRESS_BOOK_REPLACEMENT_MUTATION_ID, mutation.mutationId)
        }
        snapshot.deletion?.let { mutation ->
            putInt(KEY_ADDRESS_BOOK_DELETION_BASE_REVISION, mutation.baseRevision)
            putString(KEY_ADDRESS_BOOK_DELETION_MUTATION_ID, mutation.mutationId)
        }
    }

    private fun SharedPreferences.Editor.removeAddressBookMetadata(): SharedPreferences.Editor =
        remove(KEY_ADDRESS_BOOK_REVISION)
            .removeAddressBookReplacement()
            .removeAddressBookDeletion()

    private fun SharedPreferences.Editor.removeAddressBookReplacement(): SharedPreferences.Editor =
        remove(KEY_ADDRESS_BOOK_REPLACEMENT_BASE_REVISION)
            .remove(KEY_ADDRESS_BOOK_REPLACEMENT_MUTATION_ID)

    private fun SharedPreferences.Editor.removeAddressBookDeletion(): SharedPreferences.Editor =
        remove(KEY_ADDRESS_BOOK_DELETION_BASE_REVISION)
            .remove(KEY_ADDRESS_BOOK_DELETION_MUTATION_ID)

    private fun SharedPreferences.Editor.writeHealthSyncReminderDeadline(
        deadline: HealthSyncReminderDeadline?,
    ): SharedPreferences.Editor {
        removeHealthSyncReminderDeadline()
        if (deadline != null) {
            putString(KEY_HEALTH_SYNC_REMINDER_DEADLINE_BASIS, deadline.basisToken)
            putInt(KEY_HEALTH_SYNC_REMINDER_DEADLINE_BOOT_COUNT, deadline.bootCount)
            putLong(
                KEY_HEALTH_SYNC_REMINDER_DEADLINE_TRIGGER,
                deadline.triggerElapsedRealtimeMillis,
            )
        }
        return this
    }

    private fun SharedPreferences.Editor.removeHealthSyncReminderDeadline():
        SharedPreferences.Editor = remove(KEY_HEALTH_SYNC_REMINDER_DEADLINE_BASIS)
            .remove(KEY_HEALTH_SYNC_REMINDER_DEADLINE_BOOT_COUNT)
            .remove(KEY_HEALTH_SYNC_REMINDER_DEADLINE_TRIGGER)

    private data class AddressBookSnapshot(
        val revision: Int?,
        val replacement: AddressBookMutation?,
        val deletion: AddressBookMutation?,
    )

    private fun SharedPreferences.Editor.writeInstant(key: String, value: InstantValue?) {
        if (value == null) remove(key) else putLong(key, value.epochMilliseconds)
    }

    private fun SharedPreferences.Editor.writeInitialSetupStep(value: InitialSetupStep?) {
        if (value == null) remove(KEY_INITIAL_SETUP_STEP)
        else putString(KEY_INITIAL_SETUP_STEP, value.wireValue)
    }

    private fun SharedPreferences.Editor.writePendingHealthSyncFailure(
        failure: PendingHealthSyncFailure?,
    ): SharedPreferences.Editor {
        removePendingHealthSyncFailure()
        if (failure != null) {
            putStringSet(
                KEY_PENDING_HEALTH_SYNC_FAILURE_RESOURCE_FLOORS,
                failure.receiptFloorsByResource.mapTo(linkedSetOf()) {
                    (resourceKey, receiptFloorAt) ->
                    resourceKey + ":" + receiptFloorAt.epochMilliseconds
                },
            )
        }
        return this
    }

    private fun SharedPreferences.Editor.removePendingHealthSyncFailure():
        SharedPreferences.Editor = remove(KEY_PENDING_HEALTH_SYNC_FAILURE_RESOURCE_FLOORS)
            .remove(KEY_PENDING_HEALTH_SYNC_FAILURE_RESOURCES)
            .remove(KEY_PENDING_HEALTH_SYNC_FAILURE_RECEIPT_FLOOR)

    private fun SharedPreferences.Editor.writePendingExternalHandoff(
        pending: PendingExternalHandoff?,
    ): SharedPreferences.Editor {
        if (pending == PendingExternalHandoff.AccountDeletion) {
            putString(
                KEY_PENDING_EXTERNAL_HANDOFF,
                PENDING_EXTERNAL_HANDOFF_ACCOUNT_DELETION,
            )
        } else {
            remove(KEY_PENDING_EXTERNAL_HANDOFF)
        }
        return this
    }

    private fun decodePendingHealthSyncFailureFloor(
        encoded: String,
    ): Pair<String, InstantValue>? {
        val separator = encoded.lastIndexOf(':')
        if (separator <= 0 || separator == encoded.lastIndex) return null
        val resourceKey = encoded.substring(0, separator)
        val floor = encoded.substring(separator + 1).toLongOrNull() ?: return null
        return resourceKey to InstantValue(floor)
    }

    private fun invalidPendingHealthSyncFailure() = PendingHealthSyncFailure(
        setOf(UNKNOWN_HEALTH_RESOURCE_KEY),
        InstantValue(Long.MAX_VALUE),
    )

    private fun currentReminderPreference(): Boolean =
        preferences.getBoolean(KEY_HEALTH_SYNC_REMINDER_ENABLED, false)

    private companion object {
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_MEMBER_KEY = "member_key"
        const val KEY_INITIAL_SETUP_STEP = "initial_setup_step"
        const val KEY_HEALTH_ACCESS_REQUESTED_AT = "health_access_requested_at"
        const val KEY_HEALTH_RECEIPT_BASELINE_AT = "health_receipt_baseline_at"
        const val KEY_LAST_DATA_RECEIVED_AT = "last_data_received_at"
        const val KEY_LAST_STATUS_OBSERVED_AT = "last_status_observed_at"
        const val KEY_HEALTH_RECONNECT_REQUIRED = "health_reconnect_required"
        const val KEY_PENDING_HEALTH_SYNC_FAILURE_RESOURCE_FLOORS =
            "pending_health_sync_failure_resource_floors"
        const val KEY_PENDING_HEALTH_SYNC_FAILURE_RESOURCES =
            "pending_health_sync_failure_resources"
        const val KEY_PENDING_HEALTH_SYNC_FAILURE_RECEIPT_FLOOR =
            "pending_health_sync_failure_receipt_floor"
        const val KEY_HEALTH_SYNC_REMINDER_ENABLED = "health_sync_reminder_enabled"
        const val KEY_HEALTH_SYNC_REMINDER_DEADLINE_BASIS =
            "health_sync_reminder_deadline_basis"
        const val KEY_HEALTH_SYNC_REMINDER_DEADLINE_BOOT_COUNT =
            "health_sync_reminder_deadline_boot_count"
        const val KEY_HEALTH_SYNC_REMINDER_DEADLINE_TRIGGER =
            "health_sync_reminder_deadline_trigger"
        const val KEY_SIGN_OUT_PENDING = "sign_out_pending"
        const val KEY_PENDING_PRIVY_SIGN_OUT_MEMBER_KEY =
            "pending_privy_sign_out_member_key"
        const val KEY_PENDING_EXTERNAL_HANDOFF = "pending_external_handoff"
        const val PENDING_EXTERNAL_HANDOFF_ACCOUNT_DELETION = "account_deletion"
        const val KEY_ADDRESS_BOOK_REVISION = "address_book_revision"
        const val KEY_ADDRESS_BOOK_REPLACEMENT_BASE_REVISION =
            "address_book_replacement_base_revision"
        const val KEY_ADDRESS_BOOK_REPLACEMENT_MUTATION_ID =
            "address_book_replacement_mutation_id"
        const val KEY_ADDRESS_BOOK_DELETION_BASE_REVISION =
            "address_book_deletion_base_revision"
        const val KEY_ADDRESS_BOOK_DELETION_MUTATION_ID =
            "address_book_deletion_mutation_id"
    }
}
