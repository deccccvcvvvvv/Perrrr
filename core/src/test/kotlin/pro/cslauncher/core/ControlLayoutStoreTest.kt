package pro.cslauncher.core

import pro.cslauncher.core.controls.*
import java.nio.file.Files
import kotlin.test.*

class ControlLayoutStoreTest {
    @Test fun defaultAlwaysAvailableAndOverridable() {
        val dir = tempDir("layouts")
        val store = ControlLayoutStore(dir)
        assertEquals(listOf(ControlLayoutStore.DEFAULT_ID), store.list().map { it.id })
        val custom = store.load("does-not-exist").copy(name = "My default")
        store.save(custom)
        assertEquals("My default", store.load(ControlLayoutStore.DEFAULT_ID).name)
        store.resetDefault()
        assertEquals("Default Touch", store.load(ControlLayoutStore.DEFAULT_ID).name)
        assertFailsWith<IllegalArgumentException> { store.delete(ControlLayoutStore.DEFAULT_ID) }
    }

    @Test fun importNeverOverwritesAndRejectsInvalidFiles() {
        val dir = tempDir("layouts")
        val store = ControlLayoutStore(dir)
        val shared = store.export(ControlLayoutStore.DEFAULT_ID)
        val a = store.import(shared); val b = store.import(shared)
        assertEquals("default-touch-2", a.id); assertEquals("default-touch-3", b.id)
        assertEquals(3, store.list().size)
        assertFailsWith<LayoutValidationException> { store.import("""{"formatVersion": 99, "id": "x", "name": "x", "modes": {}}""") }
        // A corrupted file is ignored instead of breaking the list (and load falls back to the default).
        Files.writeString(dir.resolve("broken.json"), "{not json")
        assertEquals(3, store.list().size)
        assertEquals(ControlLayoutStore.DEFAULT_ID, store.load("broken").id)
        store.delete(a.id)
        assertEquals(listOf(ControlLayoutStore.DEFAULT_ID, b.id), store.list().map { it.id })
    }
}
