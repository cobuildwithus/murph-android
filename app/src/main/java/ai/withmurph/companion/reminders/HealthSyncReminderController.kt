package ai.withmurph.companion.reminders

import ai.withmurph.companion.MainActivity
import ai.withmurph.companion.R
import ai.withmurph.companion.core.HealthSyncReminderDeadline
import ai.withmurph.companion.core.HealthSyncReminderLifecycle
import ai.withmurph.companion.core.HealthSyncState
import ai.withmurph.companion.core.InstantValue
import ai.withmurph.companion.core.LocalState
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Owns one optional, inexact Android reminder. It never starts health work.
 * The alarm is admitted only by member-scoped local state and a backend-confirmed
 * receipt (or the explicit setup boundary before the first receipt).
 */
class HealthSyncReminderController internal constructor(
    private val context: Context,
    private val localState: LocalState,
    private val alarmManager: AlarmManager,
    private val notificationManager: NotificationManager,
    private val elapsedRealtime: () -> Long,
    private val bootCount: () -> Int,
) : HealthSyncReminderLifecycle {
    constructor(context: Context, localState: LocalState) : this(
        context = context.applicationContext,
        localState = localState,
        alarmManager = context.getSystemService(AlarmManager::class.java),
        notificationManager = context.getSystemService(NotificationManager::class.java),
        elapsedRealtime = SystemClock::elapsedRealtime,
        bootCount = {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                0,
            ).coerceAtLeast(0)
        },
    )

    fun notificationsAllowed(): Boolean =
        notificationManager.areNotificationsEnabled() &&
            notificationChannelAllowsReminders(
                notificationManager.getNotificationChannel(CHANNEL_ID)?.importance,
            ) &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                )

    fun prepareNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.health_sync_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(
                    R.string.health_sync_reminder_channel_description,
                )
            },
        )
    }

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    private fun scheduleIfEligible(): Boolean {
        val admission = activeReminderAdmission(localState) ?: run {
            cancel()
            return false
        }
        if (!notificationsAllowed()) {
            cancel()
            return false
        }
        val deadline = persistDeadline(admission) ?: run {
            cancel()
            return false
        }
        if (activeReminderAdmission(localState)?.basisToken != admission.basisToken) {
            cancel()
            return false
        }
        return runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadline.triggerElapsedRealtimeMillis,
                deliveryPendingIntent(admission.basisToken),
            )
        }.isSuccess
    }

    override fun didEnterForeground() {
        HealthSyncReminderProcessState.didEnterForeground()
        cancel()
    }

    override fun didEnterBackground() {
        HealthSyncReminderProcessState.didEnterBackground()
        scheduleIfEligible()
    }

    override fun initialDeadline(memberKey: String): HealthSyncReminderDeadline? {
        val admission = reminderEvidenceAdmission(localState)
            ?.takeIf { it.memberKey == memberKey }
            ?: return null
        val deadline = deadlineFor(admission)
        return deadline.takeIf {
            reminderEvidenceAdmission(localState)?.basisToken == admission.basisToken
        }
    }

    override fun deadlineForSetupAuthorization(
        memberKey: String,
        requestedAt: InstantValue,
    ): HealthSyncReminderDeadline? = setupAuthorizationReminderAdmission(
        localState = localState,
        memberKey = memberKey,
        requestedAt = requestedAt,
    )?.let(::deadlineFor)

    override fun refreshSchedule(freshBackendStatus: Boolean) {
        if (freshBackendStatus) {
            activeReminderAdmission(localState)?.let { admission ->
                persistDeadline(admission, mayReanchorAfterBoot = true)
            }
        }
        if (!HealthSyncReminderProcessState.isForeground) scheduleIfEligible()
    }

    internal fun postIfEligible(scheduledBasisToken: String) {
        forgetScheduledDelivery()
        when (
            reminderDeliveryAction(
                localState = localState,
                scheduledBasisToken = scheduledBasisToken,
                appInForeground = HealthSyncReminderProcessState.isForeground,
            )
        ) {
            HealthSyncReminderDeliveryAction.Cancel -> {
                notificationManager.cancel(NOTIFICATION_ID)
                return
            }
            HealthSyncReminderDeliveryAction.Reschedule -> {
                scheduleIfEligible()
                return
            }
            HealthSyncReminderDeliveryAction.Post -> Unit
        }
        if (!notificationsAllowed()) {
            cancel()
            return
        }

        prepareNotificationChannel()
        val body = context.getString(R.string.health_sync_reminder_notification_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_reminder)
            .setContentTitle(context.getString(R.string.health_sync_reminder_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(settingsPendingIntent(UUID.randomUUID().toString()))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun persistDeadline(
        admission: HealthSyncReminderAdmission,
        mayReanchorAfterBoot: Boolean = false,
    ): HealthSyncReminderDeadline? {
        val deadline = deadlineFor(admission, mayReanchorAfterBoot)
        if (
            deadline != localState.healthSyncReminderDeadline &&
            !localState.persistHealthSyncReminderDeadline(admission.memberKey, deadline)
        ) {
            return null
        }
        return deadline.takeIf {
            reminderEvidenceAdmission(localState)?.basisToken == admission.basisToken
        }
    }

    private fun deadlineFor(
        admission: HealthSyncReminderAdmission,
        mayReanchorAfterBoot: Boolean = false,
    ): HealthSyncReminderDeadline = retainedHealthSyncReminderDeadline(
        basisToken = admission.basisToken,
        bootCount = bootCount(),
        elapsedRealtimeMillis = elapsedRealtime(),
        remaining = admission.remaining,
        existing = localState.healthSyncReminderDeadline,
        mayReanchorAfterBoot = mayReanchorAfterBoot,
    )

    override fun cancel() {
        forgetScheduledDelivery()
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun deliveryPendingIntent(basisToken: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            DELIVERY_REQUEST_CODE,
            deliveryIntent()
                .putExtra(EXTRA_BASIS_TOKEN, basisToken),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun existingDeliveryPendingIntent(): PendingIntent? = PendingIntent.getBroadcast(
        context,
        DELIVERY_REQUEST_CODE,
        deliveryIntent(),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun deliveryIntent(): Intent =
        Intent(context, HealthSyncReminderReceiver::class.java).setAction(ACTION_DELIVER)

    private fun forgetScheduledDelivery() {
        existingDeliveryPendingIntent()?.let { pendingIntent ->
            runCatching { alarmManager.cancel(pendingIntent) }
            pendingIntent.cancel()
        }
    }

    private fun settingsPendingIntent(deliveryId: String): PendingIntent = PendingIntent.getActivity(
        context,
        SETTINGS_REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_SETTINGS)
            .putExtra(EXTRA_SETTINGS_DELIVERY_ID, deliveryId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_OPEN_SETTINGS =
            "ai.withmurph.companion.action.OPEN_HEALTH_SYNC_REMINDER_SETTINGS"
        internal const val ACTION_DELIVER =
            "ai.withmurph.companion.action.DELIVER_HEALTH_SYNC_REMINDER"
        internal const val EXTRA_BASIS_TOKEN =
            "ai.withmurph.companion.extra.HEALTH_REMINDER_BASIS"
        internal const val EXTRA_SETTINGS_DELIVERY_ID =
            "ai.withmurph.companion.extra.HEALTH_REMINDER_SETTINGS_DELIVERY"
        private const val CHANNEL_ID = "health_sync_reminders"
        private const val NOTIFICATION_ID = 4_201
        private const val DELIVERY_REQUEST_CODE = 4_201
        private const val SETTINGS_REQUEST_CODE = 4_202
    }
}

class HealthSyncReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HealthSyncReminderController.ACTION_DELIVER) return
        val scheduledBasisToken = intent.getStringExtra(
            HealthSyncReminderController.EXTRA_BASIS_TOKEN,
        ) ?: return
        HealthSyncReminderController(
            context = context,
            localState = SharedPreferencesLocalState(context),
        ).postIfEligible(scheduledBasisToken)
    }
}

internal object HealthSyncReminderProcessState {
    var isForeground: Boolean = false
        private set

    fun didEnterForeground() {
        isForeground = true
    }

    fun didEnterBackground() {
        isForeground = false
    }
}

internal data class HealthSyncReminderAdmission(
    val memberKey: String,
    val basisToken: String,
    val remaining: Duration,
)

internal enum class HealthSyncReminderDeliveryAction {
    Cancel,
    Reschedule,
    Post,
}

internal fun activeReminderAdmission(localState: LocalState): HealthSyncReminderAdmission? {
    val admission = reminderEvidenceAdmission(localState) ?: return null
    if (!localState.isHealthSyncReminderEnabled(admission.memberKey)) return null
    return admission
}

internal fun reminderEvidenceAdmission(localState: LocalState): HealthSyncReminderAdmission? {
    if (localState.signOutPending) return null
    val memberKey = localState.memberKey ?: return null
    val requestedAtValue = localState.healthAccessRequestedAt ?: return null
    val requestedAt = Instant.ofEpochMilli(requestedAtValue.epochMilliseconds)
    val receivedAt = localState.lastKnownDataReceivedAt?.epochMilliseconds
        ?.let(Instant::ofEpochMilli)
    val qualifyingReceipt = receivedAt?.takeUnless { it.isBefore(requestedAt) }
    val observedAtValue = localState.lastKnownStatusObservedAt ?: return null
    val observedAt = Instant.ofEpochMilli(observedAtValue.epochMilliseconds)
    val remaining = HealthSyncState.attentionRemaining(
        requestedAt = requestedAt,
        lastDataReceivedAt = receivedAt,
        statusObservedAt = observedAt,
    ) ?: return null
    return HealthSyncReminderAdmission(
        memberKey = memberKey,
        basisToken = reminderBasisToken(
            installationId = localState.installationId,
            memberKey = memberKey,
            setupAtEpochMilliseconds = requestedAtValue.epochMilliseconds,
            receiptAtEpochMilliseconds = qualifyingReceipt?.toEpochMilli(),
        ),
        remaining = remaining,
    )
}

internal fun setupAuthorizationReminderAdmission(
    localState: LocalState,
    memberKey: String,
    requestedAt: InstantValue,
): HealthSyncReminderAdmission? {
    if (!localState.isHealthSyncReminderEnabled(memberKey)) return null
    val setupAt = Instant.ofEpochMilli(requestedAt.epochMilliseconds)
    val remaining = HealthSyncState.attentionRemaining(
        requestedAt = setupAt,
        lastDataReceivedAt = null,
        statusObservedAt = setupAt,
    ) ?: return null
    return HealthSyncReminderAdmission(
        memberKey = memberKey,
        basisToken = reminderBasisToken(
            installationId = localState.installationId,
            memberKey = memberKey,
            setupAtEpochMilliseconds = requestedAt.epochMilliseconds,
            receiptAtEpochMilliseconds = null,
        ),
        remaining = remaining,
    )
}

internal fun reminderFenceMatches(
    localState: LocalState,
    scheduledBasisToken: String,
): Boolean = activeReminderAdmission(localState)?.basisToken == scheduledBasisToken

internal fun reminderDeliveryAction(
    localState: LocalState,
    scheduledBasisToken: String,
    appInForeground: Boolean = false,
): HealthSyncReminderDeliveryAction {
    if (appInForeground) return HealthSyncReminderDeliveryAction.Cancel
    val activeBasisToken = activeReminderAdmission(localState)?.basisToken
        ?: return HealthSyncReminderDeliveryAction.Cancel
    return if (activeBasisToken == scheduledBasisToken) {
        HealthSyncReminderDeliveryAction.Post
    } else {
        HealthSyncReminderDeliveryAction.Reschedule
    }
}

internal fun reminderBasisToken(
    installationId: String,
    memberKey: String,
    setupAtEpochMilliseconds: Long,
    receiptAtEpochMilliseconds: Long?,
): String {
    val basis = buildString {
        append(installationId)
        append('|')
        append(memberKey)
        append('|')
        append(setupAtEpochMilliseconds)
        append('|')
        append(receiptAtEpochMilliseconds ?: "none")
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(basis.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun notificationChannelAllowsReminders(channelImportance: Int?): Boolean =
    channelImportance == null || channelImportance != NotificationManager.IMPORTANCE_NONE

internal fun elapsedRealtimeTriggerAt(
    elapsedRealtimeMillis: Long,
    remaining: Duration,
    minimumDelayMillis: Long = MINIMUM_REENTRY_DELAY_MILLIS,
): Long {
    val remainingMillis = runCatching { remaining.toMillis() }.getOrElse { Long.MAX_VALUE }
        .coerceAtLeast(0L)
    val delayMillis = maxOf(minimumDelayMillis, remainingMillis)
    return if (elapsedRealtimeMillis > Long.MAX_VALUE - delayMillis) {
        Long.MAX_VALUE
    } else {
        elapsedRealtimeMillis + delayMillis
    }
}

internal fun retainedHealthSyncReminderDeadline(
    basisToken: String,
    bootCount: Int,
    elapsedRealtimeMillis: Long,
    remaining: Duration,
    existing: HealthSyncReminderDeadline?,
    mayReanchorAfterBoot: Boolean = false,
): HealthSyncReminderDeadline {
    val candidateTrigger = elapsedRealtimeTriggerAt(
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        remaining = remaining,
    )
    val existingForBasis = existing?.takeIf { it.basisToken == basisToken }
    val retainedTrigger = existingForBasis
        ?.takeIf { it.bootCount == bootCount }
        ?.triggerElapsedRealtimeMillis
    val selectedTrigger = when {
        existingForBasis != null && retainedTrigger == null && !mayReanchorAfterBoot ->
            elapsedRealtimeTriggerAt(
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                remaining = Duration.ZERO,
            )
        retainedTrigger == null -> candidateTrigger
        retainedTrigger > elapsedRealtimeMillis -> minOf(retainedTrigger, candidateTrigger)
        else -> elapsedRealtimeTriggerAt(
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            remaining = Duration.ZERO,
        )
    }
    return HealthSyncReminderDeadline(
        basisToken = basisToken,
        bootCount = bootCount,
        triggerElapsedRealtimeMillis = selectedTrigger,
    )
}

private const val MINIMUM_REENTRY_DELAY_MILLIS = 15 * 60 * 1_000L
