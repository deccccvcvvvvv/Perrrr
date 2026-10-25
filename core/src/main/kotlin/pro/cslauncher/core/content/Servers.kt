package pro.cslauncher.core.content

import kotlinx.serialization.json.*
import pro.cslauncher.core.util.*
import java.io.*
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

data class ServerEntry(val name: String, val host: String, val port: Int = 25565, val favorite: Boolean = false, val acceptTextures: Int? = null, val icon: String? = null) {
    val address get() = if (port == 25565) host else "$host:$port"
}

object ServerAddress {
    private val HOST = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
    /** Parses "host", "host:port" or "[v6]:port". Returns null with no exceptions for invalid input. */
    fun parse(input: String): Pair<String, Int>? {
        val s = input.trim(); if (s.isEmpty() || s.length > 260) return null
        val (h, p) = when {
            s.startsWith("[") -> { val e = s.indexOf(']'); if (e < 0) return null; s.substring(1, e) to s.substring(e + 1).removePrefix(":").ifEmpty { "25565" } }
            s.count { it == ':' } == 1 -> s.substringBefore(':') to s.substringAfter(':')
            else -> s to "25565"
        }
        val port = p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = runCatching { IDN.toASCII(h) }.getOrNull() ?: return null
        if (!(HOST.matches(host) || IPV4.matches(host) || (s.startsWith("[") && host.contains(':')))) return null
        return host to port
    }
}

/** Reads/writes the game's own servers.dat (uncompressed NBT) so the in-game multiplayer list stays in sync. */
class ServerListStore(private val gameDir: Path) {
    private val file get() = gameDir.resolve("servers.dat").toFile()
    private val meta get() = gameDir.resolve("launcher-servers.json")

    fun load(): List<ServerEntry> {
        val favs = favorites()
        if (!file.isFile) return emptyList()
        val (_, root) = Nbt.readFile(file)
        return ((root.v["servers"] as? Tag.TList)?.v ?: emptyList()).mapNotNull { t ->
            val c = t as? Tag.TCompound ?: return@mapNotNull null
            val ip = c.string("ip") ?: return@mapNotNull null
            val (h, p) = ServerAddress.parse(ip) ?: (ip to 25565)
            ServerEntry(c.string("name") ?: ip, h, p, ip in favs, c.int("acceptTextures"), c.string("icon"))
        }
    }

    fun save(list: List<ServerEntry>) {
        val tags = list.map { e ->
            val m = LinkedHashMap<String, Tag>(); m["name"] = Tag.TString(e.name); m["ip"] = Tag.TString(e.address)
            e.icon?.let { m["icon"] = Tag.TString(it) }; e.acceptTextures?.let { m["acceptTextures"] = Tag.TByte(it.toByte()) }
            Tag.TCompound(m)
        }
        val root = Tag.TCompound(linkedMapOf("servers" to Tag.TList(10, tags)))
        Files.createDirectories(gameDir)
        if (file.isFile) Files.copy(file.toPath(), gameDir.resolve("servers.dat_old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Nbt.writeFile(file, "", root, gzip = false)
        FsUtil.writeAtomic(meta, CsJson.encodeToString(JsonArray.serializer(), JsonArray(list.filter { it.favorite }.map { JsonPrimitive(it.address) })))
    }
    private fun favorites(): Set<String> = runCatching { CsJson.parseToJsonElement(pro.cslauncher.core.util.FsUtil.readText(meta)).jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet())
}

data class ServerStatus(val reachable: Boolean, val latencyMs: Long?, val versionName: String?, val protocol: Int?, val online: Int?, val max: Int?, val motd: String?, val error: String?) {
    /** A status response proves network reachability only; joining may still require authentication/whitelisting. */
    val note get() = "Status ping only checks reachability. Joining can still fail on authentication, whitelist or version."
}

/** Minecraft "Server List Ping" (protocol ≥ 1.7) — the same handshake the in-game list uses. */
object ServerPinger {
    fun ping(host: String, port: Int, timeoutMs: Int = 5000): ServerStatus = try {
        Socket().use { s ->
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream())); val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val hs = ByteArrayOutputStream(); DataOutputStream(hs).apply { writeVarInt(0x00); writeVarInt(-1); writeString(host); writeShort(port); writeVarInt(1) }
            out.writePacket(hs.toByteArray()); out.writePacket(byteArrayOf(0x00)); out.flush()
            readVarInt(inp); val id = readVarInt(inp); require(id == 0) { "Unexpected packet $id" }
            val len = readVarInt(inp); require(len in 1..(1 shl 21)) { "Status too large" }
            val json = ByteArray(len).also { inp.readFully(it) }.decodeToString()
            val latency = (System.nanoTime() - t0) / 1_000_000
            val o = CsJson.parseToJsonElement(json).jsonObject
            val desc = when (val d = o["description"]) { is JsonPrimitive -> d.content; is JsonObject -> (d.str("text") ?: "") + (d.arr("extra")?.joinToString("") { (it as? JsonObject)?.str("text") ?: (it as? JsonPrimitive)?.content ?: "" } ?: ""); else -> null }
            ServerStatus(true, latency, o.obj("version")?.str("name"), o.obj("version")?.int("protocol"), o.obj("players")?.int("online"), o.obj("players")?.int("max"), desc?.replace(Regex("§."), ""), null)
        }
    } catch (e: Exception) { ServerStatus(false, null, null, null, null, null, null, e.message ?: e.javaClass.simpleName) }

    private fun DataOutputStream.writeVarInt(v: Int) { var x = v; while (true) { if (x and 0x7F.inv() == 0) { writeByte(x); return }; writeByte((x and 0x7F) or 0x80); x = x ushr 7 } }
    private fun DataOutputStream.writeString(s: String) { val b = s.toByteArray(); writeVarInt(b.size); write(b) }
    private fun DataOutputStream.writePacket(b: ByteArray) { writeVarInt(b.size); write(b) }
    private fun readVarInt(i: DataInputStream): Int { var r = 0; var n = 0; while (true) { val b = i.readByte().toInt(); r = r or ((b and 0x7F) shl (7 * n)); if (++n > 5) throw IOException("VarInt too big"); if (b and 0x80 == 0) return r } }
}
