package io.github.nyzeep.orchestella.ssh

import org.json.JSONObject
import java.util.UUID

/**
 * SSH Connection Profile model (aligned with Termius / Termux).
 * Supports local guest VM forwarded ports as well as remote Linux hosts.
 */
data class SshProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String = "127.0.0.1",
    val port: Int = 22,
    val username: String = "root",
    val authMode: AuthMode = AuthMode.PASSWORD,
    val passwordOrKey: String = "",
    val instanceId: String? = null
) {
    enum class AuthMode {
        PASSWORD,
        PRIVATE_KEY,
        AGENT_NONE
    }

    /**
     * Formats canonical OpenSSH command line string.
     */
    fun buildCliCommand(): String {
        val portParam = if (port == 22) "" else "-p $port "
        return "ssh ${portParam}$username@$host"
    }

    /**
     * Formats URI for standard mobile SSH client handlers (e.g. Termux, ConnectBot, JuiceSSH).
     */
    fun buildUriString(): String {
        return "ssh://$username@$host:$port"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authMode", authMode.name)
        put("passwordOrKey", passwordOrKey)
        put("instanceId", instanceId ?: "")
    }

    companion object {
        fun fromJson(json: JSONObject): SshProfile {
            return SshProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                label = json.optString("label", "SSH Profile"),
                host = json.optString("host", "127.0.0.1"),
                port = json.optInt("port", 22),
                username = json.optString("username", "root"),
                authMode = try {
                    AuthMode.valueOf(json.optString("authMode", AuthMode.PASSWORD.name))
                } catch (e: Exception) {
                    AuthMode.PASSWORD
                },
                passwordOrKey = json.optString("passwordOrKey", ""),
                instanceId = json.optString("instanceId").takeIf { !it.isNullOrBlank() }
            )
        }
    }
}
