package ai.withmurph.companion.ui

import ai.withmurph.companion.app.AppPhase
import ai.withmurph.companion.core.InitialSetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MurphAppShellStateTest {
    @Test
    fun signedInFailureKeepsSupportAccountAndLegalActionsReachable() {
        val invoked = mutableListOf<String>()
        val actions = failureExternalActions(
            failure = AppPhase.Failed(message = "Account unavailable", canSignOut = true),
            onOpenSupport = { invoked += "support" },
            onDeleteAccount = { invoked += "delete" },
            onOpenPrivacy = { invoked += "privacy" },
            onOpenTerms = { invoked += "terms" },
        )

        assertEquals(
            listOf("Contact support", "Delete account", "Privacy Policy", "Terms"),
            actions.map { it.label },
        )
        actions.forEach { it.onClick() }
        assertEquals(listOf("support", "delete", "privacy", "terms"), invoked)
    }

    @Test
    fun signedOutFailureDoesNotOfferAccountSpecificActions() {
        val actions = failureExternalActions(
            failure = AppPhase.Failed(message = "Configuration unavailable"),
            onOpenSupport = {},
            onDeleteAccount = {},
            onOpenPrivacy = {},
            onOpenTerms = {},
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun reconnectReplacesHomeWhileKeepingTheSignedInTabBar() {
        val shell = readyAppShellState(
            selectedTab = AppTab.Home,
            initialSetupStep = InitialSetupStep.Complete,
            healthReconnectRequired = true,
        )

        assertEquals(AppTab.Home, shell.activeTab)
        assertTrue(shell.showsTabBar)
        assertTrue(shell.showsReconnect)
    }

    @Test
    fun reconnectDoesNotReplaceSettings() {
        val shell = readyAppShellState(
            selectedTab = AppTab.Settings,
            initialSetupStep = InitialSetupStep.Complete,
            healthReconnectRequired = true,
        )

        assertEquals(AppTab.Settings, shell.activeTab)
        assertTrue(shell.showsTabBar)
        assertFalse(shell.showsReconnect)
    }

    @Test
    fun friendlyNamesReconnectKeepsHomeNavigationAndReconnectGuidance() {
        val shell = readyAppShellState(
            selectedTab = AppTab.Home,
            initialSetupStep = InitialSetupStep.FriendlyNames,
            healthReconnectRequired = true,
        )

        assertEquals(AppTab.Home, shell.activeTab)
        assertTrue(shell.showsTabBar)
        assertTrue(shell.showsReconnect)
    }

    @Test
    fun friendlyNamesReconnectKeepsSettingsAvailable() {
        val shell = readyAppShellState(
            selectedTab = AppTab.Settings,
            initialSetupStep = InitialSetupStep.FriendlyNames,
            healthReconnectRequired = true,
        )

        assertEquals(AppTab.Settings, shell.activeTab)
        assertTrue(shell.showsTabBar)
        assertFalse(shell.showsReconnect)
    }

    @Test
    fun friendlyNamesWithoutReconnectRemainsHomeOnlyAndTabless() {
        val shell = readyAppShellState(
            selectedTab = AppTab.Settings,
            initialSetupStep = InitialSetupStep.FriendlyNames,
            healthReconnectRequired = false,
        )

        assertEquals(AppTab.Home, shell.activeTab)
        assertFalse(shell.showsTabBar)
        assertFalse(shell.showsReconnect)
    }
}
