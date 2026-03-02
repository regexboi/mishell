package ai.mishell.app

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.mishell.app.network.ClawdiaEndpoint
import ai.mishell.app.network.parseClawdiaEndpoint
import java.util.UUID

object AppSettings {
    private const val PREFS_NAME = "mishell_settings"
    private const val SECURE_PREFS_NAME = "mishell_secure_settings"

    private const val KEY_WISPR_TEXT_MODE = "wispr_text_mode"
    private const val KEY_ALWAYS_ON_ULTRA_DIM = "always_on_ultra_dim"
    private const val KEY_ORIENTATION_LOCK = "orientation_lock"
    private const val KEY_BACKEND_MODE = "backend_mode"
    private const val KEY_CLAWDIA_GATEWAY_URL = "clawdia_gateway_url"
    private const val KEY_INSTANCE_ID = "app_instance_id"
    private const val KEY_NEON_CONNECTION_STRING = "neon_connection_string"

    private const val VALUE_BACKEND_MISHELL = "mishell"
    private const val VALUE_BACKEND_CLAWDIA = "clawdia"

    enum class BackendMode { MISHELL, CLAWDIA }

    data class ClawdiaConnectionConfig(
        val endpoint: ClawdiaEndpoint,
        val token: String,
        val password: String,
        val expectedFingerprint: String?
    )

    fun isWisprTextModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WISPR_TEXT_MODE, false)
    }

    fun setWisprTextModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WISPR_TEXT_MODE, enabled)
            .apply()
    }

    fun isAlwaysOnUltraDimEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_ON_ULTRA_DIM, true)
    }

    fun setAlwaysOnUltraDimEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALWAYS_ON_ULTRA_DIM, enabled)
            .apply()
    }

    fun isOrientationLockEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ORIENTATION_LOCK, true)
    }

    fun setOrientationLockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ORIENTATION_LOCK, enabled)
            .apply()
    }

    fun getBackendMode(context: Context): BackendMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_MODE, VALUE_BACKEND_MISHELL)
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return when (raw) {
            VALUE_BACKEND_CLAWDIA -> BackendMode.CLAWDIA
            else -> BackendMode.MISHELL
        }
    }

    fun setBackendMode(context: Context, mode: BackendMode) {
        val value = when (mode) {
            BackendMode.MISHELL -> VALUE_BACKEND_MISHELL
            BackendMode.CLAWDIA -> VALUE_BACKEND_CLAWDIA
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_MODE, value)
            .apply()
    }

    fun getClawdiaGatewayUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = prefs.getString(KEY_CLAWDIA_GATEWAY_URL, null)?.trim().orEmpty()
        return if (persisted.isNotEmpty()) {
            persisted
        } else {
            BuildConfig.CLAWDIA_GATEWAY_URL
        }
    }

    fun setClawdiaGatewayUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_CLAWDIA_GATEWAY_URL, value.trim())
            }
    }

    fun getClawdiaToken(context: Context): String {
        return securePrefs(context).getString("clawdia_gateway_token", null)
            ?.trim()
            .orEmpty()
    }

    fun setClawdiaToken(context: Context, value: String) {
        securePrefs(context).edit {
            putString("clawdia_gateway_token", value.trim())
        }
    }

    fun getClawdiaPassword(context: Context): String {
        return securePrefs(context).getString("clawdia_gateway_password", null)
            ?.trim()
            .orEmpty()
    }

    fun setClawdiaPassword(context: Context, value: String) {
        securePrefs(context).edit {
            putString("clawdia_gateway_password", value.trim())
        }
    }

    fun getNeonConnectionString(context: Context): String {
        val secure = securePrefs(context).getString(KEY_NEON_CONNECTION_STRING, null)
            ?.trim()
            .orEmpty()
        if (secure.isNotEmpty()) {
            return secure
        }

        val fallback = BuildConfig.NEON_STRING.trim()
        if (fallback.isNotEmpty()) {
            setNeonConnectionString(context, fallback)
        }
        return fallback
    }

    fun setNeonConnectionString(context: Context, value: String) {
        securePrefs(context).edit {
            putString(KEY_NEON_CONNECTION_STRING, value.trim())
        }
    }

    fun getTrustedTlsFingerprint(context: Context, stableId: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("clawdia_tls_pin_$stableId", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setTrustedTlsFingerprint(context: Context, stableId: String, fingerprint: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("clawdia_tls_pin_$stableId", fingerprint.trim())
        }
    }

    fun getDeviceRoleToken(context: Context, deviceId: String, role: String): String? {
        return securePrefs(context)
            .getString(deviceRoleTokenKey(deviceId, role), null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setDeviceRoleToken(context: Context, deviceId: String, role: String, token: String) {
        securePrefs(context).edit {
            putString(deviceRoleTokenKey(deviceId, role), token.trim())
        }
    }

    fun getOrCreateInstanceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTANCE_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        prefs.edit {
            putString(KEY_INSTANCE_ID, generated)
        }
        return generated
    }

    fun getClawdiaConnectionConfig(context: Context): ClawdiaConnectionConfig? {
        val endpoint = parseClawdiaEndpoint(getClawdiaGatewayUrl(context)) ?: return null
        val token = getClawdiaToken(context)
        val password = getClawdiaPassword(context)
        val fingerprint = getTrustedTlsFingerprint(context, endpoint.stableId)
        return ClawdiaConnectionConfig(
            endpoint = endpoint,
            token = token,
            password = password,
            expectedFingerprint = fingerprint
        )
    }

    private fun deviceRoleTokenKey(deviceId: String, role: String): String {
        val normalizedDevice = deviceId.trim().lowercase()
        val normalizedRole = role.trim().lowercase()
        return "clawdia_device_token_${normalizedDevice}_$normalizedRole"
    }

    @Suppress("DEPRECATION")
    private fun securePrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            SECURE_PREFS_NAME,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}
