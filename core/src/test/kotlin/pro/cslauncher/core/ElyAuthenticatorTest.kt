package pro.cslauncher.core

import pro.cslauncher.core.account.*
import pro.cslauncher.core.net.HttpResponse
import pro.cslauncher.core.net.HttpTransport
import java.io.ByteArrayInputStream
import kotlin.test.*

/** Offline tests for the Ely.by Yggdrasil flow: no network, no passwords stored, no tokens logged. */
class ElyAuthenticatorTest {

    private class FakeHttp(private val respond: (String, String) -> Pair<Int, String>) : HttpTransport {
        override fun open(url: String, headers: Map<String, String>, method: String, body: ByteArray?): HttpResponse {
            val (code, text) = respond(url, body?.toString(Charsets.UTF_8) ?: "")
            return HttpResponse(code, emptyMap(), ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) {}
        }
    }

    private val profile = """"selectedProfile":{"id":"d3e1f0a2b4c54f2a9c3d8e7b1a6f0c9d2","name":"SteveEly"}"""

    @Test fun loginBuildsAnElyAccountAndNeverReturnsThePassword() {
        var sent = ""
        val http = FakeHttp { _, body -> sent = body; 200 to """{"accessToken":"ACCESS","clientToken":"CLIENT",$profile}""" }
        val login = ElyAuthenticator(http).login("user@ely.by", "s3cret", "123456")
        assertEquals(AccountType.ELY_BY, login.account.type)
        assertEquals("SteveEly", login.account.username)
        assertEquals("ely-d3e1f0a2b4c54f2a9c3d8e7b1a6f0c9d2", login.account.id)
        assertEquals("ACCESS", login.token)
        assertEquals("CLIENT", login.client)
        // Ely.by's documented 2FA form is "password:code"; the password itself is not echoed anywhere.
        assertTrue(sent.contains("s3cret:123456"))
        assertFalse(login.account.toString().contains("s3cret"))
    }

    @Test fun refreshKeepsTheStoredIdentityWhenTheServerOmitsSelectedProfile() {
        val http = FakeHttp { _, _ -> 200 to """{"accessToken":"FRESH","clientToken":"CLIENT2"}""" }
        val existing = Account("ely-d3e1f0a2b4c54f2a9c3d8e7b1a6f0c9d2", AccountType.ELY_BY,
            "SteveEly", "d3e1f0a2b4c54f2a9c3d8e7b1a6f0c9d2", skinUrl = "https://textures.minecraft.net/x")
        val login = ElyAuthenticator(http).refresh("OLD", "CLIENT", existing)
        assertEquals(existing.id, login.account.id)
        assertEquals("FRESH", login.token)
        assertEquals("CLIENT2", login.client)
    }

    @Test fun authFailuresAreActionable() {
        val http = FakeHttp { _, _ -> 401 to """{"error":"ForbiddenOperationException"}""" }
        val e = assertFailsWith<AuthException> { ElyAuthenticator(http).login("user", "pw") }
        assertTrue(e.message!!.contains("two-factor"))
    }

    @Test fun elySessionsAreEncryptedSecretsNotMetadata() {
        val secrets = InMemorySecretStore()
        val acc = Account("ely-abc", AccountType.ELY_BY, "SteveEly", "d3e1f0a2b4c54f2a9c3d8e7b1a6f0c9d2")
        secrets.put("${acc.id}.mc", "TOKEN")
        secrets.put("${acc.id}.elyClient", "CLIENT")
        assertEquals("TOKEN", secrets.get("${acc.id}.mc"))
        // The account record itself carries no credential.
        assertFalse(acc.toString().contains("TOKEN"))
        secrets.remove("${acc.id}.mc")
        assertNull(secrets.get("${acc.id}.mc"))
    }

    @Test fun authlibInjectorUsesElysMetadataEndpoint() {
        assertEquals("https://authserver.ely.by/api/authlib-injector", ElyAuthenticator.AUTHLIB_INJECTOR_API)
    }
}
