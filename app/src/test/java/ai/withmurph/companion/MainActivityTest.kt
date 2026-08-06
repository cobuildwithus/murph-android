package ai.withmurph.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTest {
    @Test
    fun throwingHistoryPermissionProbeCompletesWithoutLaunching() {
        var launchCalls = 0
        var completionCalls = 0

        launchHealthHistoryPermissionsOrComplete(
            supportedPermissions = { throw IllegalStateException("probe unavailable") },
            launchPermissions = { launchCalls += 1 },
            complete = { completionCalls += 1 },
        )

        assertEquals(0, launchCalls)
        assertEquals(1, completionCalls)
    }

    @Test
    fun emptyHistoryPermissionsCompleteWithoutLaunching() {
        var launchCalls = 0
        var completionCalls = 0

        launchHealthHistoryPermissionsOrComplete(
            supportedPermissions = { emptySet() },
            launchPermissions = { launchCalls += 1 },
            complete = { completionCalls += 1 },
        )

        assertEquals(0, launchCalls)
        assertEquals(1, completionCalls)
    }

    @Test
    fun throwingHistoryPermissionLauncherCompletesExactlyOnce() {
        var completionCalls = 0

        launchHealthHistoryPermissionsOrComplete(
            supportedPermissions = { setOf("history") },
            launchPermissions = { throw IllegalStateException("launcher unavailable") },
            complete = { completionCalls += 1 },
        )

        assertEquals(1, completionCalls)
    }

    @Test
    fun supportedHistoryPermissionLaunchesWithoutCompleting() {
        var launchedPermissions = emptySet<String>()
        var completionCalls = 0

        launchHealthHistoryPermissionsOrComplete(
            supportedPermissions = { setOf("history") },
            launchPermissions = { launchedPermissions = it },
            complete = { completionCalls += 1 },
        )

        assertEquals(setOf("history"), launchedPermissions)
        assertEquals(0, completionCalls)
    }
}
