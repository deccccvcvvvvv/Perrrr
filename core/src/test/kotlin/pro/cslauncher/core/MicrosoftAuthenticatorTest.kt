package pro.cslauncher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pro.cslauncher.core.account.DeviceCode
import pro.cslauncher.core.account.MicrosoftAuthenticator
import pro.cslauncher.core.net.HttpResponse
import pro.cslauncher.core.net.HttpTransport
import java.io.ByteArrayInputStream

class MicrosoftAuthenticatorTest {
    @Test fun deviceFlowHonoursMicrosoftSlowDownInterval() {
        val replies = ArrayDeque(listOf(
            """{"error":"slow_down"}""",
            """{"error":"authorization_pending"}""",
        ))
        val http = object : HttpTransport {
            override fun open(url: String, headers: Map<String, String>, method: String, body: ByteArray?): HttpResponse {
                val text = replies.removeFirst()
                return HttpResponse(400, emptyMap(), ByteArrayInputStream(text.toByteArray())) {}
            }
        }
        val auth = MicrosoftAuthenticator(http, "public-client-id")
        val device = DeviceCode("ABCD-EFGH", "https://microsoft.com/devicelogin", "device-code", 900, 5)

        assertNull(auth.pollDeviceCode(device))
        assertEquals(10, device.intervalSec)
        assertNull(auth.pollDeviceCode(device))
        assertEquals(10, device.intervalSec)
    }
}
