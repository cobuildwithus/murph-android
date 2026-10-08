package ai.withmurph.companion.auth

/** BotFather binds each redirect to one installed-package signing certificate. */
internal object TelegramLoginRedirect {
    fun forSigners(packageName: String, fingerprints: List<String>): String? {
        if (packageName != "ai.withmurph.app" || fingerprints.size != 1) return null
        return when (fingerprints.single()) {
            "44:E0:3E:46:B1:DF:A9:0F:9B:86:BD:25:F1:AE:DE:3F:96:5B:4C:DB:20:95:C9:06:18:CD:5E:BA:7A:DC:21:3F" ->
                "https://app397543190-login.tg.dev/tglogin"
            "0E:3B:C0:67:49:13:B0:7C:9C:0C:2F:7A:78:33:E4:2C:40:53:28:F6:AC:EB:C9:DB:45:7B:66:C2:83:C2:5C:39" ->
                "https://app1452659770-login.tg.dev/tglogin"
            else -> null
        }
    }
}
