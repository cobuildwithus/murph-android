package ai.withmurph.companion.auth

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.withmurph.companion.app.AppConfig
import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.app.AppSession
import ai.withmurph.companion.core.*
import ai.withmurph.companion.storage.SharedPreferencesLocalState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HostedAuthInterruptedWriteTest {
    @Test fun interruptedFirstWriteRetiresFallbackAndReopensLoginThroughSessionTeardown() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.noBackupFilesDir, "auth-interruption-${UUID.randomUUID()}").apply { mkdirs() }
        val preferences = app.getSharedPreferences(directory.name, Context.MODE_PRIVATE)
        fun context(folder: File) = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = folder
        }
        try {
            // The real writer obtains its key before AtomicFile.startWrite().
            // Provision that key with the same owner, in a separate record.
            val seed = File(directory, "key-seed").apply { mkdirs() }
            HostedAuthCredentialStore(context(seed)).save(HostedAuthStoredState.SignedOut(null))
            val file = AtomicFile(File(directory, "hosted-auth-v1"))
            file.startWrite().use { output ->
                output.write("unfinished credential must never authorize".toByteArray())
                output.fd.sync()
            } // Simulate process interruption: neither finishWrite nor failWrite.
            assertFalse(file.baseFile.exists())
            assertTrue(File(file.baseFile.path + ".new").exists())

            val events = mutableListOf<String>()
            val store = HostedAuthCredentialStore(context(directory))
            val instant = Instant.parse("2026-09-10T12:00:00Z")
            val auth = HostedAuthService(object : HostedAuthServing {
                override suspend fun sendCode(method: LoginMethod, value: String) { events += "send" }
                override suspend fun verifyCode(method: LoginMethod, value: String, code: String): HostedAuthSession {
                    assertEquals(HostedAuthStoredState.SignedOut(null), HostedAuthCredentialStore(context(directory)).load())
                    assertTrue(file.baseFile.exists())
                    assertFalse(File(file.baseFile.path + ".new").exists())
                    events += "verify"
                    return HostedAuthSession("member-new", "murph_auth_v1." + "b".repeat(32), instant.plusSeconds(3600))
                }
                override suspend fun exchange(legacyCredential: String): HostedAuthSession = error("Legacy exchange must stay retired")
                override suspend fun renew(credential: String): HostedAuthSessionStatus = error("Fresh credential must not renew")
                override suspend fun revoke(credential: String) = error("No committed credential to revoke")
            }, store, { error("An orphan must never restore the SDK") }, { instant })
            val local = SharedPreferencesLocalState(preferences).apply { memberKey = "previous-local-member" }
            val health = RecoveryHealth(events)
            val api = object : CompanionApi {
                override suspend fun admitCompanion(memberKey: String, timeZone: String) {
                    events += "admit"
                    assertEquals("member-new", memberKey)
                    assertTrue(events.indexOf("health-sign-out") < events.indexOf("verify"))
                    throw CompanionApiException.Network // Stop after the actual admission boundary.
                }
                override suspend fun createJunctionSignInToken(memberKey: String, request: SignInTokenRequest): SignInTokenResponse = error("No health admission yet")
                override suspend fun fetchSyncStatus(memberKey: String, sourceProviderSlug: String): CompanionSyncStatus = error("No sync admission yet")
            }
            val session = AppSession(auth, api, health, localState = local, config = AppConfig(
                "https://auth-proof.invalid", AppEnvironment.Sandbox, "synthetic-client", "synthetic-client", "1", "test", "test",
            ))
            session.start()
            assertEquals(AppPhase.NeedsLogin, session.state.value.phase)
            assertNull(local.memberKey)
            assertEquals(HostedAuthStoredState.SignedOut(null), store.load())
            assertTrue(events.contains("health-sign-out"))
            assertFalse(events.contains("admit"))
            auth.sendCode(LoginMethod.Email, "member@example.test")
            auth.confirmCode(LoginMethod.Email, "member@example.test", "123456")
            session.didLogin()
            assertEquals(listOf("send", "verify", "admit"), events.filter { it != "health-sign-out" })
            assertTrue(events.lastIndexOf("health-sign-out") < events.indexOf("verify"))
            assertTrue(HostedAuthCredentialStore(context(directory)).load() is HostedAuthStoredState.Active)
            assertEquals(AuthSessionState.SignedIn("member-new", true), auth.currentState())
        } finally {
            preferences.edit().clear().commit()
            app.deleteSharedPreferences(directory.name)
            directory.deleteRecursively()
        }
    }

    private class RecoveryHealth(private val events: MutableList<String>) : HealthSyncing {
        private var signedIn = true
        override val totalResourceCount = 0
        override fun availability() = HealthConnectAvailability.Available
        override fun openHealthConnectIntent(): android.content.Intent? = null
        override fun isSignedIn() = signedIn
        override fun pauseAutomaticSync() {}
        override fun cancelActiveSync() {}
        override fun configure() {}
        override fun grantSnapshot(): HealthGrantSnapshot = HealthGrantSnapshot.Available(0, emptySet())
        override fun revokeUnpromotedSyncLaunch() {}
        override suspend fun identify(memberKey: String, authenticate: suspend () -> String) = error("No health admission yet")
        override suspend fun connectAfterPermissionRequest() = error("No connection intent")
        override suspend fun refreshPermissionState() {}
        override suspend fun syncAllGrantedResources(expectedMemberKey: String, beforeSyncPromotion: () -> Boolean): HealthSyncAttemptResult = error("No sync intent")
        override suspend fun revokeActiveSyncAuthorization() {}
        override suspend fun signOutSdk() { events += "health-sign-out"; signedIn = false }
    }
}
