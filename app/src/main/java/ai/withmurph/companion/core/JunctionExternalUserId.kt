package ai.withmurph.companion.core

import java.security.MessageDigest

object JunctionExternalUserId {
    fun derive(memberKey: String, environment: AppEnvironment): String {
        val input = "murph-junction-external-user:v1:${environment.wireValue}:$memberKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return "murph:" + digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
