package ai.withmurph.companion.auth

import java.util.Locale

data class CountryDialCode(
    val region: String,
    val dialCode: String,
) {
    val localizedName: String
        get() = Locale.Builder()
            .setRegion(region)
            .build()
            .getDisplayCountry(Locale.getDefault())
            .ifBlank { region }

    fun compose(input: String): String {
        val trimmed = input.trim()
        compactExplicitInternational(trimmed)?.let { return it }
        if ('+' in trimmed) return trimmed

        val digits = compactNational(trimmed) ?: return trimmed
        val significantDigits = when {
            dialCode == "+1" && digits.length == 11 && digits.startsWith("1") ->
                digits.drop(1)
            dialCode == "+39" -> digits
            else -> digits.dropWhile { it == '0' }
        }
        return dialCode + significantDigits
    }

    companion object {
        val All = listOf(
            CountryDialCode("US", "+1"),
            CountryDialCode("CA", "+1"),
            CountryDialCode("GB", "+44"),
            CountryDialCode("IE", "+353"),
            CountryDialCode("AU", "+61"),
            CountryDialCode("NZ", "+64"),
            CountryDialCode("DE", "+49"),
            CountryDialCode("FR", "+33"),
            CountryDialCode("ES", "+34"),
            CountryDialCode("PT", "+351"),
            CountryDialCode("IT", "+39"),
            CountryDialCode("NL", "+31"),
            CountryDialCode("BE", "+32"),
            CountryDialCode("CH", "+41"),
            CountryDialCode("AT", "+43"),
            CountryDialCode("PL", "+48"),
            CountryDialCode("CZ", "+420"),
            CountryDialCode("SE", "+46"),
            CountryDialCode("NO", "+47"),
            CountryDialCode("DK", "+45"),
            CountryDialCode("FI", "+358"),
            CountryDialCode("GR", "+30"),
            CountryDialCode("UA", "+380"),
            CountryDialCode("TR", "+90"),
            CountryDialCode("IL", "+972"),
            CountryDialCode("AE", "+971"),
            CountryDialCode("SA", "+966"),
            CountryDialCode("IN", "+91"),
            CountryDialCode("PK", "+92"),
            CountryDialCode("BD", "+880"),
            CountryDialCode("SG", "+65"),
            CountryDialCode("MY", "+60"),
            CountryDialCode("TH", "+66"),
            CountryDialCode("VN", "+84"),
            CountryDialCode("PH", "+63"),
            CountryDialCode("ID", "+62"),
            CountryDialCode("JP", "+81"),
            CountryDialCode("KR", "+82"),
            CountryDialCode("HK", "+852"),
            CountryDialCode("TW", "+886"),
            CountryDialCode("CN", "+86"),
            CountryDialCode("BR", "+55"),
            CountryDialCode("MX", "+52"),
            CountryDialCode("AR", "+54"),
            CountryDialCode("CL", "+56"),
            CountryDialCode("CO", "+57"),
            CountryDialCode("PE", "+51"),
            CountryDialCode("ZA", "+27"),
            CountryDialCode("NG", "+234"),
            CountryDialCode("KE", "+254"),
            CountryDialCode("EG", "+20"),
        )

        val Default: CountryDialCode
            get() {
                val region = Locale.getDefault().country
                return All.firstOrNull { it.region == region } ?: All.first()
            }

        val SortedByName: List<CountryDialCode>
            get() = All.sortedBy { it.localizedName }

        fun isPlausibleE164(value: String): Boolean {
            if (!value.startsWith("+")) return false
            val digits = value.drop(1)
            return digits.length in 8..15 &&
                digits.firstOrNull() != '0' &&
                digits.all { it in '0'..'9' }
        }

        private fun compactExplicitInternational(value: String): String? {
            val result = StringBuilder()
            value.forEach { character ->
                when {
                    character == '+' && result.isEmpty() -> result.append(character)
                    character in '0'..'9' && result.isNotEmpty() -> result.append(character)
                    isPermittedFormatting(character) -> Unit
                    else -> return null
                }
            }
            return result.toString().takeIf { it.startsWith("+") }
        }

        private fun compactNational(value: String): String? {
            val result = StringBuilder()
            value.forEach { character ->
                when {
                    character in '0'..'9' -> result.append(character)
                    isPermittedFormatting(character) -> Unit
                    else -> return null
                }
            }
            return result.toString()
        }

        private fun isPermittedFormatting(character: Char): Boolean =
            character.isWhitespace() || character in setOf(' ', '-', '(', ')', '.')
    }
}
