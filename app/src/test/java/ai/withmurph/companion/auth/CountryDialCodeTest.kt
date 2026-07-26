package ai.withmurph.companion.auth

import ai.withmurph.companion.core.LoginMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryDialCodeTest {
    @Test
    fun `national phone input composes with the selected country`() {
        val country = CountryDialCode("US", "+1")

        assertEquals("+15555550100", country.compose("(555) 555-0100"))
    }

    @Test
    fun `explicit international input is not retargeted`() {
        val country = CountryDialCode("US", "+1")

        assertEquals("+442071838750", country.compose("+44 20 7183 8750"))
    }

    @Test
    fun `italian national zero remains significant`() {
        val country = CountryDialCode("IT", "+39")

        assertEquals("+390212345678", country.compose("02 1234 5678"))
    }

    @Test
    fun `phone login validation uses the composed e164 target`() {
        val valid = LoginUiState(
            method = LoginMethod.Phone,
            destination = "555 555 0100",
            phoneCountry = CountryDialCode("US", "+1"),
        )
        val invalid = valid.copy(destination = "123")

        assertTrue(valid.canSendCode)
        assertEquals("+15555550100", valid.normalizedDestination)
        assertFalse(invalid.canSendCode)
    }
}
