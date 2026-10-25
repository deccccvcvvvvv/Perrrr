package pro.cslauncher.app.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import pro.cslauncher.core.account.SecretStore
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.FsUtil
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted with AES-256-GCM using a non-exportable Android Keystore key. The file holds only
 * ciphertext; it is re-read on every access so the launcher and ":game" processes always agree.
 */
class KeystoreSecretStore(private val file: File, private val alias: String = "cs_launcher_secrets_v1") : SecretStore {
    private val ser = MapSerializer(String.serializer(), String.serializer())

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    private fun readAll(): Map<String, String> =
        if (!file.isFile) emptyMap() else runCatching { CsJson.decodeFromString(ser, file.readText()) }.getOrDefault(emptyMap())
    private fun writeAll(m: Map<String, String>) = FsUtil.writeAtomic(file.toPath(), CsJson.encodeToString(ser, m))

    @Synchronized override fun put(key: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val blob = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        writeAll(readAll() + (key to Base64.encodeToString(blob, Base64.NO_WRAP)))
    }

    @Synchronized override fun get(key: String): String? {
        val blob = readAll()[key]?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        }.getOrNull()   // key invalidated (e.g. device restore): treated as "not signed in"
    }

    @Synchronized override fun remove(key: String) { val m = readAll(); if (key in m) writeAll(m - key) }
}
