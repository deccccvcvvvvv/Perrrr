package pro.cslauncher.core.net

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val url: String, val code: Int, message: String) : IOException("HTTP $code for $url: $message")

class HttpResponse(val code: Int, private val headers: Map<String, List<String>>, val body: InputStream, private val onClose: () -> Unit) : AutoCloseable {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    val contentLength: Long get() = header("Content-Length")?.toLongOrNull() ?: -1
    override fun close() { runCatching { body.close() }; onClose() }
}

/** Minimal HTTP abstraction so downloads can be tested against a local server and replaced on Android if needed. */
interface HttpTransport {
    fun open(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: ByteArray? = null): HttpResponse

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String = open(url, headers).use { r ->
        if (r.code !in 200..299) throw HttpStatusException(url, r.code, r.body.readBytes().decodeToString().take(300))
        r.body.readBytes().decodeToString()
    }
    fun postText(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        open(url, headers + ("Content-Type" to contentType), "POST", body.toByteArray()).use { r -> r.code to r.body.readBytes().decodeToString() }
}

class UrlConnectionTransport(
    private val userAgent: String = "CSLauncherPro/1.0 (+https://github.com/cslauncherpro)",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val allowInsecureHttp: Boolean = false,
) : HttpTransport {
    override fun open(url: String, headers: Map<String, String>, method: String, body: ByteArray?): HttpResponse {
        var current = URL(url)
        repeat(6) {
            if (current.protocol != "https" && !(allowInsecureHttp || current.host == "127.0.0.1" || current.host == "localhost"))
                throw IOException("Refusing insecure URL $current")
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
            c.requestMethod = method
            c.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without Location from $current")
                c.disconnect()
                val next = URL(current, loc)
                if (current.protocol == "https" && next.protocol != "https") throw IOException("Refusing HTTPS->HTTP downgrade to $next")
                current = next
                return@repeat
            }
            val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
            return HttpResponse(code, c.headerFields.filterKeys { it != null }, stream) { c.disconnect() }
        }
        throw IOException("Too many redirects for $url")
    }
}
