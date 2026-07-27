package ai.withmurph.companion.contacts

import ai.withmurph.companion.core.AddressBookPersonContact
import ai.withmurph.companion.core.AddressBookProjection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

object AddressBookProjector {
    const val MAX_CONTACTS = 5_000
    const val MAX_PHONE_VALUES = 20_000
    const val MAX_PHONES_PER_CONTACT = 8
    const val MAX_PROJECTIONS = 1_000
    const val MAX_LABEL_CHARACTERS = 48
    const val MAX_LABEL_UTF8_BYTES = 96

    fun project(contacts: List<AddressBookPersonContact>): List<AddressBookProjection> {
        val namesByPhone = linkedMapOf<String, String>()
        val conflictedPhones = mutableSetOf<String>()
        var phoneValuesSeen = 0

        contactLoop@ for (contact in contacts.take(MAX_CONTACTS)) {
            val advisoryName = sanitizeName(contact.givenName, contact.familyName)
            for (rawPhone in contact.phoneNumbers.take(MAX_PHONES_PER_CONTACT)) {
                if (phoneValuesSeen >= MAX_PHONE_VALUES) break@contactLoop
                phoneValuesSeen += 1
                if (advisoryName == null) continue
                val phoneNumber = normalizePhoneNumber(rawPhone) ?: continue
                if (phoneNumber in conflictedPhones) continue

                when (namesByPhone[phoneNumber]) {
                    null -> namesByPhone[phoneNumber] = advisoryName
                    advisoryName -> Unit
                    else -> {
                        namesByPhone.remove(phoneNumber)
                        conflictedPhones += phoneNumber
                    }
                }
            }
        }

        return namesByPhone
            .map { (phoneNumber, advisoryName) ->
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

    fun normalizePhoneNumber(rawValue: String): String? {
        if (rawValue.isEmpty() || rawValue.any { it.code > ASCII_MAX }) return null
        val value = rawValue.trim(' ')
        val body = when {
            value.startsWith("+") -> value.substring(1)
            value.startsWith("00") -> value.substring(2)
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
        if (digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) return null
        return "+$digits"
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

    private const val ASCII_MAX = 0x7f
    private const val MIN_PHONE_DIGITS = 8
    private const val MAX_PHONE_DIGITS = 15
    private val ALLOWED_PHONE_SEPARATORS = setOf(' ', '-', '(', ')', '.')
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
