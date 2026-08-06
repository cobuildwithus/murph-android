package ai.withmurph.companion.ui

import ai.withmurph.companion.core.InitialSetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MurphAppShellStateTest {
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
}
