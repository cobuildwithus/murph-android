package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class LoginUiState(
    val method: LoginMethod = LoginMethod.Phone,
    val destination: String = "",
    val phoneCountry: CountryDialCode = CountryDialCode.Default,
    val code: String = "",
    val codeSent: Boolean = false,
    val isInFlight: Boolean = false,
    val errorMessage: String? = null,
) {
    val normalizedDestination: String
        get() = when (method) {
            LoginMethod.Phone -> phoneCountry.compose(destination)
            LoginMethod.Email -> destination.trim()
        }

    val canSendCode: Boolean
        get() = !codeSent && !isInFlight && when (method) {
            LoginMethod.Phone -> CountryDialCode.isPlausibleE164(normalizedDestination)
            LoginMethod.Email -> EMAIL_PATTERN.matches(normalizedDestination)
        }

    val canConfirmCode: Boolean
        get() = codeSent && !isInFlight && code.count(Char::isDigit) == 6

    private companion object {
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

class LoginCoordinator(
    private val auth: AuthProvider,
    private val localeRegion: String? = Locale.getDefault().country,
) {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var automaticSendConsumed = false
    private var destinationRevision = 0L
    private var pendingAutomaticSendRevision: Long? = null

    fun setMethod(method: LoginMethod) {
        if (_state.value.isInFlight) return
        advanceDestinationRevision()
        _state.value = LoginUiState(
            method = method,
            phoneCountry = _state.value.phoneCountry,
        )
    }

    fun setPhoneCountry(country: CountryDialCode) {
        if (_state.value.isInFlight || _state.value.codeSent) return
        if (_state.value.phoneCountry == country) return
        advanceDestinationRevision()
        _state.update { current ->
            current.copy(phoneCountry = country, errorMessage = null)
        }
    }

    /** Returns true only when this whole-field update authorizes one automatic +1 send. */
    fun setDestination(destination: String): Boolean {
        val current = _state.value
        if (current.isInFlight) return false

        val compactInternational = if (current.method == LoginMethod.Phone) {
            CountryDialCode.compactExplicitInternational(destination)
        } else {
            null
        }
        val matchedCountry = compactInternational?.let(CountryDialCode::longestCuratedMatch)
        val nextCountry = if (
            matchedCountry != null && current.phoneCountry.dialCode != matchedCountry.dialCode
        ) {
            matchedCountry
        } else {
            current.phoneCountry
        }
        val nextDestination = when {
            compactInternational == null -> destination
            matchedCountry == null -> compactInternational
            else -> compactInternational.drop(matchedCountry.dialCode.length)
        }
        val destinationChanged =
            nextDestination != current.destination || nextCountry != current.phoneCountry
        if (destinationChanged) advanceDestinationRevision()

        val next = current.copy(
            destination = nextDestination,
            phoneCountry = nextCountry,
            errorMessage = null,
        )
        _state.value = next

        val automaticTarget = next.normalizedDestination
        val shouldAutomaticallySend = current.destination.isEmpty() &&
            next.method == LoginMethod.Phone &&
            next.canSendCode &&
            !automaticSendConsumed &&
            isConservativeAutomaticFill(
                input = destination,
                compactInternational = compactInternational,
                state = next,
            )
        if (shouldAutomaticallySend) {
            pendingAutomaticSendRevision = destinationRevision
        }
        return shouldAutomaticallySend
    }

    fun setCode(code: String) {
        if (_state.value.isInFlight) return
        _state.update { current ->
            current.copy(
                code = code.filter(Char::isDigit).take(6),
                errorMessage = null,
            )
        }
    }

    suspend fun sendCode(fromAutomaticPhoneFill: Boolean = false) {
        if (fromAutomaticPhoneFill) {
            if (pendingAutomaticSendRevision != destinationRevision) return
            pendingAutomaticSendRevision = null
        } else {
            pendingAutomaticSendRevision = null
        }
        val snapshot = _state.value
        if (!snapshot.canSendCode) return
        automaticSendConsumed = true
        _state.update { it.copy(isInFlight = true, errorMessage = null) }
        try {
            auth.sendCode(snapshot.method, snapshot.normalizedDestination)
            _state.update { current ->
                current.copy(codeSent = true, code = "")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { current ->
                current.copy(
                    errorMessage = when (snapshot.method) {
                        LoginMethod.Phone -> "We couldn't send a code to that number. Check it and try again."
                        LoginMethod.Email -> "We couldn't send a code to that email. Check it and try again."
                    },
                )
            }
        } finally {
            _state.update { it.copy(isInFlight = false) }
        }
    }

    suspend fun resendCode() {
        val snapshot = _state.value
        if (!snapshot.codeSent || snapshot.isInFlight) return
        _state.update { it.copy(codeSent = false, code = "") }
        sendCode()
    }

    suspend fun confirmCode(): Boolean {
        val snapshot = _state.value
        if (!snapshot.canConfirmCode) return false
        _state.update { it.copy(isInFlight = true, errorMessage = null) }
        return try {
            auth.confirmCode(
                snapshot.method,
                snapshot.normalizedDestination,
                snapshot.code,
            )
            _state.value = LoginUiState(
                method = snapshot.method,
                phoneCountry = snapshot.phoneCountry,
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { current ->
                current.copy(
                    errorMessage = "That code didn't work. Try again or send a new one.",
                )
            }
            false
        } finally {
            _state.update { it.copy(isInFlight = false) }
        }
    }

    fun changeDestination() {
        if (_state.value.isInFlight) return
        advanceDestinationRevision()
        _state.update { current ->
            current.copy(code = "", codeSent = false, errorMessage = null)
        }
    }

    fun reset() {
        advanceDestinationRevision()
        automaticSendConsumed = false
        _state.value = LoginUiState()
    }

    private fun isConservativeAutomaticFill(
        input: String,
        compactInternational: String?,
        state: LoginUiState,
    ): Boolean {
        val target = state.normalizedDestination
        if (compactInternational != null) {
            return compactInternational == target &&
                compactInternational.startsWith("+1") &&
                compactInternational.drop(1).length == 11
        }

        val nationalDigits = CountryDialCode.compactNational(input) ?: return false
        val isNanpLocale = localeRegion.equals("US", ignoreCase = true) ||
            localeRegion.equals("CA", ignoreCase = true)
        return isNanpLocale &&
            state.phoneCountry.dialCode == "+1" &&
            nationalDigits.length == 10 &&
            target == "+1$nationalDigits"
    }

    private fun advanceDestinationRevision() {
        destinationRevision += 1
        pendingAutomaticSendRevision = null
    }
}
