package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.AuthSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MessagingLinkException(val reason: Reason) : Exception() {
    enum class Reason(val message: String) {
        InvalidNumber("Enter a valid phone number, including its country code."),
        ContactInUse("That account is already linked to another member. Try a different one."),
        RateLimited("Too many attempts. Wait a minute and try again."),
        InvalidCode("That code is invalid or expired. Try again or request a new code."),
        FreshLogin("Sign out and sign in again to confirm it’s you, then connect your account."),
        Approval("Open account settings to approve this account change."),
        ExpiredLink("That Telegram link expired. Connect Telegram again."),
        Unavailable("We couldn’t connect your account. Check your connection and try again."),
    }
}

class TelegramMessagingLink(val token: String, val url: String) {
    override fun toString() = "TelegramMessagingLink(<redacted>)"
}

enum class MessagingStage { Phone, Code, Telegram, Connected }
data class MessagingSetupState(
    val stage: MessagingStage = MessagingStage.Phone,
    val phone: String = "",
    val country: CountryDialCode = CountryDialCode.Default,
    val code: String = "",
    val busy: Boolean = false,
    val telegramPending: Boolean = false,
    val displayPhone: String = "",
    val error: String? = null,
) {
    val offersAccountSettings get() = error == MessagingLinkException.Reason.ContactInUse.message || error == MessagingLinkException.Reason.Approval.message
    val canVerify get() = !busy && Regex("^[0-9]{6}$").matches(code)
    override fun toString() = "MessagingSetupState(stage=$stage, busy=$busy)"
}

/** Memory-only linking flow, bound to the member who started it. */
class MessagingSetupCoordinator(private val auth: AuthProvider, private val api: HostedAuthServing) {
    private val mutableState = MutableStateFlow(MessagingSetupState())
    val state = mutableState.asStateFlow()
    private var owner: String? = null
    private var sentPhone: String? = null
    private var telegramToken: String? = null
    private var telegramUrl: String? = null
    private var telegramProof: String? = null
    private var revision = 0L

    fun setPhone(value: String) { if (!state.value.busy) mutableState.value = state.value.copy(phone = value, error = null) }
    fun setCountry(value: CountryDialCode) { if (!state.value.busy) mutableState.value = state.value.copy(country = value, error = null) }
    fun setCode(value: String) { if (!state.value.busy) mutableState.value = state.value.copy(code = value.filter { it in '0'..'9' }.take(6), error = null) }
    fun reset() { revision++; owner = null; sentPhone = null; telegramToken = null; telegramUrl = null; telegramProof = null; mutableState.value = MessagingSetupState() }
    fun changeNumber() { if (!state.value.busy) { sentPhone = null; telegramToken = null; telegramUrl = null; telegramProof = null; mutableState.value = state.value.copy(stage = MessagingStage.Phone, code = "", error = null, telegramPending = false, displayPhone = "") } }

    suspend fun sendCode(): Boolean {
        val target = if (state.value.stage == MessagingStage.Code) sentPhone.orEmpty() else state.value.country.compose(state.value.phone)
        if (!CountryDialCode.isPlausibleE164(target)) {
            mutableState.value = state.value.copy(error = MessagingLinkException.Reason.InvalidNumber.message)
            return false
        }
        return perform { credential, current ->
            api.sendMessagingPhoneCode(target, credential)
            requireCurrent(current)
            sentPhone = target
            mutableState.value = state.value.copy(stage = MessagingStage.Code, code = "", displayPhone = target)
        }
    }

    suspend fun verifyCode(): Boolean {
        val target = sentPhone ?: return false
        if (!state.value.canVerify) return false
        val code = state.value.code
        return perform { credential, current ->
            api.verifyMessagingPhoneCode(target, code, credential)
            requireCurrent(current)
            sentPhone = null
            mutableState.value = state.value.copy(stage = MessagingStage.Connected, phone = "", code = "")
        }
    }

    fun selectTelegram() {
        if (state.value.busy) return
        changeNumber()
        mutableState.value = state.value.copy(stage = MessagingStage.Telegram)
    }

    suspend fun startTelegram(): String? {
        telegramUrl?.let { return it }
        var url: String? = null
        val done = perform { credential, current ->
            val link = api.startMessagingTelegram(credential)
            requireCurrent(current)
            telegramToken = link.token
            telegramUrl = link.url
            sentPhone = null
            mutableState.value = state.value.copy(stage = MessagingStage.Telegram, code = "", telegramPending = true)
            url = link.url
        }
        return if (done) url else null
    }

    suspend fun checkTelegram(): Boolean {
        val token = telegramToken ?: return false
        val proof = telegramProof
        var linked = false
        val done = perform { credential, current ->
            linked = api.completeMessagingTelegram(token, proof, credential)
            requireCurrent(current)
            if (linked) {
                telegramToken = null
                telegramUrl = null
                telegramProof = null
                mutableState.value = state.value.copy(stage = MessagingStage.Connected, phone = "")
            } else mutableState.value = state.value.copy(telegramPending = true)
        }
        if (done && !linked && proof != telegramProof) return checkTelegram()
        return done && linked
    }

    fun acceptTelegramReturn(url: String): Boolean {
        val token = telegramToken ?: return false
        if (state.value.stage != MessagingStage.Telegram || url.length > 200) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "murph-messaging" || uri.host != "telegram" || uri.path != "/complete" ||
            uri.userInfo != null || uri.port != -1 || uri.query != null) return false
        val fields = Regex("^token=([A-Za-z0-9_-]{43})&proof=([A-Za-z0-9_-]{43})$").matchEntire(uri.rawFragment ?: "") ?: return false
        if (fields.groupValues[1] != token) return false
        telegramProof = fields.groupValues[2]
        return true
    }

    private suspend fun requireCurrent(current: Long) {
        if (current != revision || (auth.currentState() as? AuthSessionState.SignedIn)?.memberKey != owner) throw CancellationException()
    }

    private suspend fun perform(action: suspend (String, Long) -> Unit): Boolean {
        if (state.value.busy) return false
        val current = revision
        mutableState.value = state.value.copy(busy = true, error = null)
        try {
            val member = (auth.currentState() as? AuthSessionState.SignedIn)?.memberKey
                ?: throw MessagingLinkException(MessagingLinkException.Reason.FreshLogin)
            if (owner == null) owner = member
            if (owner != member) throw MessagingLinkException(MessagingLinkException.Reason.FreshLogin)
            val credential = auth.identityTokenForMember(member)
            requireCurrent(current)
            action(credential, current)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current == revision) {
                val reason = (error as? MessagingLinkException)?.reason ?: MessagingLinkException.Reason.Unavailable
                if (reason == MessagingLinkException.Reason.ExpiredLink) { telegramToken = null; telegramUrl = null; telegramProof = null }
                mutableState.value = state.value.copy(error = reason.message, telegramPending = telegramToken != null)
            }
            return false
        } finally {
            if (current == revision) mutableState.value = state.value.copy(busy = false)
        }
    }
}
