package pro.cslauncher.core.account

import kotlinx.serialization.json.*
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Ely.by's documented Yggdrasil API (https://docs.ely.by/en/minecraft-auth.html).
 *
 * The password exists only for the duration of the /auth/authenticate call and is never written to
 * disk, logged, or passed to the launch plan. Only the resulting access/client tokens are stored,
 * in the same Keystore-backed [SecretStore] as Microsoft sessions.
 */
class ElyAuthenticator(private val http: HttpTransport) {
    data class Login(val account: Account, val token: String, val client: String)

    companion object {
        /** Ely's authlib-injector metadata endpoint (the agent redirects in-game auth to Ely.by). */
        const val AUTHLIB_INJECTOR_API = "https://authserver.ely.by/api/authlib-injector"
        private const val AUTH = "https://authserver.ely.by/auth"
        private const val AGENT_URL = "https://authlib-injector.yushi.moe/artifact/56/authlib-injector-1.2.8.jar"
        private const val AGENT_SHA256 = "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10"
        private const val AGENT_MAX_BYTES = 8 * 1024 * 1024
    }

    private fun raw(action: String, fields: Map<String, String>): JsonObject {
        val body = buildJsonObject {
            fields.forEach { (k, v) -> put(k, v) }
            put("requestUser", true)
        }
        val (status, text) = http.postText("$AUTH/$action", body.toString(), "application/json")
        if (status != 200) throw AuthException(when (status) {
            401 -> "Ely.by rejected the credentials or session. If two-factor authentication is enabled, enter the current code."
            403 -> "Ely.by refused this request. Check the account and the two-factor code."
            429 -> "Too many Ely.by attempts. Wait a few minutes before trying again."
            else -> "Ely.by authentication failed (HTTP $status). Check the connection and try again."
        })
        return parseJsonObject(text, "Ely.by response")
    }

    private fun accountFrom(profile: JsonObject): Account {
        val uuid = profile.str("id") ?: throw AuthException("Ely.by returned no Minecraft profile id")
        require(Regex("[a-fA-F0-9-]{32,36}").matches(uuid)) { "Invalid Ely.by profile UUID" }
        val name = profile.str("name") ?: throw AuthException("Ely.by returned no Minecraft username")
        return Account("ely-" + uuid.replace("-", ""), AccountType.ELY_BY, name, uuid)
    }

    fun login(username: String, password: String, otp: String = ""): Login {
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter your Ely.by login and password" }
        // Ely.by's documented 2FA form is "password:oneTimeCode".
        val secret = if (otp.isBlank()) password else "$password:${otp.trim()}"
        val o = raw("authenticate", mapOf(
            "username" to username.trim(),
            "password" to secret,
            "clientToken" to UUID.randomUUID().toString(),
        ))
        val token = o.str("accessToken")?.takeIf { it.isNotBlank() } ?: throw AuthException("Ely.by returned no access token")
        val client = o.str("clientToken")?.takeIf { it.isNotBlank() } ?: throw AuthException("Ely.by returned no client identity")
        val profile = o.obj("selectedProfile") ?: throw AuthException("Ely.by returned no Minecraft profile. Finish account setup at ely.by first.")
        return Login(accountFrom(profile), token, client)
    }

    /**
     * Refreshes a session. Ely's /auth/refresh response does not always repeat `selectedProfile`,
     * so the stored account is accepted as a fallback identity instead of forcing a re-login.
     */
    fun refresh(token: String, client: String, existing: Account? = null): Login {
        require(token.isNotBlank() && client.isNotBlank()) { "Ely.by session missing; sign in again" }
        val o = raw("refresh", mapOf("accessToken" to token, "clientToken" to client))
        val newToken = o.str("accessToken")?.takeIf { it.isNotBlank() } ?: throw AuthException("Ely.by refresh returned no access token. Sign in again.")
        val newClient = o.str("clientToken")?.takeIf { it.isNotBlank() } ?: client
        val profile = o.obj("selectedProfile")
        val account = if (profile != null) accountFrom(profile) else existing
            ?: throw AuthException("Ely.by refresh returned no profile. Sign in again.")
        return Login(account, newToken, newClient)
    }

    /** Pinned upstream agent; no executable from arbitrary metadata is trusted. */
    fun ensureAgent(dir: Path): Path {
        val target = dir.resolve("authlib-injector-1.2.8.jar")
        if (Files.isRegularFile(target) && Hashing.sha256(target) == AGENT_SHA256) return target
        val bytes = http.open(AGENT_URL).use { response ->
            if (response.code != 200) throw AuthException("Unable to download the Ely.by authentication agent (HTTP ${response.code}). Check the connection and try again.")
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = response.body.read(buffer); if (n < 0) break
                require(out.size() + n <= AGENT_MAX_BYTES) { "Authentication agent too large" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        require(Hashing.sha256(bytes) == AGENT_SHA256) { "Authentication agent checksum mismatch; refusing to use it. Try again later." }
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "authlib-", ".part")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return target
    }
}
