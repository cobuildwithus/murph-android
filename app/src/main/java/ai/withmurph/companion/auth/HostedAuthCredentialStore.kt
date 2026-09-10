package ai.withmurph.companion.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One app-private, non-backed-up AES-GCM record with an Android Keystore key. */
class HostedAuthCredentialStore(context: Context) : HostedAuthCredentialStoring {
    private val file = AtomicFile(File(context.noBackupFilesDir, "hosted-auth-v1"))

    @Synchronized
    override fun load(): HostedAuthStoredState? = guarded {
        val encoded = try {
            readBounded()
        } catch (error: FileNotFoundException) {
            if (hasRecordFiles()) throw error
            return@guarded null
        }
        require(encoded.size in 30..MAX_BYTES && encoded[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        decode(cipher.doFinal(encoded.copyOfRange(13, encoded.size)))
    }

    @Synchronized
    override fun save(state: HostedAuthStoredState) = guarded {
        val plaintext = encode(state)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = !hasRecordFiles()))
        cipher.updateAAD(AAD)
        require(cipher.iv.size == 12)
        val encoded = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
        val output = file.startWrite()
        try {
            output.write(encoded)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        // AtomicFile reports some rename failures through platform logging.
        // Never acknowledge the new authority without checking its stored bytes.
        check(readBounded().contentEquals(encoded))
    }

    private fun hasRecordFiles(): Boolean = listOf("", ".bak", ".new")
        .any { File(file.baseFile.path + it).exists() }

    private fun readBounded(): ByteArray = file.openRead().use { input ->
        val bytes = ByteArray(MAX_BYTES + 1)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
        }
        require(count <= MAX_BYTES)
        bytes.copyOf(count)
    }

    private fun key(create: Boolean): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun <T> guarded(operation: () -> T): T = try {
        operation()
    } catch (_: Exception) {
        throw HostedAuthException.CredentialsUnavailable
    }

    internal companion object {
        private const val KEY_ALIAS = "murph.hosted-auth.v1"
        private const val MAX_BYTES = 8192
        private val AAD = "murph.hosted-auth.record.v1".toByteArray(Charsets.UTF_8)

        fun encode(state: HostedAuthStoredState): ByteArray {
            validateHostedAuthState(state)
            val json = JSONObject().put("version", 1)
                .put("state", if (state is HostedAuthStoredState.Active) "active" else "signed_out")
            state.binding?.let {
                json.put("memberId", it.memberId).put("localMemberKey", it.localMemberKey)
            }
            if (state is HostedAuthStoredState.Active) {
                json.put("credential", state.credential)
                    .put("expiresAt", state.expiresAt.toString())
                    .put("verifiedAt", state.verifiedAt.toString())
            }
            return json.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 4096) }
        }

        fun decode(data: ByteArray): HostedAuthStoredState {
            require(data.size <= 4096)
            val json = JSONObject(data.toString(Charsets.UTF_8))
            require(json.get("version") == 1)
            val binding = if (json.has("memberId") || json.has("localMemberKey")) {
                HostedAuthBinding(json.string("memberId"), json.string("localMemberKey"))
            } else null
            val state = when (json.string("state")) {
                "active" -> HostedAuthStoredState.Active(
                    binding = requireNotNull(binding),
                    credential = json.string("credential"),
                    expiresAt = Instant.parse(json.string("expiresAt")),
                    verifiedAt = Instant.parse(json.string("verifiedAt")),
                )
                "signed_out" -> {
                    require(!json.has("credential") && !json.has("expiresAt") && !json.has("verifiedAt"))
                    HostedAuthStoredState.SignedOut(binding)
                }
                else -> throw HostedAuthException.CredentialsUnavailable
            }
            validateHostedAuthState(state)
            return state
        }

        private fun JSONObject.string(key: String): String = get(key) as? String
            ?: throw HostedAuthException.CredentialsUnavailable
    }
}
