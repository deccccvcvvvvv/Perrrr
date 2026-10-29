package pro.cslauncher.app.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.cslauncher.core.content.Nbt
import pro.cslauncher.core.content.Tag
import pro.cslauncher.core.content.string

class ServerListEditorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preservesUnknownNbtAndBacksUpWhileAddingAndRemoving() {
        val game = temp.newFolder("game").toPath()
        val backups = temp.newFolder("backups").toPath()
        val server = Tag.TCompound(linkedMapOf("name" to Tag.TString("Original"), "ip" to Tag.TString("example.org"), "extra" to Tag.TString("keep")))
        val root = Tag.TCompound(linkedMapOf("servers" to Tag.TList(10, listOf(server)), "customRoot" to Tag.TString("preserve")))
        Nbt.writeFile(game.resolve("servers.dat").toFile(), "root", root, gzip = false)
        val before = ServerListEditor.read(game)
        ServerListEditor.add(game, backups, before.fingerprint, "Second", "example.net:25566")
        val after = ServerListEditor.read(game)
        assertEquals(2, after.records.size)
        assertEquals("example.net:25566", after.records[1].address)
        val (name, updated) = Nbt.readFile(game.resolve("servers.dat").toFile())
        assertEquals("root", name)
        assertEquals("preserve", updated.string("customRoot"))
        val first = (updated.v["servers"] as Tag.TList).v.first() as Tag.TCompound
        assertEquals("keep", first.string("extra"))
        assertEquals(1, backups.toFile().listFiles()!!.size)
        ServerListEditor.update(game, backups, after.fingerprint, after.records[0].index, "Renamed", "example.org:25566")
        val edited = ServerListEditor.read(game)
        val (_, editedRoot) = Nbt.readFile(game.resolve("servers.dat").toFile())
        val editedFirst = (editedRoot.v["servers"] as Tag.TList).v.first() as Tag.TCompound
        assertEquals("keep", editedFirst.string("extra"))
        assertEquals("Renamed", editedFirst.string("name"))
        ServerListEditor.remove(game, backups, edited.fingerprint, edited.records[1].index)
        assertEquals(listOf("Renamed"), ServerListEditor.read(game).records.map { it.name })
        assertEquals(3, backups.toFile().listFiles()!!.size)
    }

    @Test fun rejectsStaleSnapshotAndInvalidAddressWithoutChangingData() {
        val game = temp.newFolder("g").toPath()
        val backups = temp.newFolder("b").toPath()
        val empty = ServerListEditor.read(game)
        ServerListEditor.add(game, backups, empty.fingerprint, "Good", "example.org")
        try {
            ServerListEditor.add(game, backups, empty.fingerprint, "Stale", "example.net")
            fail("Stale snapshot must fail")
        } catch (_: IllegalArgumentException) {}
        val current = ServerListEditor.read(game)
        try {
            ServerListEditor.add(game, backups, current.fingerprint, "Invalid", "not a host")
            fail("Invalid address must fail")
        } catch (_: IllegalStateException) {}
        assertEquals(listOf("Good"), ServerListEditor.read(game).records.map { it.name })
    }
}
