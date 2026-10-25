package pro.cslauncher.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import pro.cslauncher.core.download.*
import pro.cslauncher.core.install.*
import pro.cslauncher.core.meta.*
import pro.cslauncher.core.net.UrlConnectionTransport
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.Integrity
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class DownloadAndInstallTest {
    private val http = UrlConnectionTransport()
    private val linux = PlatformInfo("linux", PlatformInfo.Arch.X86_64, "6.1", false)

    @Test fun downloadSuccessAndSkipWhenValid() = runTest {
        LocalHttp().use { srv ->
            val data = ByteArray(100_000) { (it % 251).toByte() }
            val url = srv.put("/a.bin", data)
            val dm = DownloadManager(http, retryBaseDelayMs = 1)
            val dest = tempDir().resolve("x/a.bin")
            val r = dm.downloadOne(DownloadTask(url, dest, Integrity(sha1 = sha1(data), size = data.size.toLong())))
            assertEquals(TaskState.DONE, r.state); assertContentEquals(data, Files.readAllBytes(dest))
            val again = dm.downloadOne(DownloadTask(url, dest, Integrity(sha1 = sha1(data), size = data.size.toLong())))
            assertEquals(TaskState.SKIPPED, again.state); assertEquals(1, srv.hits["/a.bin"]!!.get())
        }
    }

    @Test fun retriesTransientFailures() = runTest {
        LocalHttp().use { srv ->
            val data = "hello".toByteArray(); val url = srv.put("/r", data)
            srv.failuresBeforeSuccess["/r"] = java.util.concurrent.atomic.AtomicInteger(2)
            val r = DownloadManager(http, maxAttempts = 3, retryBaseDelayMs = 1).downloadOne(DownloadTask(url, tempDir().resolve("r"), Integrity(sha1 = sha1(data))))
            assertEquals(TaskState.DONE, r.state); assertEquals(3, r.attempts)
        }
    }

    @Test fun checksumMismatchFailsAndLeavesNoFile() = runTest {
        LocalHttp().use { srv ->
            val data = "payload".toByteArray(); val url = srv.put("/c", data); srv.corrupt += "/c"
            val dest = tempDir().resolve("c")
            val r = DownloadManager(http, maxAttempts = 2, retryBaseDelayMs = 1).downloadOne(DownloadTask(url, dest, Integrity(sha1 = sha1(data))))
            assertEquals(TaskState.FAILED, r.state); assertTrue(r.error!!.contains("sha1"))
            assertFalse(Files.exists(dest)); assertFalse(Files.exists(dest.resolveSibling("c.part")))
        }
    }

    @Test fun notFoundIsPermanentAndEmptyFilesRejected() = runTest {
        LocalHttp().use { srv ->
            val r = DownloadManager(http, retryBaseDelayMs = 1).downloadOne(DownloadTask(srv.base + "/missing", tempDir().resolve("m")))
            assertEquals(TaskState.FAILED, r.state); assertEquals(1, r.attempts)
            val url = srv.put("/empty", ByteArray(0))
            val e = DownloadManager(http, maxAttempts = 1, retryBaseDelayMs = 1).downloadOne(DownloadTask(url, tempDir().resolve("e")))
            assertEquals(TaskState.FAILED, e.state); assertTrue(e.error!!.contains("empty"))
        }
    }

    @Test fun interruptedDownloadResumesWithRange() = runTest {
        LocalHttp().use { srv ->
            val data = ByteArray(300_000) { (it * 7).toByte() }; val url = srv.put("/big", data)
            srv.truncateFirstResponse = "/big"
            val dest = tempDir().resolve("big")
            val r = DownloadManager(http, retryBaseDelayMs = 1).downloadOne(DownloadTask(url, dest, Integrity(sha1 = sha1(data), size = data.size.toLong())))
            assertEquals(TaskState.DONE, r.state); assertContentEquals(data, Files.readAllBytes(dest))
            assertTrue(srv.rangeRequests.get() >= 1, "second attempt must resume with a Range request")
        }
    }

    @Test fun duplicateRequestsAreDeduplicated() = runTest {
        LocalHttp().use { srv ->
            val data = ByteArray(50_000) { 1 }; val url = srv.put("/d", data)
            val dest = tempDir().resolve("d")
            val t = DownloadTask(url, dest, Integrity(sha1 = sha1(data)))
            val res = DownloadManager(http).downloadAll("dup", listOf(t, t, t.copy(label = "again")))
            assertTrue(res.ok); assertEquals(1, res.results.size); assertEquals(1, srv.hits["/d"]!!.get())
        }
    }

    @Test fun insufficientStorageIsDetectedBeforeDownloading() = runTest {
        val t = DownloadTask("http://127.0.0.1:1/x", tempDir().resolve("huge"), Integrity(size = Long.MAX_VALUE / 4))
        assertFailsWith<InsufficientStorageException> { DownloadManager(http).downloadAll("huge", listOf(t)) }
    }

    @Test fun downloadStateReportsBatchProgress() = runTest {
        LocalHttp().use { srv ->
            val tasks = (1..12).map { i -> val d = ByteArray(1000 + i) { i.toByte() }; DownloadTask(srv.put("/f$i", d), tempDir().resolve("f$i"), Integrity(sha1 = sha1(d), size = d.size.toLong())) }
            var last: BatchProgress? = null
            val r = DownloadManager(http, maxConcurrency = 3).downloadAll("batch", tasks) { last = it }
            assertTrue(r.ok); assertEquals(12, last!!.finishedFiles); assertEquals(TaskState.DONE, last!!.state)
        }
    }

    // ---------------- complete installation pipeline against a local "Mojang" ----------------
    private class Fixture(val srv: LocalHttp, val dirs: LauncherDirs, val manifest: VersionManifest)

    private fun fixture(srv: LocalHttp): Fixture {
        val client = zipBytes(mapOf("net/minecraft/client/main/Main.class" to ByteArray(64) { 3 }))
        val lib = zipBytes(mapOf("com/example/Lib.class" to ByteArray(32) { 5 }))
        val native = zipBytes(mapOf("libexample.so" to ByteArray(128) { 9 }, "META-INF/MANIFEST.MF" to "x".toByteArray()))
        val assets = (1..3).associate { "minecraft/sounds/s$it.ogg" to ByteArray(200 + it) { b -> (b + it).toByte() } }
        val objects = assets.entries.joinToString(",") { (n, d) -> val h = sha1(d); srv.put("/res/${h.substring(0, 2)}/$h", d); "\"$n\":{\"hash\":\"$h\",\"size\":${d.size}}" }
        val index = "{\"objects\":{$objects}}".toByteArray()
        val log = "<Configuration/>".toByteArray()
        val v = """{"id":"9.9","type":"release","mainClass":"net.minecraft.client.main.Main","javaVersion":{"majorVersion":17},
          "assetIndex":{"id":"9","sha1":"${sha1(index)}","size":${index.size},"url":"${srv.put("/idx.json", index)}"},"assets":"9",
          "downloads":{"client":{"sha1":"${sha1(client)}","size":${client.size},"url":"${srv.put("/client.jar", client)}"}},
          "logging":{"client":{"argument":"-Dlog4j.configurationFile=${'$'}{path}","file":{"id":"log.xml","sha1":"${sha1(log)}","size":${log.size},"url":"${srv.put("/log.xml", log)}"}}},
          "arguments":{"game":["--username","${'$'}{auth_player_name}","--version","${'$'}{version_name}","--gameDir","${'$'}{game_directory}","--assetsDir","${'$'}{assets_root}","--assetIndex","${'$'}{assets_index_name}","--uuid","${'$'}{auth_uuid}","--accessToken","${'$'}{auth_access_token}","--userType","${'$'}{user_type}","--versionType","${'$'}{version_type}"],
                       "jvm":["-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"]},
          "libraries":[{"name":"com.example:lib:1.0","downloads":{"artifact":{"path":"com/example/lib/1.0/lib-1.0.jar","sha1":"${sha1(lib)}","size":${lib.size},"url":"${srv.put("/lib.jar", lib)}"}}},
                       {"name":"com.example:lib:1.0:natives-linux","downloads":{"artifact":{"path":"com/example/lib/1.0/lib-1.0-natives-linux.jar","sha1":"${sha1(native)}","size":${native.size},"url":"${srv.put("/native.jar", native)}"}},"rules":[{"action":"allow","os":{"name":"linux"}}]}]}""".toByteArray()
        srv.put("/v.json", v)
        // Asset URLs in metadata point to resources.download.minecraft.net; redirect them through a mirror rule in tests.
        val manifest = VersionManifest(null, null, listOf(ManifestEntry("9.9", VersionType.RELEASE, srv.base + "/v.json", sha1(v), "2024", null)), Instant.now(), false)
        return Fixture(srv, LauncherDirs(tempDir("install")), manifest)
    }


    @Test fun fullInstallVerifyAndTargetedRepair() = runTest {
        LocalHttp().use { srv ->
            val f = fixture(srv)
            val dm = DownloadManager(http, retryBaseDelayMs = 1)
            val inst = Installer(f.dirs, http, dm, linux, assetBaseUrl = srv.base + "/res")
            val stages = mutableListOf<InstallStage>()
            val report = withContext(Dispatchers.IO) { inst.install("9.9", f.manifest) { stages += it.stage } }
            assertTrue(report.complete, report.summary())
            assertTrue(stages.containsAll(listOf(InstallStage.RESOLVE, InstallStage.CLIENT, InstallStage.LIBRARIES, InstallStage.ASSETS, InstallStage.NATIVES, InstallStage.VERIFY)))
            assertTrue(Files.isRegularFile(f.dirs.nativesDir("9.9").resolve("libexample.so")), "natives extracted")
            assertFalse(Files.exists(f.dirs.nativesDir("9.9").resolve("META-INF")), "META-INF excluded")

            // Corrupt one library and delete one asset: verification must find exactly those two.
            val rv = inst.resolve("9.9", f.manifest)
            Files.write(rv.libraries.first { !it.isNative }.file, "broken".toByteArray())
            val idx = AssetIndex.parse(Files.readString(f.dirs.assetIndexFile("9")))
            Files.delete(f.dirs.assetObjects.resolve(idx.objects[1].relativeObjectPath))
            val broken = Verifier(f.dirs, inst, linux).verify(rv, fullHash = true)
            assertEquals(setOf(Component.LIBRARY, Component.ASSET), broken.issues.map { it.component }.toSet())
            assertEquals(2, broken.repairTasks.size)
            val clientHitsBefore = srv.hits["/client.jar"]!!.get()
            val rep = withContext(Dispatchers.IO) { dm.downloadAll("repair", broken.repairTasks) }
            assertTrue(rep.ok)
            assertEquals(clientHitsBefore, srv.hits["/client.jar"]!!.get(), "targeted repair must not re-download the client")
            assertTrue(Verifier(f.dirs, inst, linux).verify(rv, fullHash = true).complete)
        }
    }

    @Test fun installReportsStageOnFailure() = runTest {
        LocalHttp().use { srv ->
            val f = fixture(srv)
            srv.files.remove("/lib.jar")
            val inst = Installer(f.dirs, http, DownloadManager(http, maxAttempts = 1, retryBaseDelayMs = 1), linux)
            val e = assertFailsWith<InstallException> { withContext(Dispatchers.IO) { inst.install("9.9", f.manifest) } }
            assertEquals(InstallStage.LIBRARIES, e.stage)
            assertFalse(Files.exists(f.dirs.libraryFile("com/example/lib/1.0/lib-1.0.jar")))
        }
    }

    @Test fun corruptStoredMetadataIsReDownloadedWhenManifestHashDiffers() = runTest {
        LocalHttp().use { srv ->
            val f = fixture(srv)
            Files.createDirectories(f.dirs.versionDir("9.9")); Files.writeString(f.dirs.versionJson("9.9"), "{garbage")
            val inst = Installer(f.dirs, http, DownloadManager(http), linux)
            val rv = withContext(Dispatchers.IO) { inst.resolve("9.9", f.manifest) }
            assertEquals("9.9", rv.meta.id)
        }
    }
}
