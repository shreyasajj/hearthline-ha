package dev.pixelhome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Scoped HA credential only. Separate key, file and trust domain from Sendspin. */
class TabletPairingStore(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "hearthline_pairing_v1")
    private val identity = java.io.File(context.noBackupFilesDir, "hearthline_installation_v1")
    val tabletId: String = if(identity.exists()) identity.readText().also { UUID.fromString(it) }
        else UUID.randomUUID().toString().also { identity.writeText(it) }
    private val alias = "hearthline_ha_pairing_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias,null) as? SecretKey) ?: run {
            check(!file.exists()) { "Pairing key unavailable. Forget and pair again." }
            KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    fun load(): JSONObject? {
        if(!file.exists()) return null
        val bytes = file.readBytes(); require(bytes.size in 29..8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD("hearthline:ha:v1".toByteArray())
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    fun save(origin: String, entry: String, credential: String, telemetry: Boolean) {
        val value = JSONObject().put("origin",origin).put("entry_id",entry).put("credential",credential).put("telemetry",telemetry)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        cipher.updateAAD("hearthline:ha:v1".toByteArray())
        val atomic = android.util.AtomicFile(file)
        val output=atomic.startWrite()
        try { output.write(cipher.iv+cipher.doFinal(value.toString().toByteArray())); atomic.finishWrite(output) }
        catch(e: Exception) { atomic.failWrite(output); throw e }
    }
    fun forget() {
        check(!file.exists() || file.delete())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
