package ai.withmurph.companion.contacts

import ai.withmurph.companion.core.AddressBookPersonContact
import ai.withmurph.companion.core.AddressBookProjection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.SortedMap

object AddressBookProjector {
    const val MAX_CONTACTS = 5_000
    const val MAX_PHONE_VALUES = 20_000
    const val MAX_PHONES_PER_CONTACT = 8
    const val MAX_PROJECTIONS = 1_000
    const val MAX_LABEL_CHARACTERS = 48
    const val MAX_LABEL_UTF8_BYTES = 96

    fun project(contacts: List<AddressBookPersonContact>): List<AddressBookProjection> {
        val aliasesByPhone = linkedMapOf<String, SortedMap<String, String>>()
        var phoneValuesSeen = 0

        contactLoop@ for (contact in contacts.take(MAX_CONTACTS)) {
            val advisoryName = sanitizeName(contact.givenName, contact.familyName)
            for (rawPhone in contact.phoneNumbers.take(MAX_PHONES_PER_CONTACT)) {
                if (phoneValuesSeen >= MAX_PHONE_VALUES) break@contactLoop
                phoneValuesSeen += 1
                if (advisoryName == null) continue
                val phoneNumber = normalizePhoneNumber(rawPhone) ?: continue
                val aliases = aliasesByPhone.getOrPut(phoneNumber) { sortedMapOf() }
                addAlias(aliases, advisoryName)
            }
        }

        return aliasesByPhone
            .mapNotNull { (phoneNumber, aliases) ->
                val advisoryName = joinedAliases(aliases) ?: return@mapNotNull null
                RankedProjection(
                    projection = AddressBookProjection(phoneNumber, advisoryName),
                    digest = sha256("$phoneNumber\u0000$advisoryName"),
                )
            }
            .sortedWith { left, right ->
                compareDigests(left.digest, right.digest)
                    .takeIf { it != 0 }
                    ?: compareValuesBy(
                        left,
                        right,
                        { it.projection.phoneNumber },
                        { it.projection.advisoryName },
                    )
            }
            .take(MAX_PROJECTIONS)
            .map(RankedProjection::projection)
    }

    internal fun canonicalPhoneNumber(
        rawValue: String,
        providerNormalizedValue: String?,
        defaultRegionCode: String,
        formatToE164: (value: String, regionCode: String) -> String?,
    ): String? {
        val candidate = phoneParsingCandidate(rawValue) ?: return null
        providerNormalizedValue
            ?.let(::normalizePhoneNumber)
            ?.let { return it }
        normalizePhoneNumber(candidate)?.let { return it }

        val regionCode = defaultRegionCode.uppercase(Locale.US)
        if (!REGION_CODE.matches(regionCode)) return null
        return formatToE164(candidate, regionCode)?.let(::normalizePhoneNumber)
    }

    fun normalizePhoneNumber(rawValue: String): String? {
        val value = phoneParsingCandidate(rawValue) ?: return null
        val body = when {
            value.startsWith("+") -> value.substring(1)
            else -> return null
        }
        if (body.isEmpty()) return null

        val digits = StringBuilder(body.length)
        for (character in body) {
            when {
                character in '0'..'9' -> digits.append(character)
                character in ALLOWED_PHONE_SEPARATORS -> Unit
                else -> return null
            }
        }
        if (
            digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS ||
            digits.firstOrNull() == '0'
        ) {
            return null
        }
        return "+$digits"
    }

    internal fun coalescedAdvisoryName(advisoryNames: Iterable<String>): String? {
        val aliases = sortedMapOf<String, String>()
        advisoryNames.forEach { addAlias(aliases, it) }
        return joinedAliases(aliases)
    }

    fun sanitizeName(givenName: String?, familyName: String?): String? {
        val source = givenName?.trim().orEmpty()
        if (
            source.isEmpty() ||
            looksLikeAddressOrUrl(source) ||
            containsRelationshipOrRole(source)
        ) {
            return null
        }

        val firstToken = source
            .splitToSequence(Regex("\\s+"))
            .firstOrNull()
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            ?: return null
        if (!isSafeNameToken(firstToken)) return null

        val familyInitial = familyName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !looksLikeAddressOrUrl(it) }
            ?.splitToSequence(Regex("\\s+"))
            ?.firstOrNull()
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            ?.takeIf(::isSafeNameToken)
            ?.takeUnless(::isRelationshipOrRole)
            ?.codePoints()
            ?.toArray()
            ?.firstOrNull { Character.isLetter(it) }
            ?.let(::uppercaseSingleCodePointOrNull)

        val advisoryName = if (familyInitial == null) {
            firstToken
        } else {
            "$firstToken $familyInitial."
        }
        return advisoryName.takeIf(::isWithinLabelLimit)
    }

    private fun isSafeNameToken(token: String): Boolean {
        if (token.isEmpty() || token.any(Char::isWhitespace)) return false
        val codePoints = token.codePoints().toArray()
        if (codePoints.isEmpty() || !Character.isLetter(codePoints.first())) return false
        if (!Character.isLetter(codePoints.last())) return false

        for (index in codePoints.indices) {
            val codePoint = codePoints[index]
            val isLetter = Character.isLetter(codePoint)
            val type = Character.getType(codePoint)
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
            val isJoiner = codePoint in NAME_JOINERS
            if (!isLetter && !isMark && !isJoiner) return false
            if (isMark && index == 0) return false
            if (isJoiner) {
                val previous = codePoints.getOrNull(index - 1) ?: return false
                val next = codePoints.getOrNull(index + 1) ?: return false
                if (!isLetterOrMark(previous) || !Character.isLetter(next)) return false
            }
        }
        return true
    }

    private fun isLetterOrMark(codePoint: Int): Boolean {
        if (Character.isLetter(codePoint)) return true
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun uppercaseSingleCodePointOrNull(codePoint: Int): String? {
        val upper = String(Character.toChars(codePoint)).uppercase()
        return upper.takeIf {
            it.codePointCount(0, it.length) == 1 && Character.isLetter(it.codePointAt(0))
        }
    }

    private fun isWithinLabelLimit(value: String): Boolean =
        value.codePointCount(0, value.length) <= MAX_LABEL_CHARACTERS &&
            value.toByteArray(StandardCharsets.UTF_8).size <= MAX_LABEL_UTF8_BYTES

    private fun addAlias(aliases: SortedMap<String, String>, advisoryName: String) {
        val foldedName = advisoryName.lowercase(Locale.US)
        val existingName = aliases[foldedName]
        if (existingName == null || advisoryName < existingName) {
            aliases[foldedName] = advisoryName
        }
        while (aliases.size > MAX_ALIASES_PER_PHONE) {
            aliases.remove(aliases.lastKey())
        }
    }

    private fun joinedAliases(aliases: SortedMap<String, String>): String? {
        var prefix = ""
        for (advisoryName in aliases.values) {
            val candidate = if (prefix.isEmpty()) {
                advisoryName
            } else {
                "$prefix$ALIAS_SEPARATOR$advisoryName"
            }
            if (!isWithinLabelLimit(candidate)) break
            prefix = candidate
        }
        return prefix.takeIf(String::isNotEmpty)
    }

    private fun phoneParsingCandidate(rawValue: String): String? {
        if (rawValue.isEmpty()) return null
        val compact = StringBuilder(rawValue.length)
        for (character in rawValue) {
            when {
                character in '0'..'9' -> compact.append(character)
                character == '+' && compact.isEmpty() -> compact.append(character)
                character in ALLOWED_PHONE_SEPARATORS ||
                    Character.getType(character) == Character.DASH_PUNCTUATION.toInt() -> Unit
                Character.isSpaceChar(character) -> Unit
                character.code in ALLOWED_BIDI_FORMATTING -> Unit
                else -> return null
            }
        }
        if (compact.none { it in '0'..'9' }) return null
        val compactValue = compact.toString()
        return if (compactValue.startsWith("00")) {
            "+" + compactValue.substring(2)
        } else {
            compactValue
        }
    }

    private fun looksLikeAddressOrUrl(value: String): Boolean {
        val lower = value.lowercase()
        return '@' in value ||
            "://" in lower ||
            lower.startsWith("www.") ||
            lower.startsWith("mailto:")
    }

    private fun containsRelationshipOrRole(value: String): Boolean =
        value
            .lowercase()
            .replace("’", "'")
            .splitToSequence(RELATIONSHIP_OR_ROLE_BOUNDARY)
            .filter(String::isNotEmpty)
            .any { it in RELATIONSHIP_OR_ROLE_LABELS }

    private fun isRelationshipOrRole(value: String): Boolean =
        value.lowercase().replace("’", "'") in RELATIONSHIP_OR_ROLE_LABELS

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun compareDigests(left: ByteArray, right: ByteArray): Int {
        for (index in left.indices) {
            val comparison = (left[index].toInt() and 0xff)
                .compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class RankedProjection(
        val projection: AddressBookProjection,
        val digest: ByteArray,
    )

    private const val MIN_PHONE_DIGITS = 8
    private const val MAX_PHONE_DIGITS = 15
    private const val MAX_ALIASES_PER_PHONE = 4
    private const val ALIAS_SEPARATOR = " / "
    private val ALLOWED_PHONE_SEPARATORS = setOf(' ', '-', '(', ')', '.')
    private val ALLOWED_BIDI_FORMATTING = setOf(
        0x061c, // Arabic letter mark
        0x200e, // Left-to-right mark
        0x200f, // Right-to-left mark
        0x202a, // Left-to-right embedding
        0x202b, // Right-to-left embedding
        0x202c, // Pop directional formatting
        0x202d, // Left-to-right override
        0x202e, // Right-to-left override
        0x2066, // Left-to-right isolate
        0x2067, // Right-to-left isolate
        0x2068, // First strong isolate
        0x2069, // Pop directional isolate
    )
    private val REGION_CODE = Regex("[A-Z]{2}")
    private val NAME_JOINERS = setOf('\''.code, '’'.code, '-'.code)
    private val RELATIONSHIP_OR_ROLE_BOUNDARY = Regex("[^\\p{L}]+")

    private val RELATIONSHIP_OR_ROLE_LABELS = setOf(
        "assistant",
        "aunt",
        "babysitter",
        "bestie",
        "boss",
        "boyfriend",
        "brother",
        "coach",
        "colleague",
        "coworker",
        "cousin",
        "dad",
        "daddy",
        "daughter",
        "dentist",
        "doctor",
        "dr",
        "emergency",
        "father",
        "fiance",
        "fiancee",
        "friend",
        "girlfriend",
        "granddad",
        "grandfather",
        "grandma",
        "grandmother",
        "grandpa",
        "grandparent",
        "gym",
        "home",
        "husband",
        "ice",
        "landlord",
        "lawyer",
        "manager",
        "mom",
        "mommy",
        "mother",
        "neighbor",
        "neighbour",
        "nurse",
        "office",
        "papa",
        "partner",
        "roommate",
        "school",
        "sister",
        "son",
        "spouse",
        "teacher",
        "therapist",
        "uncle",
        "wife",
        "work",
    )
}
