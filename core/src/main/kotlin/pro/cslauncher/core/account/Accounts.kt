package pro.cslauncher.core.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.*
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Serializable enum class AccountType { MICROSOFT, OFFLINE }

/** Non-secret account metadata. Tokens live only in a [SecretStore] (Android Keystore backed on device). */
@Serializable data class Account(
    val id: String, val type: AccountType, val username: String, val uuid: String,
    val xuid: String? = null, val addedAtMs: Long = System.currentTimeMillis(),
    val accessTokenExpiresAtMs: Long? = null, val needsRelogin: Boolean = false,
)

interface SecretStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}

class InMemorySecretStore : SecretStore {
    private val m = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun put(key: String, value: String) { m[key] = value }
    override fun get(key: String) = m[key]
    override fun remove(key: String) { m.remove(key) }
}

/** Values substituted into launch arguments. [accessToken] is never logged (see Redactor). */
data class LaunchSession(val username: String, val uuid: String, val accessToken: String, val userType: String, val xuid: String, val clientId: String, val isOffline: Boolean) {
    override fun toString() = "LaunchSession(username=$username, uuid=$uuid, userType=$userType, offline=$isOffline)"
}

object OfflineIdentity {
    private val NAME = Regex("^[A-Za-z0-9_]{3,16}$")
    fun validName(n: String) = NAME.matches(n)
    /** Same derivation the vanilla server uses for offline-mode players. */
    fun uuidFor(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
}

@Serializable data class AccountsFile(val schemaVersion: Int = 1, val activeId: String? = null, val accounts: List<Account> = emptyList())

class AccountRepository(private val file: Path, private val secrets: SecretStore) {
    @Synchronized fun load(): AccountsFile = if (!Files.isRegularFile(file)) AccountsFile() else CsJson.decodeFromString(AccountsFile.serializer(), pro.cslauncher.core.util.FsUtil.readText(file))
    @Synchronized private fun save(a: AccountsFile) = FsUtil.writeAtomic(file, CsJson.encodeToString(AccountsFile.serializer(), a))

    fun addOffline(name: String): Account {
        require(OfflineIdentity.validName(name)) { "Offline names must be 3-16 characters: letters, digits or underscore" }
        val acc = Account("offline-" + name.lowercase(), AccountType.OFFLINE, name, OfflineIdentity.uuidFor(name).toString())
        upsert(acc); return acc
    }

    @Synchronized fun upsert(acc: Account) { val f = load(); save(f.copy(accounts = f.accounts.filterNot { it.id == acc.id } + acc, activeId = f.activeId ?: acc.id)) }
    @Synchronized fun setActive(id: String) { val f = load(); require(f.accounts.any { it.id == id }); save(f.copy(activeId = id)) }

    /** Logout: removes metadata and every stored secret for the account. */
    @Synchronized fun remove(id: String) {
        val f = load()
        save(f.copy(accounts = f.accounts.filterNot { it.id == id }, activeId = if (f.activeId == id) f.accounts.firstOrNull { it.id != id }?.id else f.activeId))
        listOf("mc", "msaRefresh").forEach { secrets.remove("$id.$it") }
    }

    fun storeMicrosoftTokens(id: String, mcAccessToken: String, msaRefreshToken: String) {
        secrets.put("$id.mc", mcAccessToken); secrets.put("$id.msaRefresh", msaRefreshToken)
    }
    fun refreshToken(id: String) = secrets.get("$id.msaRefresh")

    fun session(acc: Account, clientId: String): LaunchSession = when (acc.type) {
        AccountType.OFFLINE -> LaunchSession(acc.username, acc.uuid.replace("-", ""), "0", "legacy", "0", clientId.ifBlank { "0" }, true)
        AccountType.MICROSOFT -> {
            val token = secrets.get("${acc.id}.mc") ?: throw AuthException("No stored session for ${acc.username}. Sign in again.")
            if ((acc.accessTokenExpiresAtMs ?: 0) < System.currentTimeMillis() + 60_000) throw AuthException("The Minecraft session for ${acc.username} expired. Refresh required.")
            LaunchSession(acc.username, acc.uuid.replace("-", ""), token, "msa", acc.xuid ?: "0", clientId.ifBlank { "0" }, false)
        }
    }
}

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val expiresInSec: Int, val intervalSec: Int)
data class MsaTokens(val accessToken: String, val refreshToken: String)
data class MinecraftLogin(val accessToken: String, val expiresInSec: Long, val uuid: String, val name: String, val xuid: String?, val ownsGame: Boolean)

/**
 * Microsoft account sign-in with the OAuth 2.0 device authorization grant (no embedded browser, no password handling):
 *  MSA device code -> MSA token -> Xbox Live user token -> XSTS token -> Minecraft services token -> entitlement + profile.
 * Requires an Azure application (client) id that Microsoft has approved for Minecraft services. Public clients
 * have no secret, so nothing private is embedded in the APK.
 */
class MicrosoftAuthenticator(private val http: HttpTransport, private val clientId: String) {
    private val authority = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }

    init { if (clientId.isBlank()) throw AuthException("Microsoft sign-in needs an Azure client id (Settings › Accounts).") }

    fun startDeviceCode(): DeviceCode {
        val (code, body) = http.postText("$authority/devicecode", form("client_id" to clientId, "scope" to "XboxLive.signin offline_access"), "application/x-www-form-urlencoded")
        val o = parseJsonObject(body, "device code response")
        if (code != 200) throw AuthException("Microsoft rejected the sign-in request: ${o.str("error_description") ?: code}")
        return DeviceCode(o.str("user_code")!!, o.str("verification_uri") ?: "https://microsoft.com/link", o.str("device_code")!!, o.int("expires_in") ?: 900, o.int("interval") ?: 5)
    }

    /** One poll; returns null while authorization is pending. */
    fun pollDeviceCode(dc: DeviceCode): MsaTokens? {
        val (_, body) = http.postText("$authority/token", form("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to dc.deviceCode), "application/x-www-form-urlencoded")
        val o = parseJsonObject(body, "token response")
        return when (o.str("error")) {
            null -> MsaTokens(o.str("access_token")!!, o.str("refresh_token")!!)
            "authorization_pending", "slow_down" -> null
            "authorization_declined" -> throw AuthException("Sign-in was declined")
            "expired_token" -> throw AuthException("The sign-in code expired. Start again.")
            else -> throw AuthException("Sign-in failed: ${o.str("error_description") ?: o.str("error")}")
        }
    }

    fun refresh(refreshToken: String): MsaTokens {
        val (code, body) = http.postText("$authority/token", form("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken, "scope" to "XboxLive.signin offline_access"), "application/x-www-form-urlencoded")
        val o = parseJsonObject(body, "refresh response")
        if (code != 200) throw AuthException("Session refresh failed (${o.str("error") ?: code}). Sign in again.")
        return MsaTokens(o.str("access_token")!!, o.str("refresh_token") ?: refreshToken)
    }

    fun loginMinecraft(msaAccessToken: String): MinecraftLogin {
        val json = "application/json"
        val xbl = parseJsonObject(http.postText("https://user.auth.xboxlive.com/user/authenticate",
            """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$msaAccessToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}""", json, mapOf("Accept" to json)).also {
            if (it.first != 200) throw AuthException("Xbox Live authentication failed (HTTP ${it.first})") }.second, "XBL")
        val xblToken = xbl.str("Token")!!
        val uhs = xbl.obj("DisplayClaims")?.arr("xui")?.firstOrNull()?.jsonObject?.str("uhs") ?: throw AuthException("Xbox Live response has no user hash")
        val (xc, xb) = http.postText("https://xsts.auth.xboxlive.com/xsts/authorize",
            """{"Properties":{"SandboxId":"RETAIL","UserTokens":["$xblToken"]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}""", json, mapOf("Accept" to json))
        val xsts = parseJsonObject(xb, "XSTS")
        if (xc != 200) throw AuthException(when (xsts.long("XErr")) {
            2148916233 -> "This Microsoft account has no Xbox profile. Create one at xbox.com first."
            2148916235 -> "Xbox Live is not available in your country/region."
            2148916238 -> "Child account: an adult must add this account to a Microsoft family."
            else -> "XSTS authorization failed (HTTP $xc)"
        })
        val xstsToken = xsts.str("Token")!!
        val xuid = xsts.obj("DisplayClaims")?.arr("xui")?.firstOrNull()?.jsonObject?.str("xid")
        val (mc, mb) = http.postText("https://api.minecraftservices.com/authentication/login_with_xbox", """{"identityToken":"XBL3.0 x=$uhs;$xstsToken"}""", json)
        if (mc != 200) throw AuthException("Minecraft services rejected the login (HTTP $mc). The client id may not be approved for Minecraft.")
        val mco = parseJsonObject(mb, "Minecraft login")
        val token = mco.str("access_token")!!
        val bearer = mapOf("Authorization" to "Bearer $token")
        val ent = runCatching { parseJsonObject(http.getText("https://api.minecraftservices.com/entitlements/mcstore", bearer), "entitlements") }.getOrNull()
        val owns = ent?.arr("items")?.isNotEmpty() == true
        val profile = runCatching { parseJsonObject(http.getText("https://api.minecraftservices.com/minecraft/profile", bearer), "profile") }
            .getOrElse { throw AuthException(if (!owns) "This account does not own Minecraft: Java Edition." else "Could not read the Minecraft profile. Has a username been chosen?") }
        val rawId = profile.str("id")!!
        val uuid = UUID(java.lang.Long.parseUnsignedLong(rawId.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(rawId.substring(16), 16)).toString()
        return MinecraftLogin(token, mco.long("expires_in") ?: 86400, uuid, profile.str("name")!!, xuid, owns)
    }
}
