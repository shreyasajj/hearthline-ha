package dev.pixelhome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sendspindroid.sendspin.crypto.*
import com.sendspindroid.sendspin.pairing.PairingToken
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate Keystore-encrypted receiver identity. HA credentials remain session-only. */
class SendspinStore(context: Context) {
    private val prefs = context.getSharedPreferences("sendspin_secrets", Context.MODE_PRIVATE)
    private val alias = "pixel_home_sendspin_v1"
    private val lock = Any()
    private val key: SecretKey = run {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: run {
            check(!prefs.contains("blob")) { "Receiver key unavailable. Reset receiver identity to pair again." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    private var root: JSONObject = prefs.getString("blob", null)?.let { envelope ->
        val bytes = Base64.decode(envelope, Base64.NO_WRAP)
        require(bytes.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8))
    } ?: JSONObject().put("identity", ClientIdentity.encodeForStorage(ClientIdentity.generate()))
        .put("pairing", Base64Url.encode(secureRandomBytes(32))).put("records", "[]").also { persist(it) }
    val identity = ClientIdentity.fromStoredKey(root.getString("identity")) ?: error("Receiver identity unavailable")
    private var config = PairingConfig(Base64Url.decodeOrNull(root.getString("pairing")) ?: error("Receiver pairing unavailable"), false, false)
    val trust: TrustStore = object : InMemoryTrustStore(PskRecordCodec.decode(root.getString("records")), config.pairingPskId) {
        override fun onChanged() = listRecords().size <= 16 && change("records", PskRecordCodec.encode(listRecords()))
    }
    val pairing: PairingConfigStore = object : PairingConfigStore {
        override fun load() = synchronized(lock) { config }
        // Hearthline requires pairing; unauthenticated LAN playback cannot be enabled remotely.
        override fun setUnpairedAccess(enabled: Boolean) = !enabled
        override fun rotatePairingPsk(newPsk: ByteArray, claimedPskIds: Set<String>): PairingConfigStore.RotateResult = synchronized(lock) {
            if(newPsk.size != 32) return@synchronized PairingConfigStore.RotateResult.Invalid
            if(PskId.derive(newPsk) in claimedPskIds) return@synchronized PairingConfigStore.RotateResult.AlreadyExists
            if(!change("pairing", Base64Url.encode(newPsk))) return@synchronized PairingConfigStore.RotateResult.StorageFailed
            config = config.withPairingPsk(newPsk)
            PairingConfigStore.RotateResult.Ok
        }
    }
    fun token() = PairingToken.encode(identity.publicKey, config.pairingPsk)
    private fun persist(next: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(next.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("blob", Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) { "Receiver credentials could not be stored" }
    }
    private fun change(field: String, value: String): Boolean = synchronized(lock) {
        try { val next = JSONObject(root.toString()).put(field,value); persist(next); root = next; true } catch(_: Exception) { false }
    }
    companion object {
        fun reset(context: Context) {
            check(context.getSharedPreferences("sendspin_secrets",0).edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("pixel_home_sendspin_v1") }
        }
    }
}
