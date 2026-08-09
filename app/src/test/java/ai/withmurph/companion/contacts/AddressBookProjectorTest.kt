package ai.withmurph.companion.contacts

import ai.withmurph.companion.core.AddressBookPersonContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBookProjectorTest {
    @Test
    fun normalizesStructurallyValidInternationalNumbers() {
        assertEquals(
            "+12125550123",
            AddressBookProjector.normalizePhoneNumber(" +1 (212) 555-0123 "),
        )
        assertEquals(
            "+442079460018",
            AddressBookProjector.normalizePhoneNumber("0044 20 7946 0018"),
        )
        assertEquals(
            "+12125550123",
            AddressBookProjector.normalizePhoneNumber("\u200E+1\u00A0(212) 555-0123\u200F"),
        )

        listOf(
            "2125550123",
            "+1234567",
            "+1234567890123456",
            "+0123456789",
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
    fun canonicalizesProviderNormalizedAndNationalNumbersWithDeviceRegion() {
        var formatterCalls = 0
        assertEquals(
            "+442079460018",
            AddressBookProjector.canonicalPhoneNumber(
                rawValue = "\u202A020\u00A07946 0018\u202C",
                providerNormalizedValue = "+44 20 7946 0018",
                defaultRegionCode = "US",
            ) { _, _ ->
                formatterCalls += 1
                null
            },
        )
        assertEquals(0, formatterCalls)

        val formatterInputs = mutableListOf<Pair<String, String>>()
        assertEquals(
            "+12125550123",
            AddressBookProjector.canonicalPhoneNumber(
                rawValue = "(212) 555-0123",
                providerNormalizedValue = null,
                defaultRegionCode = "us",
            ) { value, regionCode ->
                formatterInputs += value to regionCode
                "+12125550123"
            },
        )
        assertEquals(listOf("2125550123" to "US"), formatterInputs)

        assertEquals(
            "+442079460018",
            AddressBookProjector.canonicalPhoneNumber(
                rawValue = "00 44 20 7946 0018",
                providerNormalizedValue = null,
                defaultRegionCode = "US",
            ) { value, _ ->
                assertEquals("+442079460018", value)
                "+442079460018"
            },
        )
        listOf(
            "+",
            "+1 (212) 555-0123 ext 2",
            "+1 (212) 555-0123 x2",
            "+1 (212) 555-0123 #2",
            "+1 (212) 555-0123,2",
            "+1 (212) 555-0123;2",
        ).forEach { rawValue ->
            assertNull(
                rawValue,
                AddressBookProjector.canonicalPhoneNumber(
                    rawValue = rawValue,
                    providerNormalizedValue = "+12125550123",
                    defaultRegionCode = "US",
                ) { _, _ -> error("Unsafe raw values must not reach the platform formatter") },
            )
        }
        assertNull(
            AddressBookProjector.canonicalPhoneNumber(
                rawValue = "(212) 555-0123",
                providerNormalizedValue = null,
                defaultRegionCode = "US",
            ) { _, _ -> "+0123456789" },
        )
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
            "lawyer",
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
    fun preservesSafeConflictingAliasesAndDeduplicatesMatchingRows() {
        val projections = AddressBookProjector.project(
            listOf(
                person("Anna", "Smith", "+12125550101", "+12125550102"),
                person("Anna", "Smith", "+1 212 555 0101"),
                person("Ben", "Jones", "+12125550102"),
                person("Cara", "Diaz", "+12125550103"),
            ),
        )

        assertEquals(
            setOf(
                "+12125550101" to "Anna S.",
                "+12125550102" to "Anna S. / Ben J.",
                "+12125550103" to "Cara D.",
            ),
            projections.map { it.phoneNumber to it.advisoryName }.toSet(),
        )
    }

    @Test
    fun keepsAtMostFourCaseInsensitiveAliasesInDeterministicOrder() {
        assertEquals(
            "Billy B. / Bob B.",
            AddressBookProjector.coalescedAdvisoryName(
                listOf("Bob B.", "Billy B.", "Bob B."),
            ),
        )
        assertEquals(
            "Alex R.",
            AddressBookProjector.coalescedAdvisoryName(listOf("alex R.", "Alex R.")),
        )
        assertEquals(
            "alpha / Bob / Cam / Dee",
            AddressBookProjector.coalescedAdvisoryName(
                listOf("Echo", "Dee", "Cam", "Bob", "alpha"),
            ),
        )
        assertEquals(
            "A".repeat(23),
            AddressBookProjector.coalescedAdvisoryName(
                listOf("A".repeat(23), "B".repeat(23)),
            ),
        )
        assertEquals(
            "界".repeat(15) + " / " + "語".repeat(16),
            AddressBookProjector.coalescedAdvisoryName(
                listOf("語".repeat(16), "界".repeat(15)),
            ),
        )
        assertEquals(
            "界".repeat(16),
            AddressBookProjector.coalescedAdvisoryName(
                listOf("語".repeat(16), "界".repeat(16)),
            ),
        )

        val contacts = listOf("Echo", "Dee", "Cam", "Bob", "alpha").map {
            person(it, null, "+12125550123")
        }
        val forward = AddressBookProjector.project(contacts)
        val reversed = AddressBookProjector.project(contacts.reversed())
        assertEquals(forward, reversed)
        assertEquals("alpha / Bob / Cam / Dee", forward.single().advisoryName)

        val overflow = AddressBookProjector.project(
            listOf(
                person("A".repeat(23), null, "+12125550124"),
                person("B".repeat(23), null, "+12125550124"),
            ),
        )
        assertEquals("A".repeat(23), overflow.single().advisoryName)
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
