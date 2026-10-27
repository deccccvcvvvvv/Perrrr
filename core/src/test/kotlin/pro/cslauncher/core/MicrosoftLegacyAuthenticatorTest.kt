package pro.cslauncher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import pro.cslauncher.core.account.Account
import pro.cslauncher.core.account.AccountRepository
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.account.InMemorySecretStore
import pro.cslauncher.core.account.MicrosoftAuthFlow
import pro.cslauncher.core.account.MicrosoftLegacyAuthenticator
import pro.cslauncher.core.net.HttpResponse
import pro.cslauncher.core.net.HttpTransport
import java.io.ByteArrayInputStream
import java.net.URLDecoder

class MicrosoftLegacyAuthenticatorTest {
    @Rule @JvmField val temp = TemporaryFolder()

    @Test fun buildsMicrosoftWebViewAuthorizationUrlWithoutAskingForAnId() {
        val auth = MicrosoftLegacyAuthenticator(FakeTransport(emptyList()))
        val request = auth.authorizationRequest()
        val uri = java.net.URI(request.url)
        val params = uri.rawQuery.split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }

        assertEquals("login.live.com", uri.host)
        assertEquals("oauth20_authorize.srf", uri.path.substringAfterLast('/'))
        assertEquals(MicrosoftLegacyAuthenticator.CLIENT_ID, params["client_id"])
        assertEquals("code", params["response_type"])
        assertEquals(MicrosoftLegacyAuthenticator.SCOPE, params["scope"])
        assertEquals(MicrosoftLegacyAuthenticator.REDIRECT_URL, params["redirect_url"])
        assertEquals(request.state, params["state"])
        assertTrue(request.state.length >= 40)
        assertFalse(params.containsKey("client_secret"))
    }

    @Test fun exchangesAndRefreshesWithTheSameBundledClientAndLegacyEndpoint() {
        val transport = FakeTransport(listOf(
            """{"access_token":"msa-access","refresh_token":"refresh-1","expires_in":3600}""",
            """{"access_token":"msa-access-2","refresh_token":"refresh-2","expires_in":3600}""",
        ))
        val auth = MicrosoftLegacyAuthenticator(transport)

        assertEquals("msa-access", auth.exchangeCode("code + value").accessToken)
        assertEquals("refresh-2", auth.refresh("refresh-1").refreshToken)
        assertEquals(2, transport.requests.size)
        transport.requests.forEach { request ->
            assertEquals("https://login.live.com/oauth20_token.srf", request.url)
            assertTrue(request.body.contains("client_id=${MicrosoftLegacyAuthenticator.CLIENT_ID}"))
            assertTrue(request.body.contains("redirect_url="))
            assertTrue(request.body.contains("scope="))
            assertFalse(request.body.contains("client_secret"))
        }
        assertTrue(transport.requests[0].body.contains("grant_type=authorization_code"))
        assertTrue(transport.requests[0].body.contains("code=code+%2B+value"))
        assertTrue(transport.requests[1].body.contains("grant_type=refresh_token"))
        assertTrue(transport.requests[1].body.contains("refresh_token=refresh-1"))
    }

    @Test fun legacyAccountsPassTheBundledClientIdIntoMinecraftLaunchArguments() {
        val secrets = InMemorySecretStore()
        val repository = AccountRepository(temp.root.toPath().resolve("accounts.json"), secrets)
        val account = Account(
            id = "microsoft-player",
            type = AccountType.MICROSOFT,
            username = "Player",
            uuid = "01234567-89ab-cdef-0123-456789abcdef",
            accessTokenExpiresAtMs = System.currentTimeMillis() + 3_600_000,
            microsoftAuthFlow = MicrosoftAuthFlow.LEGACY_LIVE,
        )
        repository.upsert(account)
        repository.storeMicrosoftTokens(account.id, "minecraft-token", "refresh-token")

        assertEquals(MicrosoftLegacyAuthenticator.CLIENT_ID, repository.session(account, "").clientId)
    }

    private data class Request(val url: String, val body: String)

    private class FakeTransport(responses: List<String>) : HttpTransport {
        private val replies = ArrayDeque(responses)
        val requests = mutableListOf<Request>()

        override fun open(url: String, headers: Map<String, String>, method: String, body: ByteArray?): HttpResponse {
            requests += Request(url, body?.decodeToString().orEmpty())
            val response = replies.removeFirst()
            return HttpResponse(200, emptyMap(), ByteArrayInputStream(response.toByteArray())) {}
        }
    }
}
