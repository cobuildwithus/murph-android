package ai.withmurph.companion.contacts

import ai.withmurph.companion.core.AddressBookPersonContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBookProjectorTest {
    @Test
    fun normalizesOnlyExplicitAsciiInternationalNumbers() {
        assertEquals(
            "+12125550123",
            AddressBookProjector.normalizePhoneNumber(" +1 (212) 555-0123 "),
        )
        assertEquals(
            "+442079460018",
            AddressBookProjector.normalizePhoneNumber("0044 20 7946 0018"),
        )

        listOf(
            "2125550123",
            "+1234567",
            "+1234567890123456",
            "+1-800-FLOWERS",
            "+١٢٣٤٥٦٧٨",
            "\t+12125550123",
            "+12125550123#4",
            "+12125550123,",
            "00+442079460018",
        ).forEach { value ->
            assertNull(value, AddressBookProjector.normalizePhoneNumber(value))
        }
    }

    @Test
    fun emitsOneSafeFirstNameTokenAndOptionalLastInitial() {
        assertEquals("Élodie D.", AddressBookProjector.sanitizeName(" Élodie Marie ", "de Souza"))
        assertEquals("Anne-Marie O.", AddressBookProjector.sanitizeName("Anne-Marie", "O'Neil"))
        assertEquals("Luz Á.", AddressBookProjector.sanitizeName("Luz", "Álvarez"))
        assertEquals("Anna", AddressBookProjector.sanitizeName("Anna", "ßeta"))

        listOf(
            "Mom",
            "Doctor",
            "My Mom",
            "Anna Work",
            "friend",
            "john@example.com",
            "https://example.com",
            "A🙂",
            "-Anna",
            "Anna-",
            "Anna--Marie",
        ).forEach { value ->
            assertNull(value, AddressBookProjector.sanitizeName(value, "Smith"))
        }

        assertEquals("a".repeat(48), AddressBookProjector.sanitizeName("a".repeat(48), null))
        assertNull(AddressBookProjector.sanitizeName("a".repeat(49), null))
        assertNull(AddressBookProjector.sanitizeName("界".repeat(33), null))
    }

    @Test
    fun dropsConflictingPhoneNamesAndDeduplicatesMatchingRows() {
        val projections = AddressBookProjector.project(
            listOf(
                person("Anna", "Smith", "+12125550101", "+12125550102"),
                person("Anna", "Smith", "+1 212 555 0101"),
                person("Ben", "Jones", "+12125550102"),
                person("Cara", "Diaz", "+12125550103"),
            ),
        )

        assertEquals(
            setOf("+12125550101" to "Anna S.", "+12125550103" to "Cara D."),
            projections.map { it.phoneNumber to it.advisoryName }.toSet(),
        )
    }

    @Test
    fun enforcesContactPhoneAndValueScanBoundsBeforeProjection() {
        val ninthOnly = person(
            "Anna",
            "Smith",
            *(List(8) { "local-$it" } + "+12125550123").toTypedArray(),
        )
        assertTrue(AddressBookProjector.project(listOf(ninthOnly)).isEmpty())

        val afterContactLimit = List(AddressBookProjector.MAX_CONTACTS) {
            person("", null, "local")
        } + person("Anna", "Smith", "+12125550123")
        assertTrue(AddressBookProjector.project(afterContactLimit).isEmpty())

        val afterPhoneValueLimit = List(
            AddressBookProjector.MAX_PHONE_VALUES / AddressBookProjector.MAX_PHONES_PER_CONTACT,
        ) {
            person("", null, *List(8) { "local" }.toTypedArray())
        } + person("Anna", "Smith", "+12125550123")
        assertTrue(AddressBookProjector.project(afterPhoneValueLimit).isEmpty())
    }

    @Test
    fun selectsAtMostOneThousandByStableSha256Rank() {
        val contacts = (0 until 1_200).map { index ->
            person(
                "Person$index".replace(Regex("[0-9]"), "A"),
                "Smith",
                "+1555${index.toString().padStart(7, '0')}",
            )
        }

        val forward = AddressBookProjector.project(contacts)
        val reversed = AddressBookProjector.project(contacts.reversed())

        assertEquals(AddressBookProjector.MAX_PROJECTIONS, forward.size)
        assertEquals(forward, reversed)
        assertEquals(forward.size, forward.map { it.phoneNumber }.toSet().size)
    }

    private fun person(
        givenName: String?,
        familyName: String?,
        vararg phoneNumbers: String,
    ) = AddressBookPersonContact(givenName, familyName, phoneNumbers.toList())
}
