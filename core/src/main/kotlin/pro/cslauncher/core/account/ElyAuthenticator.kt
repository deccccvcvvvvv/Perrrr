package pro.cslauncher.core.account

import kotlinx.serialization.json.*
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Ely's documented Yggdrasil API. Passwords are used for this request only, never persisted. */
class ElyAuthenticator(private val http: HttpTransport) {
    data class Login(val account: Account, val token: String, val client: String)
    private fun request(action: String, fields: Map<String, String>): Login {
        val body = buildJsonObject { fields.forEach { (k,v) -> put(k,v) }; put("requestUser", true) }
        val (status, text) = http.postText("https://authserver.ely.by/auth/$action", body.toString(), "application/json")
        if (status != 200) throw AuthException(when (status) {
            401 -> "Ely.by rejected the credentials/session. If 2FA is enabled enter the current code."
            429 -> "Too many Ely.by attempts. Please wait before retrying."
            else -> "Ely.by authentication failed (HTTP $status)."
        })
        val o = parseJsonObject(text, "Ely.by response")
        val profile = o.obj("selectedProfile") ?: throw AuthException("Ely.by returned no Minecraft profile")
        val uuid = profile.str("id") ?: throw AuthException("Missing Ely.by UUID")
        require(Regex("[a-fA-F0-9-]{32,36}").matches(uuid)) { "Invalid Ely.by profile UUID" }
        val account = Account("ely-" + uuid.replace("-", ""), AccountType.ELY_BY,
            profile.str("name") ?: throw AuthException("Missing Ely.by username"), uuid)
        return Login(account, o.str("accessToken") ?: throw AuthException("Missing Ely.by session"),
            o.str("clientToken") ?: throw AuthException("Missing Ely.by client identity"))
    }
    fun login(username: String, password: String, otp: String = ""): Login {
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter your Ely.by login and password" }
        return request("authenticate", mapOf("username" to username, "password" to
            if (otp.isBlank()) password else "$password:$otp", "clientToken" to UUID.randomUUID().toString()))
    }
    fun refresh(token: String, client: String) = request("refresh", mapOf("accessToken" to token, "clientToken" to client))

    /** Pinned upstream agent; no executable from arbitrary metadata is trusted. */
    fun ensureAgent(dir: Path): Path {
        val target = dir.resolve("authlib-injector-1.2.8.jar")
        val hash = "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10"
        if (Files.isRegularFile(target) && Hashing.sha256(target) == hash) return target
        val bytes = http.open("https://authlib-injector.yushi.moe/artifact/56/authlib-injector-1.2.8.jar").use {
            if (it.code != 200) throw AuthException("Unable to download Ely.by authentication agent")
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = it.body.read(buffer); if (n < 0) break
                require(out.size() + n <= 8 * 1024 * 1024) { "Authentication agent too large" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        require(Hashing.sha256(bytes) == hash) { "Authentication agent checksum mismatch" }
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "authlib-", ".part")
        try { Files.write(temp, bytes); Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp) }
        return target
    }
}
