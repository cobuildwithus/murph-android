package ai.withmurph.companion.auth

import ai.withmurph.companion.core.AuthProvider
import ai.withmurph.companion.core.LoginMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

class LoginCoordinator(private val auth: AuthProvider) {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun setMethod(method: LoginMethod) {
        if (_state.value.isInFlight) return
        _state.value = LoginUiState(
            method = method,
            phoneCountry = _state.value.phoneCountry,
        )
    }

    fun setPhoneCountry(country: CountryDialCode) {
        if (_state.value.isInFlight || _state.value.codeSent) return
        _state.update { current ->
            current.copy(phoneCountry = country, errorMessage = null)
        }
    }

    fun setDestination(destination: String) {
        if (_state.value.isInFlight) return
        _state.update { current ->
            current.copy(destination = destination, errorMessage = null)
        }
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

    suspend fun sendCode() {
        val snapshot = _state.value
        if (!snapshot.canSendCode) return
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
        _state.update { current ->
            current.copy(code = "", codeSent = false, errorMessage = null)
        }
    }

    fun reset() {
        _state.value = LoginUiState()
    }
}
