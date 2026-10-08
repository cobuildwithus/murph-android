package ai.withmurph.companion.auth

import android.app.Activity
import android.net.Uri
import android.content.pm.PackageManager
import java.security.MessageDigest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.telegram.login.TelegramLogin

/** Activity-owned, memory-only adapter around the unmodified official SDK. */
class TelegramLoginService(private val activity: Activity, private val scope: CoroutineScope) {
    private var redirectUri: Uri? = null
    private var pending: CancellableContinuation<String>? = null
    private var attempt = 0L
    private var receivedCallback = false

    suspend fun login(clientId: String): String {
        if (pending != null) throw unavailable()
        val redirect = installedRedirect() ?: throw unavailable()
        redirectUri = Uri.parse(redirect)
        val current = ++attempt
        receivedCallback = false
        TelegramLogin.init(clientId, redirect, listOf("openid", "profile", "telegram:bot_access"))
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
            if (attempt == current) { pending = null; redirectUri = null }
        }
    }

    private fun installedRedirect(): String? = runCatching {
        val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        // Only current installed signers qualify; a historical key must not select
        // an App Link registration for a differently signed installed package.
        val fingerprints = info.signingInfo?.apkContentsSigners?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }.orEmpty()
        TelegramLoginRedirect.forSigners(activity.packageName, fingerprints)
    }.getOrNull()

    fun handle(uri: Uri) {
        if (pending == null || receivedCallback || uri.scheme != "https" || uri.host != redirectUri?.host ||
            uri.path != "/tglogin" || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return
        receivedCallback = true
        if (uri.getQueryParameter("error") == "access_denied") { cancel(); return }
        val current = attempt
        TelegramLogin.handleLoginResponse(uri,
            onSuccess = { if (attempt == current) finish(Result.success(it.idToken)) },
            onError = { if (attempt == current) finish(Result.failure(unavailable())) },
        )
    }

    fun cancel() = finish(Result.failure(MessagingLinkException(MessagingLinkException.Reason.TelegramCancelled)))

    private fun finish(result: Result<String>) {
        val continuation = pending
        pending = null
        if (continuation?.isActive == true) continuation.resumeWith(result)
    }

    private fun unavailable() = MessagingLinkException(MessagingLinkException.Reason.TelegramUnavailable)
}
