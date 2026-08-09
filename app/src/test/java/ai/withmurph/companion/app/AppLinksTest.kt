package ai.withmurph.companion.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLinksTest {
    @Test
    fun failureRecoveryLinksUseThePublicSupportAccountAndLegalTargets() {
        assertEquals("mailto:support@withmurph.ai", AppLinks.Support)
        assertEquals("https://www.withmurph.ai/settings/data-privacy", AppLinks.AccountDeletion)
        assertEquals("https://www.withmurph.ai/legal/privacy", AppLinks.Privacy)
        assertEquals("https://www.withmurph.ai/legal/terms", AppLinks.Terms)
    }
}
