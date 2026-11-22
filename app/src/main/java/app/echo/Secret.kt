package app.echo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key at rest.
 *
 * The key used to live in SharedPreferences as plain text. It is now wrapped with
 * an AES-256-GCM key that never leaves the Android Keystore, so the on-disk XML
 * only holds "v1:" + base64(iv || ciphertext) and stays useless if copied off the
 * device. Decryption failure (Keystore reset, restored backup, factory reset) is
 * treated as "key missing" and the stale blob is dropped, so the app asks for the
 * key again instead of crashing or silently sending an empty credential.
 */
object KeyVault {
    private const val PREFS = "continue-native"
    private const val FIELD = "apiKey"
    private const val PREFIX = "v1:"
    private const val ALIAS = "echo.apiKey.v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    data class Loaded(val key: String, val lost: Boolean)

    fun load(context: Context): Loaded {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(FIELD, "").orEmpty()
        if (raw.isBlank()) return Loaded("", false)
        if (raw.startsWith(PREFIX)) {
            val plain = runCatching { decrypt(raw.removePrefix(PREFIX)) }.getOrNull()
            if (plain.isNullOrBlank()) {
                // Keystore key gone or payload damaged: never keep an undecryptable blob.
                prefs.edit().remove(FIELD).apply()
                return Loaded("", true)
            }
            return Loaded(plain, false)
        }
        // Legacy plaintext from 0.9.0: migrate on first read. If the Keystore is
        // unavailable we still refuse to leave plaintext behind; the key stays in
        // memory for this process only.
        val migrated = runCatching { encrypt(raw) }.getOrNull()
        if (migrated != null) {
            prefs.edit().putString(FIELD, PREFIX + migrated).apply()
        } else {
            prefs.edit().remove(FIELD).apply()
        }
        return Loaded(raw, false)
    }

    /** Returns false when the key could not be persisted safely; nothing is written. */
    fun save(context: Context, plain: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = plain.trim()
        if (value.isEmpty()) {
            prefs.edit().remove(FIELD).apply()
            return true
        }
        val token = runCatching { encrypt(value) }.getOrNull() ?: return false
        prefs.edit().putString(FIELD, PREFIX + token).apply()
        return true
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(FIELD).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + body.size)
        iv.copyInto(out, 0)
        body.copyInto(out, iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(token: String): String {
        val packed = Base64.decode(token, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "short payload" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val body = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
