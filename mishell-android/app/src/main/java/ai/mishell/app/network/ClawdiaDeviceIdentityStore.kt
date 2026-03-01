package ai.mishell.app.network

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

data class ClawdiaDeviceIdentity(
    val deviceId: String,
    val publicKeyRawBase64: String,
    val privateKeyPkcs8Base64: String,
    val createdAtMs: Long
)

class ClawdiaDeviceIdentityStore(context: Context) {
    private val identityFile = File(context.filesDir, "clawdia/identity/device.json")

    @Volatile
    private var cached: ClawdiaDeviceIdentity? = null

    @Synchronized
    fun loadOrCreate(): ClawdiaDeviceIdentity {
        cached?.let { return it }
        val existing = load()
        if (existing != null) {
            cached = existing
            return existing
        }

        val fresh = generate()
        save(fresh)
        cached = fresh
        return fresh
    }

    fun signPayload(payload: String, identity: ClawdiaDeviceIdentity): String? {
        return runCatching {
            val privateKeyBytes = Base64.decode(identity.privateKeyPkcs8Base64, Base64.DEFAULT)
            val pkInfo = PrivateKeyInfo.getInstance(privateKeyBytes)
            val parsed = pkInfo.parsePrivateKey()
            val rawPrivate = DEROctetString.getInstance(parsed).octets
            val privateKey = Ed25519PrivateKeyParameters(rawPrivate, 0)

            val signer = Ed25519Signer()
            signer.init(true, privateKey)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            signer.update(bytes, 0, bytes.size)
            base64UrlEncode(signer.generateSignature())
        }.getOrNull()
    }

    fun publicKeyBase64Url(identity: ClawdiaDeviceIdentity): String? {
        return runCatching {
            val raw = Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT)
            base64UrlEncode(raw)
        }.getOrNull()
    }

    private fun load(): ClawdiaDeviceIdentity? {
        return runCatching {
            if (!identityFile.exists()) return null
            val json = JSONObject(identityFile.readText(Charsets.UTF_8))
            val deviceId = json.optString("deviceId").trim()
            val pub = json.optString("publicKeyRawBase64").trim()
            val priv = json.optString("privateKeyPkcs8Base64").trim()
            val createdAt = json.optLong("createdAtMs", 0L)
            if (deviceId.isEmpty() || pub.isEmpty() || priv.isEmpty()) {
                null
            } else {
                ClawdiaDeviceIdentity(
                    deviceId = deviceId,
                    publicKeyRawBase64 = pub,
                    privateKeyPkcs8Base64 = priv,
                    createdAtMs = createdAt
                )
            }
        }.getOrNull()
    }

    private fun save(identity: ClawdiaDeviceIdentity) {
        runCatching {
            identityFile.parentFile?.mkdirs()
            val json = JSONObject()
                .put("deviceId", identity.deviceId)
                .put("publicKeyRawBase64", identity.publicKeyRawBase64)
                .put("privateKeyPkcs8Base64", identity.privateKeyPkcs8Base64)
                .put("createdAtMs", identity.createdAtMs)
            identityFile.writeText(json.toString(), Charsets.UTF_8)
        }
    }

    private fun generate(): ClawdiaDeviceIdentity {
        val keyGen = Ed25519KeyPairGenerator()
        keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyGen.generateKeyPair()
        val publicKey = keyPair.public as Ed25519PublicKeyParameters
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters

        val rawPublic = publicKey.encoded
        val deviceId = sha256Hex(rawPublic)

        val pkcs8 = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey).encoded
        return ClawdiaDeviceIdentity(
            deviceId = deviceId,
            publicKeyRawBase64 = Base64.encodeToString(rawPublic, Base64.NO_WRAP),
            privateKeyPkcs8Base64 = Base64.encodeToString(pkcs8, Base64.NO_WRAP),
            createdAtMs = System.currentTimeMillis()
        )
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef".toCharArray()
        var index = 0
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            chars[index++] = hex[value ushr 4]
            chars[index++] = hex[value and 0x0f]
        }
        return String(chars)
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
