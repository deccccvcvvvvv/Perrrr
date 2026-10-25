package pro.cslauncher.core

import kotlinx.coroutines.runBlocking
import pro.cslauncher.core.content.*
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.net.UrlConnectionTransport
import java.nio.file.Files
import kotlin.test.*

class ContentInstallTest {
    /** In-memory catalog: projectId -> newest-first files. */
    private class FakeCatalog(val files: Map<String, List<CatalogFile>>) : ContentCatalog {
        override val name = "Fake"; override val available = true
        val lookups = mutableListOf<String>()
        override fun search(query: String, kind: ProjectKind, gameVersion: String?, loader: String?, offset: Int) = emptyList<CatalogProject>()
        override fun files(projectId: String, gameVersion: String?, loader: String?): List<CatalogFile> {
            lookups += projectId
            return files[projectId].orEmpty().filter { (gameVersion == null || gameVersion in it.gameVersions) && (loader == null || loader in it.loaders) }
        }
    }

    private fun file(http: LocalHttp, project: String, name: String, deps: List<String> = emptyList(), mc: String = "1.20.1"): CatalogFile {
        val data = "$project:$name".toByteArray()
        val url = http.put("/$project/$name", data)
        return CatalogFile("modrinth", project, name.substringBeforeLast('.'), name, url, sha1(data), null, data.size.toLong(), listOf(mc), listOf("fabric"), deps)
    }

    private fun installer() = CatalogInstaller(DownloadManager(UrlConnectionTransport(), retryBaseDelayMs = 1))

    @Test fun installsTransitiveRequiredDependenciesOnceAndRecordsThem() = runBlocking {
        LocalHttp().use { http ->
            val api = file(http, "api", "fabric-api-0.92.0+1.20.1.jar")
            val lib = file(http, "lib", "cloth-config-11.1.jar", deps = listOf("api"))
            val main = file(http, "main", "sodium-extra-0.5.jar", deps = listOf("lib", "api", "missing"))
            val cat = FakeCatalog(mapOf("api" to listOf(api), "lib" to listOf(lib), "main" to listOf(main), "missing" to listOf(file(http, "missing", "x-1.jar", mc = "1.19"))))
            val game = tempDir()
            val r = installer().install(cat, "Sodium Extra", main, ProjectKind.MOD, game, "1.20.1", "fabric")
            assertEquals(listOf("sodium-extra-0.5.jar", "cloth-config-11.1.jar", "fabric-api-0.92.0+1.20.1.jar"), r.installed.map { it.fileName })
            assertEquals(listOf("missing"), r.unresolved)
            assertEquals(1, cat.lookups.count { it == "api" }, "a shared dependency is resolved once")
            for (n in r.installed) assertTrue(Files.isRegularFile(game.resolve("mods").resolve(n.fileName)))
            val idx = ContentIndex(game).installed(ProjectKind.MOD)
            assertEquals(setOf("modrinth:main", "modrinth:lib", "modrinth:api"), idx.keys)
            assertEquals("Sodium Extra", idx.getValue("modrinth:lib").dependencyOf)
            assertTrue(r.summary("Sodium Extra").contains("2 required dependencies"))
        }
    }

    @Test fun skipsDependenciesAlreadyPresentIncludingManualCopies() = runBlocking {
        LocalHttp().use { http ->
            val api = file(http, "api", "fabric-api-0.92.0+1.20.1.jar")
            val main = file(http, "main", "iris-1.7.jar", deps = listOf("api"))
            val game = tempDir()
            Files.createDirectories(game.resolve("mods"))
            Files.write(game.resolve("mods/fabric-api-0.90.0+1.20.1.jar"), byteArrayOf(1))   // added by hand
            val r = installer().install(FakeCatalog(mapOf("api" to listOf(api))), "Iris", main, ProjectKind.MOD, game, "1.20.1", "fabric")
            assertEquals(listOf("iris-1.7.jar"), r.installed.map { it.fileName })
            assertEquals(listOf("fabric-api-0.92.0+1.20.1.jar"), r.alreadyPresent)
            assertEquals(0, http.hits["/api/fabric-api-0.92.0+1.20.1.jar"]?.get() ?: 0)
        }
    }

    @Test fun newVersionReplacesOldFileOfTheSameProjectAndRemovedFilesAreForgotten() = runBlocking {
        LocalHttp().use { http ->
            val game = tempDir(); val cat = FakeCatalog(emptyMap())
            installer().install(cat, "Pack", file(http, "rp", "faithful-1.0.zip"), ProjectKind.RESOURCE_PACK, game, "1.20.1", null)
            val r = installer().install(cat, "Pack", file(http, "rp", "faithful-1.1.zip"), ProjectKind.RESOURCE_PACK, game, "1.20.1", null)
            assertEquals(listOf("faithful-1.0.zip"), r.replaced)
            assertFalse(Files.exists(game.resolve("resourcepacks/faithful-1.0.zip")))
            assertEquals("faithful-1.1.zip", ContentIndex(game).installed(ProjectKind.RESOURCE_PACK).getValue("modrinth:rp").fileName)
            Files.delete(game.resolve("resourcepacks/faithful-1.1.zip"))
            assertTrue(ContentIndex(game).installed(ProjectKind.RESOURCE_PACK).isEmpty())
        }
    }

    @Test fun failedDownloadDoesNotRecordAnything() = runBlocking {
        LocalHttp().use { http ->
            val f = file(http, "m", "broken-1.0.jar")
            http.corrupt += "/m/broken-1.0.jar"
            val game = tempDir()
            assertFails { installer().install(FakeCatalog(emptyMap()), "Broken", f, ProjectKind.MOD, game, "1.20.1", "fabric") }
            assertTrue(ContentIndex(game).load().isEmpty())
        }
    }

    @Test fun baseNameStripsVersions() {
        assertEquals("fabric-api", ContentIndex.baseName("fabric-api-0.92.0+1.20.1.jar"))
        assertEquals("sodium-fabric", ContentIndex.baseName("sodium-fabric-0.5.8+mc1.20.1.jar"))
        assertEquals("modmenu", ContentIndex.baseName("modmenu-7.2.2.jar.disabled"))
        assertEquals("lithium", ContentIndex.baseName("lithium_v1.2.jar"))
        assertEquals("plainname", ContentIndex.baseName("PlainName.jar"))
    }
}
