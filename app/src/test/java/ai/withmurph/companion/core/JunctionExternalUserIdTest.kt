package ai.withmurph.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JunctionExternalUserIdTest {
    @Test
    fun derivationIsStableAndEnvironmentScoped() {
        val sandbox = JunctionExternalUserId.derive("did:privy:user_123", AppEnvironment.Sandbox)
        val production = JunctionExternalUserId.derive("did:privy:user_123", AppEnvironment.Production)

        assertEquals(
            "murph:f61b302361071a50214e15963ac844e671732b7416b750cffd973fa27385b241",
            sandbox,
        )
        assertNotEquals(sandbox, production)
    }
}
