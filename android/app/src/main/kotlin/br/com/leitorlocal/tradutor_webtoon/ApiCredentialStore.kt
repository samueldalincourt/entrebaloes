package br.com.leitorlocal.tradutor_webtoon

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Personal API credentials: encrypted at rest, never returned through Flutter status or logs. */
class ApiCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("api_credentials", Context.MODE_PRIVATE)
    private val alias = "entre-baloes-gemini-v1"
    val configured get() = prefs.contains("gemini_key")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(value: String) {
        val clean = value.trim()
        require(clean.length in 20..256 && clean.all { it.code in 33..126 }) { "Confira a chave copiada do AI Studio." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("gemini_key", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("gemini_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "Não foi possível salvar a chave no aparelho." }
    }
    @Synchronized fun read(): String {
        try {
            val data = prefs.getString("gemini_key", null) ?: error("missing")
            val iv = prefs.getString("gemini_iv", null) ?: error("missing")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            return cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("Insira novamente a chave do Gemini no app.") }
    }
    @Synchronized fun clear() { prefs.edit().clear().commit() }
}
