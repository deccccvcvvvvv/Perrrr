package pro.cslauncher.core

import pro.cslauncher.core.account.*
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.perf.*
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.*
import pro.cslauncher.core.runtime.*
import pro.cslauncher.core.settings.*
import java.nio.file.Files
import kotlin.test.*

class RuntimeProfileAccountTest {
    private val arm = PlatformInfo.android("arm64-v8a", "14")
    private fun rt(major: Int, arch: PlatformInfo.Arch = PlatformInfo.Arch.ARM64, android: Boolean = true) =
        JavaRuntime("jre$major", tempDir(), major, "$major.0", arch, null, RuntimeSource.IMPORTED, android)

    @Test fun runtimeSelectionPolicy() {
        val all = listOf(rt(8), rt(17), rt(21))
        assertEquals(17, RuntimeSelector.select(17, true, all, arm).runtime!!.major)
        assertEquals(21, RuntimeSelector.select(21, true, all, arm).runtime!!.major)
        assertEquals(8, RuntimeSelector.select(8, false, all, arm).runtime!!.major)
        assertEquals(17, RuntimeSelector.select(16, true, listOf(rt(17), rt(21)), arm).runtime!!.major)
        assertNull(RuntimeSelector.select(21, true, listOf(rt(17)), arm).runtime)
        assertNull(RuntimeSelector.select(8, false, listOf(rt(17)), arm).runtime, "LWJGL2 versions require Java 8")
        assertEquals(Compatibility.RISKY, RuntimeSelector.select(8, true, listOf(rt(17)), arm).compatibility)
        assertNull(RuntimeSelector.select(17, true, listOf(rt(17, PlatformInfo.Arch.X86_64)), arm).runtime, "wrong architecture")
        assertNull(RuntimeSelector.select(17, true, listOf(rt(17, android = false)), arm).runtime, "desktop JVM on Android")
        assertTrue(RuntimeSelector.select(17, true, emptyList(), arm).reason.contains("No Java runtime"))
        assertEquals(8, RuntimeProbe.parseMajor("1.8.0_392")); assertEquals(17, RuntimeProbe.parseMajor("17.0.9"))
    }

    @Test fun runtimeInstallFromZipIsVerified() {
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val zip = tempDir().resolve("jre.zip")
        Files.write(zip, zipBytes(mapOf("jre17/release" to "JAVA_VERSION=\"17.0.9\"\nOS_ARCH=\"aarch64\"\n".toByteArray(), "jre17/lib/server/libjvm.so" to ByteArray(8))))
        val mgr = RuntimeManager(dirs, arm)
        assertFailsWith<IllegalArgumentException> { mgr.install(zip, "jre17", "00".repeat(32), "test") }
        val r = mgr.install(zip, "jre17", pro.cslauncher.core.util.Hashing.sha256(zip), "test")
        assertEquals(17, r.major); assertNotNull(r.libjvm()); assertEquals(1, mgr.list().size)
        assertFailsWith<IllegalArgumentException> { mgr.remove("jre17", listOf("My profile")) }
        mgr.remove("jre17", emptyList()); assertTrue(mgr.list().isEmpty())
    }

    @Test fun profileStoreLifecycleAndMigration() {
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val store = ProfileStore(dirs.config.resolve("profiles.json"), dirs)
        val p = Profile(name = "Survival", versionId = "1.20.1", memoryMb = 2048)
        store.upsert(p)
        assertEquals(p.id, store.load().selectedId)
        val dup = store.duplicate(p.id, "Copy"); assertNotEquals(p.id, dup.id); assertEquals(2, store.load().profiles.size)
        // user data inside the isolated game dir survives profile deletion by default
        val gd = p.gameDir(dirs); Files.createDirectories(gd.resolve("saves/World")); Files.writeString(gd.resolve("saves/World/level.dat"), "x")
        val plan = store.planDeletion(p.id); assertTrue(plan.gameDirBytes > 0)
        store.delete(p.id, deleteGameDir = false)
        assertTrue(Files.exists(gd.resolve("saves/World/level.dat")))
        assertEquals(dup.id, store.load().selectedId)
        // export / import
        val imported = store.import(store.export(dup.id)); assertNotEquals(dup.id, imported.id); assertEquals("Copy", imported.name)
        assertFailsWith<IllegalArgumentException> { store.import("""{"format":"other"}""") }
        // invalid profile rejected
        assertFailsWith<IllegalArgumentException> { store.upsert(Profile(name = "", versionId = "1.20.1")) }
        // migration from schema 1
        val v1 = """{"profiles":[{"id":"a","name":"Old","versionId":"1.16.5","memory":"2G","renderer":"gl4es"}]}"""
        val migrated = kotlinx.serialization.json.Json.decodeFromJsonElement(ProfilesFile.serializer(), store.migrate(pro.cslauncher.core.util.parseJsonObject(v1, "t")))
        assertEquals(2048, migrated.profiles[0].memoryMb); assertEquals("gl4es", migrated.profiles[0].rendererId)
        assertTrue(store.listBackups().isNotEmpty())
    }

    @Test fun jvmArgValidation() {
        assertEquals(listOf("-Xmx2G", "-Dname=a b"), JvmArgs.tokenize("-Xmx2G \"-Dname=a b\""))
        val f = JvmArgs.validate(listOf("-Xmx2G", "-cp", "x.jar", "-XX:+UseG1GC", "--foo"))
        assertTrue(f.any { it.arg == "-cp" && it.severity == JvmArgs.Severity.ERROR })
        assertTrue(f.any { it.arg == "x.jar" && it.severity == JvmArgs.Severity.ERROR })
        assertTrue(f.any { it.arg == "-Xmx2G" && it.severity == JvmArgs.Severity.WARNING })
        assertTrue(f.none { it.arg == "-XX:+UseG1GC" })
    }

    @Test fun accountsOfflineAndSecretsNeverInMetadata() {
        val dir = tempDir(); val secrets = InMemorySecretStore()
        val repo = AccountRepository(dir.resolve("accounts.json"), secrets)
        val off = repo.addOffline("Steve_1")
        assertEquals(OfflineIdentity.uuidFor("Steve_1").toString(), off.uuid)
        assertFailsWith<IllegalArgumentException> { repo.addOffline("x") }
        val s = repo.session(off, ""); assertTrue(s.isOffline); assertEquals("legacy", s.userType)
        val ms = Account("msa-1", AccountType.MICROSOFT, "Alex", "00000000-0000-0000-0000-000000000001", accessTokenExpiresAtMs = System.currentTimeMillis() + 3_600_000)
        repo.upsert(ms); repo.storeMicrosoftTokens(ms.id, "MC-TOKEN-XYZ", "REFRESH-ABC")
        assertFalse(Files.readString(dir.resolve("accounts.json")).contains("MC-TOKEN-XYZ"))
        assertEquals("MC-TOKEN-XYZ", repo.session(ms, "cid").accessToken)
        assertFalse(repo.session(ms, "cid").toString().contains("MC-TOKEN"))
        repo.upsert(ms.copy(accessTokenExpiresAtMs = 0)); assertFailsWith<AuthException> { repo.session(repo.load().accounts.first { it.id == "msa-1" }, "cid") }
        repo.remove(ms.id); assertNull(secrets.get("msa-1.mc")); assertNull(secrets.get("msa-1.msaRefresh"))
        assertFailsWith<AuthException> { MicrosoftAuthenticator(pro.cslauncher.core.net.UrlConnectionTransport(), "") }
    }

    @Test fun redactorRemovesSecrets() {
        val r = pro.cslauncher.core.util.Redactor
        assertFalse(r.redact("--accessToken abc.def.ghi --version 1").contains("abc.def"))
        assertFalse(r.redact("\"access_token\":\"XYZ123\"").contains("XYZ123"))
        assertFalse(r.redact("Authorization: Bearer secretvalue").contains("secretvalue"))
        assertEquals(listOf("--accessToken", "<redacted>", "--uuid", "u"), r.redactArgs(listOf("--accessToken", "t", "--uuid", "u")))
    }

    @Test fun settingsPersistAndRecoverFromCorruption() {
        val f = tempDir().resolve("settings.json")
        SettingsStore(f).update { it.copy(theme = ThemeChoice.GLASSMORPHISM, reduceMotion = true) }
        val reloaded = SettingsStore(f).state.value
        assertEquals(ThemeChoice.GLASSMORPHISM, reloaded.theme); assertTrue(reloaded.reduceMotion)
        Files.writeString(f, "{broken")
        assertEquals(ThemeChoice.DARK_GRAPHITE, SettingsStore(f).state.value.theme)
    }

    @Test fun memoryAdvisorNeverUsesAllRam() {
        for (ram in listOf(2048, 3072, 4096, 6144, 8192, 12288, 16384)) {
            val d = DeviceFacts(ram, true, 34, 8, false)
            val rec = PerformanceAdvisor.recommendedHeapMb(d)
            assertTrue(rec <= PerformanceAdvisor.maxHeapMb(d) && rec <= ram / 2, "ram=$ram rec=$rec")
            assertTrue(rec >= 512)
        }
        assertTrue(PerformanceAdvisor.maxHeapMb(DeviceFacts(8192, false, 30, 8, false)) <= 1024)
    }
}
