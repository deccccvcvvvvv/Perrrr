package pro.cslauncher.app.platform

import android.graphics.BitmapFactory
import pro.cslauncher.app.Services
import pro.cslauncher.core.account.Account
import pro.cslauncher.core.account.AccountType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Validates user-selected skin files before displaying or sending them anywhere. */
internal object SkinFiles {
    private const val LIMIT = 2 * 1024 * 1024
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    fun readPng(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var count = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            count += n
            require(count <= LIMIT) { "Skin image must be under 2 MB" }
            out.write(buf, 0, n)
        }
        val bytes = out.toByteArray()
        require(bytes.size >= PNG_MAGIC.size && bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)) { "Select a PNG image" }
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
        require(info.outMimeType == "image/png" && info.outWidth == 64 && info.outHeight in listOf(32, 64)) { "Skin must be 64×64 or 64×32 pixels" }
        require(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) { "Could not decode this PNG" }
        return bytes
    }
}

/** Upload only after the user confirms; tokens are never written to files or logs. */
internal object MicrosoftSkinUpload {
    fun upload(account: Account, png: ByteArray, slim: Boolean): String? {
        require(account.type == AccountType.MICROSOFT) { "A Microsoft account is required" }
        // AccountRepository checks token expiry. A user can re-login if the session is stale.
        val session = Services.accounts.session(account, "")
        val boundary = "CSLauncher" + UUID.randomUUID().toString().replace("-", "")
        val connection = URL("https://api.minecraftservices.com/minecraft/profile/skins").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val variant = if (slim) "slim" else "classic"
        val preamble = "--$boundary\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n$variant\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray(Charsets.UTF_8)
        val ending = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(preamble.size + png.size + ending.size)
        try {
            connection.outputStream.use { it.write(preamble); it.write(png); it.write(ending) }
            val status = connection.responseCode
            require(status in 200..299) { "Minecraft skin upload failed (HTTP $status); check your account and try again" }
            val response = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (out.size() <= 128 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (out.size() <= 128 * 1024) out.toString(Charsets.UTF_8.name()) else ""
            }
            val json = runCatching { JSONObject(response) }.getOrNull() ?: return null
            val skins = json.optJSONArray("skins")
            for (i in 0 until (skins?.length() ?: 0)) {
                val skin = skins?.optJSONObject(i) ?: continue
                if (skin.optString("state") == "ACTIVE") {
                    val url = skin.optString("url")
                    return when {
                        url.startsWith("https://textures.minecraft.net/") -> url
                        url.startsWith("http://textures.minecraft.net/") -> url.replaceFirst("http://", "https://")
                        else -> null
                    }
                }
            }
            return null
        } finally { connection.disconnect() }
    }
}
