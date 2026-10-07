package ai.withmurph.companion.auth

import android.app.Activity
import android.net.Uri
import ai.withmurph.companion.BuildConfig
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.telegram.login.TelegramLogin

/** Activity-owned, memory-only adapter around the unmodified official SDK. */
class TelegramLoginService(private val activity: Activity, private val scope: CoroutineScope) {
    private var pending: CancellableContinuation<String>? = null
    private var attempt = 0L
    private var leftApp = false
    private var receivedCallback = false

    suspend fun login(clientId: String): String {
        val host = BuildConfig.TELEGRAM_REDIRECT_HOST
        if (host.isBlank() || pending != null) throw unavailable()
        val current = ++attempt
        leftApp = false
        receivedCallback = false
        TelegramLogin.init(clientId, "https://$host/tglogin", listOf("openid", "profile", "telegram:bot_access"))
        try {
            return withTimeout(300_000) {
                suspendCancellableCoroutine { continuation ->
                    pending = continuation
                    continuation.invokeOnCancellation {
                        scope.launch { if (attempt == current) pending = null }
                    }
                    TelegramLogin.startLogin(activity)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw unavailable()
        } finally {
            if (attempt == current) pending = null
        }
    }

    fun handle(uri: Uri) {
        if (pending == null || receivedCallback || uri.scheme != "https" || uri.host != BuildConfig.TELEGRAM_REDIRECT_HOST ||
            uri.path != "/tglogin" || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return
        receivedCallback = true
        if (uri.getQueryParameter("error") == "access_denied") { cancel(); return }
        val current = attempt
        TelegramLogin.handleLoginResponse(uri,
            onSuccess = { if (attempt == current) finish(Result.success(it.idToken)) },
            onError = { if (attempt == current) finish(Result.failure(unavailable())) },
        )
    }

    fun setActive(active: Boolean) {
        if (pending == null) return
        if (!active) { leftApp = true; return }
        if (!leftApp) return
        val current = attempt
        scope.launch {
            // App-link dispatch can follow onResume. Do not cancel an accepted
            // callback while the SDK exchanges its code using its PKCE verifier.
            delay(1_000)
            if (attempt == current && !receivedCallback) cancel()
        }
    }

    fun cancel() = finish(Result.failure(MessagingLinkException(MessagingLinkException.Reason.TelegramCancelled)))

    private fun finish(result: Result<String>) {
        val continuation = pending
        pending = null
        if (continuation?.isActive == true) continuation.resumeWith(result)
    }

    private fun unavailable() = MessagingLinkException(MessagingLinkException.Reason.TelegramUnavailable)
}
