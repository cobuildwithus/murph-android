package ai.withmurph.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AddressBookConsentCopyTest {
    @Test
    fun phoneDisclosureExplainsCountryBasedLocalNumberConversion() {
        val resourceFile = sequenceOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first(File::isFile)
        val resources = resourceFile.readText()
        val disclosure = Regex(
            """<string name="address_book_phone_disclosure">([^<]+)</string>""",
        ).find(resources)?.groupValues?.get(1).orEmpty()

        assertTrue(disclosure.contains("local numbers"))
        assertTrue(disclosure.contains("contact or device country"))
        assertFalse(disclosure.contains("Only explicit international"))
    }
}
