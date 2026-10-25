package pro.cslauncher.core

import com.sun.net.httpserver.HttpServer
import pro.cslauncher.core.util.Hashing
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real HTTP server for download/installer tests, with failure injection and Range support. */
class LocalHttp : AutoCloseable {
    val files = ConcurrentHashMap<String, ByteArray>()
    val failuresBeforeSuccess = ConcurrentHashMap<String, AtomicInteger>()
    val corrupt = ConcurrentHashMap.newKeySet<String>()
    val hits = ConcurrentHashMap<String, AtomicInteger>()
    val rangeRequests = AtomicInteger()
    @Volatile var truncateFirstResponse: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val fail = failuresBeforeSuccess[path]
            val data = files[path]
            when {
                fail != null && fail.getAndDecrement() > 0 -> { ex.sendResponseHeaders(503, -1); ex.close() }
                data == null -> { ex.sendResponseHeaders(404, -1); ex.close() }
                else -> {
                    var body = if (path in corrupt) data.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() } else data
                    val range = ex.requestHeaders.getFirst("Range")
                    if (range != null && range.startsWith("bytes=")) {
                        rangeRequests.incrementAndGet()
                        val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                        body = body.copyOfRange(from, body.size)
                        ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                        ex.sendResponseHeaders(206, body.size.toLong())
                    } else ex.sendResponseHeaders(200, body.size.toLong())
                    if (truncateFirstResponse == path) { truncateFirstResponse = null; ex.responseBody.write(body, 0, body.size / 2); ex.responseBody.flush(); ex.close(); return@createContext }
                    ex.responseBody.use { it.write(body) }
                }
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }
    fun put(path: String, data: ByteArray): String { files[path] = data; return base + path }
    override fun close() = server.stop(0)
}

fun sha1(b: ByteArray) = Hashing.sha1(b)
fun tempDir(prefix: String = "cs"): Path = Files.createTempDirectory(prefix)

fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
    return bos.toByteArray()
}
