package pro.cslauncher.app.platform

import pro.cslauncher.core.content.Nbt
import pro.cslauncher.core.content.ServerAddress
import pro.cslauncher.core.content.Tag
import pro.cslauncher.core.content.string
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** App-side server-list editing without replacing the launcher core or discarding unknown NBT tags. */
internal object ServerListEditor {
    private const val MAX_FILE_SIZE = 4 * 1024 * 1024L
    data class Record(val index: Int, val name: String, val address: String)
    data class Snapshot(val records: List<Record>, val fingerprint: String)
    private data class Raw(val name: String, val root: Tag.TCompound, val list: Tag.TList, val fingerprint: String)

    private fun fingerprint(path: Path): String {
        if (!Files.exists(path)) return "absent"
        require(Files.isRegularFile(path) && Files.size(path) <= MAX_FILE_SIZE) { "Server list is too large or is not a regular file" }
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun readRaw(gameDir: Path): Raw {
        val path = gameDir.resolve("servers.dat")
        val before = fingerprint(path)
        if (before == "absent") return Raw("", Tag.TCompound(linkedMapOf()), Tag.TList(10, emptyList()), before)
        val (rootName, root) = Nbt.readFile(path.toFile())
        require(fingerprint(path) == before) { "Server list changed while reading; refresh first" }
        val tag = root.v["servers"]
        val list = when (tag) {
            null -> Tag.TList(10, emptyList())
            is Tag.TList -> tag
            else -> error("Unsupported server list format; no changes were made")
        }
        require(list.elementType == 10 || list.v.isEmpty()) { "Unsupported server entries; no changes were made" }
        return Raw(rootName, root, list, before)
    }

    fun read(gameDir: Path): Snapshot {
        val raw = readRaw(gameDir)
        val records = raw.list.v.mapIndexedNotNull { index, item ->
            val compound = item as? Tag.TCompound ?: return@mapIndexedNotNull null
            val ip = compound.string("ip") ?: return@mapIndexedNotNull null
            Record(index, compound.string("name") ?: ip, ip)
        }
        return Snapshot(records, raw.fingerprint)
    }

    @Synchronized
    fun add(gameDir: Path, backups: Path, expectedFingerprint: String, title: String, address: String) {
        val (name, canonical) = validate(title, address)
        val item = Tag.TCompound(linkedMapOf("name" to Tag.TString(name), "ip" to Tag.TString(canonical)))
        mutate(gameDir, backups, expectedFingerprint) { old -> old + item }
    }

    @Synchronized
    fun update(gameDir: Path, backups: Path, expectedFingerprint: String, index: Int, title: String, address: String) {
        val (name, canonical) = validate(title, address)
        mutate(gameDir, backups, expectedFingerprint) { old ->
            require(index in old.indices) { "Server entry no longer exists; refresh first" }
            val original = old[index] as? Tag.TCompound ?: error("Unsupported server entry")
            val fields = LinkedHashMap(original.v)
            fields["name"] = Tag.TString(name)
            fields["ip"] = Tag.TString(canonical)
            old.mapIndexed { i, item -> if (i == index) Tag.TCompound(fields) else item }
        }
    }

    private fun validate(title: String, address: String): Pair<String, String> {
        val name = title.trim()
        require(name.isNotEmpty() && name.length <= 128) { "Enter a server name up to 128 characters" }
        val parsed = ServerAddress.parse(address) ?: error("Enter a valid server address and port")
        val canonical = if (':' in parsed.first) "[${parsed.first}]${if (parsed.second == 25565) "" else ":${parsed.second}"}" else parsed.first + if (parsed.second == 25565) "" else ":${parsed.second}"
        return name to canonical
    }

    @Synchronized
    fun remove(gameDir: Path, backups: Path, expectedFingerprint: String, index: Int) {
        mutate(gameDir, backups, expectedFingerprint) { old ->
            require(index in old.indices) { "Server entry no longer exists; refresh first" }
            old.filterIndexed { i, _ -> i != index }
        }
    }

    private fun mutate(gameDir: Path, backups: Path, expectedFingerprint: String, change: (List<Tag>) -> List<Tag>) {
        val raw = readRaw(gameDir)
        require(raw.fingerprint == expectedFingerprint) { "Server list changed; refresh and try again" }
        val entries = change(raw.list.v)
        val updated = LinkedHashMap(raw.root.v)
        updated["servers"] = Tag.TList(10, entries)
        Files.createDirectories(gameDir)
        val path = gameDir.resolve("servers.dat")
        if (Files.exists(path)) {
            Files.createDirectories(backups)
            Files.copy(path, backups.resolve("servers-${UUID.randomUUID()}.dat"), StandardCopyOption.COPY_ATTRIBUTES)
        }
        require(fingerprint(path) == expectedFingerprint) { "Server list changed; refresh and try again" }
        Nbt.writeFile(path.toFile(), raw.name, Tag.TCompound(updated), gzip = false)
    }
}
