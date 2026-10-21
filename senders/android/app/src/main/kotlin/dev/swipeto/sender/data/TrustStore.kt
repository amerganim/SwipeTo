package dev.swipeto.sender.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.swipeto.sender.net.SenderIdentity
import java.util.UUID

/** Trust tokens issued by receivers, keyed by receiver id. */
interface TrustTokens {
    fun get(receiverId: String): String?
    fun put(receiverId: String, token: String)
    fun remove(receiverId: String)
}

/**
 * This phone's stable sender id and the trust token each paired receiver issued. Kept in app-private
 * preferences: tokens only admit a session on the local network (docs/protocol.md, section 6).
 */
class TrustStore(context: Context) : TrustTokens {
    private val prefs = context.getSharedPreferences("swipeto_trust", Context.MODE_PRIVATE)
    private val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)

    val identity: SenderIdentity by lazy {
        val id = prefs.getString(KEY_SENDER_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_SENDER_ID, it).apply()
        }
        val name = deviceName?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        SenderIdentity(id, name.take(64))
    }

    override fun get(receiverId: String): String? = prefs.getString(KEY_TOKEN + receiverId, null)

    override fun put(receiverId: String, token: String) = prefs.edit().putString(KEY_TOKEN + receiverId, token).apply()

    override fun remove(receiverId: String) = prefs.edit().remove(KEY_TOKEN + receiverId).apply()

    fun isPaired(receiverId: String) = get(receiverId) != null

    private companion object {
        const val KEY_SENDER_ID = "sender_id"
        const val KEY_TOKEN = "token."
    }
}
