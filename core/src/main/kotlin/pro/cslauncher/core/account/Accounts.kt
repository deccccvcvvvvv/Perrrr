package pro.cslauncher.core.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.*
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Serializable enum class AccountType { MICROSOFT, OFFLINE, ELY_BY }
@Serializable enum class MicrosoftAuthFlow { LEGACY_LIVE, AZURE_V2 }

/** Non-secret account metadata. Tokens live only in a [SecretStore] (Android Keystore backed on device). */
@Serializable data class Account(
    val id: String, val type: AccountType, val username: String, val uuid: String,
    val xuid: String? = null, val addedAtMs: Long = System.currentTimeMillis(),
    val accessTokenExpiresAtMs: Long? = null, val needsRelogin: Boolean = false,
    val skinUrl: String? = null,
    /** Defaults to null for old account files; new Microsoft logins record which token endpoint minted the refresh token. */
    val microsoftAuthFlow: MicrosoftAuthFlow? = null,
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
        listOf("mc", "msaRefresh", "elyClient").forEach { secrets.remove("$id.$it") }
    }

    fun storeMicrosoftTokens(id: String, mcAccessToken: String, msaRefreshToken: String) {
        secrets.put("$id.mc", mcAccessToken); secrets.put("$id.msaRefresh", msaRefreshToken)
    }
    fun storeElyTokens(id: String, token: String, client: String) {
        secrets.put("$id.mc", token); secrets.put("$id.elyClient", client)
    }
    fun elyTokens(id: String): Pair<String, String> =
        (secrets.get("$id.mc") ?: throw AuthException("Ely.by session missing; sign in again")) to
        (secrets.get("$id.elyClient") ?: throw AuthException("Ely.by session missing; sign in again"))
    fun refreshToken(id: String) = secrets.get("$id.msaRefresh")

    fun session(acc: Account, clientId: String): LaunchSession = when (acc.type) {
        AccountType.OFFLINE -> LaunchSession(acc.username, acc.uuid.replace("-", ""), "0", "legacy", "0", clientId.ifBlank { "0" }, true)
        AccountType.ELY_BY -> LaunchSession(acc.username, acc.uuid.replace("-", ""), elyTokens(acc.id).first, "mojang", "0", "0", false)
        AccountType.MICROSOFT -> {
            val token = secrets.get("${acc.id}.mc") ?: throw AuthException("No stored session for ${acc.username}. Sign in again.")
            if ((acc.accessTokenExpiresAtMs ?: 0) < System.currentTimeMillis() + 60_000) throw AuthException("The Minecraft session for ${acc.username} expired. Refresh required.")
            val gameClientId = if (acc.microsoftAuthFlow == MicrosoftAuthFlow.LEGACY_LIVE) MicrosoftLegacyAuthenticator.CLIENT_ID else clientId.ifBlank { "0" }
            LaunchSession(acc.username, acc.uuid.replace("-", ""), token, "msa", acc.xuid ?: "0", gameClientId, false)
        }
    }
}

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val expiresInSec: Int, var intervalSec: Int)
data class MsaTokens(val accessToken: String, val refreshToken: String)
data class MinecraftLogin(val accessToken: String, val expiresInSec: Long, val uuid: String, val name: String, val xuid: String?, val ownsGame: Boolean, val skinUrl: String? = null)

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
            "authorization_pending" -> null
            // OAuth device flow requires the client to lengthen its polling interval after slow_down.
            "slow_down" -> { dc.intervalSec = (dc.intervalSec + 5).coerceAtMost(60); null }
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

    fun loginMinecraft(msaAccessToken: String, rpsTicketPrefix: String = "d="): MinecraftLogin {
        val json = "application/json"
        val xbl = parseJsonObject(http.postText("https://user.auth.xboxlive.com/user/authenticate",
            """{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"$rpsTicketPrefix$msaAccessToken"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}""", json, mapOf("Accept" to json)).also {
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
        return MinecraftLogin(token, mco.long("expires_in") ?: 86400, uuid, profile.str("name")!!, xuid, owns, profile.arr("skins")?.firstOrNull()?.jsonObject?.str("url"))
    }
}

/**
 * Minecraft-launcher-compatible Microsoft Live authorization-code flow used by MojoLauncher/Pojav.
 * The client id is bundled, so players never configure an Azure id. This is a legacy, third-party
 * launcher compatibility path and Microsoft/Mojang may change or block it without notice.
 */
data class MicrosoftWebAuthorization(val url: String, val state: String)

class MicrosoftLegacyAuthenticator(private val http: HttpTransport) {
    companion object {
        const val CLIENT_ID = "00000000402b5328"
        const val CALLBACK_SCHEME = "ms-xal-$CLIENT_ID"
        const val REDIRECT_URL = "https://login.live.com/oauth20_desktop.srf"
        const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        private const val AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf"
        private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    fun authorizationRequest(): MicrosoftWebAuthorization {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val params = listOf(
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "scope" to SCOPE,
            "redirect_url" to REDIRECT_URL,
            "state" to state,
        ).joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        return MicrosoftWebAuthorization("$AUTHORIZE_URL?$params", state)
    }

    fun exchangeCode(code: String): MsaTokens {
        require(code.isNotBlank() && code.length <= 8192) { "Microsoft returned an invalid sign-in code" }
        return exchange(
            "code" to code,
            "grant_type" to "authorization_code",
        )
    }

    fun refresh(refreshToken: String): MsaTokens {
        require(refreshToken.isNotBlank() && refreshToken.length <= 16_384) { "Stored Microsoft refresh token is invalid; sign in again" }
        return exchange(
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
        )
    }

    private fun exchange(grant: Pair<String, String>, type: Pair<String, String>): MsaTokens {
        val body = form(
            "client_id" to CLIENT_ID,
            grant.first to grant.second,
            type.first to type.second,
            "redirect_url" to REDIRECT_URL,
            "scope" to SCOPE,
        )
        val (status, response) = http.postText(TOKEN_URL, body, "application/x-www-form-urlencoded")
        val json = runCatching { parseJsonObject(response, "Microsoft token response") }
            .getOrElse { throw AuthException("Microsoft returned an unreadable token response (HTTP $status)", it) }
        if (status !in 200..299) {
            val reason = json.str("error_description") ?: json.str("error") ?: "HTTP $status"
            throw AuthException("Microsoft sign-in failed: ${reason.take(240)}")
        }
        val access = json.str("access_token") ?: throw AuthException("Microsoft did not return an access token")
        val refresh = json.str("refresh_token")
            ?: (if (grant.first == "refresh_token") grant.second else null)
            ?: throw AuthException("Microsoft did not return a refresh token")
        return MsaTokens(access, refresh)
    }

    /** The legacy Live token is sent as the RPS ticket, as in the reference mobile launchers. */
    fun loginMinecraft(msaAccessToken: String): MinecraftLogin =
        MicrosoftAuthenticator(http, CLIENT_ID).loginMinecraft(msaAccessToken, rpsTicketPrefix = "")
}
