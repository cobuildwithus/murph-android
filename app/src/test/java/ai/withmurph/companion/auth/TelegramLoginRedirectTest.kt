package ai.withmurph.companion.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramLoginRedirectTest {
    private val play = "44:E0:3E:46:B1:DF:A9:0F:9B:86:BD:25:F1:AE:DE:3F:96:5B:4C:DB:20:95:C9:06:18:CD:5E:BA:7A:DC:21:3F"
    private val upload = "0E:3B:C0:67:49:13:B0:7C:9C:0C:2F:7A:78:33:E4:2C:40:53:28:F6:AC:EB:C9:DB:45:7B:66:C2:83:C2:5C:39"

    @Test fun playInstalledBuildUsesPlayRegistration() {
        assertEquals("https://app397543190-login.tg.dev/tglogin", TelegramLoginRedirect.forSigners("ai.withmurph.app", listOf(play)))
    }

    @Test fun locallySignedBuildUsesUploadRegistration() {
        assertEquals("https://app1452659770-login.tg.dev/tglogin", TelegramLoginRedirect.forSigners("ai.withmurph.app", listOf(upload)))
    }

    @Test fun unknownOrMissingSignerFailsClosed() {
        assertNull(TelegramLoginRedirect.forSigners("ai.withmurph.app", listOf("unknown")))
        assertNull(TelegramLoginRedirect.forSigners("ai.withmurph.app", emptyList()))
    }

    @Test fun multipleSignersFailClosedRegardlessOfOrder() {
        assertNull(TelegramLoginRedirect.forSigners("ai.withmurph.app", listOf(play, upload)))
        assertNull(TelegramLoginRedirect.forSigners("ai.withmurph.app", listOf(upload, play)))
    }

    @Test fun unregisteredPackageFailsClosedEvenWithKnownSigner() {
        assertNull(TelegramLoginRedirect.forSigners("ai.withmurph.app.debug", listOf(upload)))
    }
}
