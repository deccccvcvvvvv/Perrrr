package pro.cslauncher.core

import pro.cslauncher.core.meta.*
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.MetadataException
import kotlin.test.*

class MetadataTest {
    private val linux64 = PlatformInfo("linux", PlatformInfo.Arch.X86_64, "6.1", false)
    private val android = PlatformInfo.android("arm64-v8a", "14")

    val modern = """{"id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main","javaVersion":{"component":"java-runtime-gamma","majorVersion":17},
      "assetIndex":{"id":"5","sha1":"aa","size":10,"totalSize":100,"url":"https://x/5.json"},"assets":"5",
      "downloads":{"client":{"sha1":"cc","size":20,"url":"https://x/client.jar"}},
      "arguments":{"game":["--username","${'$'}{auth_player_name}",{"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":["--width","${'$'}{resolution_width}"]}],
                   "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},{"rules":[{"action":"allow","os":{"arch":"x86"}}],"value":"-Xss1M"},"-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"]},
      "libraries":[
        {"name":"com.mojang:brigadier:1.1.8","downloads":{"artifact":{"path":"com/mojang/brigadier/1.1.8/brigadier-1.1.8.jar","sha1":"b1","size":5,"url":"https://l/b.jar"}}},
        {"name":"org.lwjgl:lwjgl:3.3.1","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar","sha1":"l1","size":5,"url":"https://l/l.jar"}}},
        {"name":"org.lwjgl:lwjgl:3.3.1:natives-linux","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar","sha1":"n1","size":5,"url":"https://l/n.jar"}},"rules":[{"action":"allow","os":{"name":"linux"}}]},
        {"name":"org.lwjgl:lwjgl:3.3.1:natives-windows","downloads":{"artifact":{"path":"w.jar","sha1":"w","size":5,"url":"https://l/w.jar"}},"rules":[{"action":"allow","os":{"name":"windows"}}]},
        {"name":"ca.weblite:java-objc-bridge:1.1","downloads":{"artifact":{"path":"o.jar","sha1":"o","size":5,"url":"https://l/o.jar"}},"rules":[{"action":"allow","os":{"name":"osx"}}]}
      ],"logging":{"client":{"argument":"-Dlog4j.configurationFile=${'$'}{path}","file":{"id":"client-1.12.xml","sha1":"f","size":3,"url":"https://x/log.xml"},"type":"log4j2-xml"}}}"""

    val legacy = """{"id":"1.8.9","type":"release","mainClass":"net.minecraft.client.main.Main","minecraftArguments":"--username ${'$'}{auth_player_name} --version ${'$'}{version_name} --gameDir ${'$'}{game_directory} --assetsDir ${'$'}{assets_root} --assetIndex ${'$'}{assets_index_name} --uuid ${'$'}{auth_uuid} --accessToken ${'$'}{auth_access_token} --userProperties ${'$'}{user_properties} --userType ${'$'}{user_type}",
      "assetIndex":{"id":"1.8","url":"https://x/1.8.json"},"downloads":{"client":{"url":"https://x/c.jar","sha1":"c"}},
      "libraries":[{"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4","natives":{"linux":"natives-linux","windows":"natives-windows-${'$'}{arch}"},"extract":{"exclude":["META-INF/"]},
        "downloads":{"classifiers":{"natives-linux":{"path":"p/linux.jar","url":"https://l/linux.jar","sha1":"x"},"natives-windows-64":{"path":"p/w64.jar","url":"https://l/w64.jar","sha1":"y"}}}},
        {"name":"org.lwjgl.lwjgl:lwjgl:2.9.4","rules":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}],"downloads":{"artifact":{"path":"p/lwjgl.jar","url":"https://l/lwjgl.jar","sha1":"z"}}}]}"""

    @Test fun parsesModernMetadata() {
        val m = VersionMetadata.parse(modern)
        assertEquals("1.20.1", m.id); assertEquals(17, m.javaMajor); assertTrue(m.usesModernArguments); assertTrue(m.usesLwjgl3)
        assertEquals("5", m.assetIndex!!.id); assertEquals(5, m.libraries.size); assertEquals("client-1.12.xml", m.logging!!.fileId)
    }

    @Test fun parsesLegacyMetadata() {
        val m = VersionMetadata.parse(legacy)
        assertFalse(m.usesModernArguments); assertFalse(m.usesLwjgl3); assertNotNull(m.legacyArguments)
        assertEquals("natives-windows-\${arch}", m.libraries[0].natives["windows"])
    }

    @Test fun rejectsMalformedMetadata() {
        assertFailsWith<MetadataException> { VersionMetadata.parse("{\"type\":\"release\"}") }
        assertFailsWith<MetadataException> { VersionMetadata.parse("not json") }
        assertFailsWith<MetadataException> { VersionMetadata.parse("""{"id":"x","mainClass":"a","libraries":[{"name":"../evil:x:1"}]}""") }
    }

    @Test fun rulesEvaluateOsArchAndFeatures() {
        val osxOnly = listOf(Rule(true, "osx", null, null, emptyMap()))
        assertFalse(RuleEvaluator.allowed(osxOnly, linux64))
        val allowButNotOsx = listOf(Rule(true, null, null, null, emptyMap()), Rule(false, "osx", null, null, emptyMap()))
        assertTrue(RuleEvaluator.allowed(allowButNotOsx, linux64))
        assertFalse(RuleEvaluator.allowed(listOf(Rule(true, null, null, "x86", emptyMap())), linux64))
        val feat = listOf(Rule(true, null, null, null, mapOf("has_custom_resolution" to true)))
        assertFalse(RuleEvaluator.allowed(feat, linux64, LaunchFeatures()))
        assertTrue(RuleEvaluator.allowed(feat, linux64, LaunchFeatures(hasCustomResolution = true)))
        assertTrue(RuleEvaluator.allowed(emptyList(), linux64))
    }

    @Test fun mavenCoordinates() {
        assertEquals("net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar", MavenCoordinate.parse("net.fabricmc:fabric-loader:0.15.0").path())
        assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar", MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.1:natives-linux").path())
        assertEquals("a/b/c/1/c-1.zip", MavenCoordinate.parse("a.b:c:1@zip").path())
    }

    @Test fun mergesInheritedLoaderProfile() {
        val parent = VersionMetadata.parse(modern)
        val child = VersionMetadata.parse("""{"id":"fabric-loader-0.15.0-1.20.1","inheritsFrom":"1.20.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
           "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
           "libraries":[{"name":"net.fabricmc:fabric-loader:0.15.0","url":"https://maven.fabricmc.net/"},{"name":"com.mojang:brigadier:1.2.0","url":"https://maven.fabricmc.net/"}]}""")
        val m = VersionMetadata.merge(child, parent)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", m.mainClass)
        assertEquals(17, m.javaMajor)
        assertEquals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar", m.libraries[0].artifact!!.url)
        assertEquals(1, m.libraries.count { it.coordinate.artifact == "brigadier" }, "child library overrides parent version")
        assertEquals("1.2.0", m.libraries.first { it.coordinate.artifact == "brigadier" }.coordinate.version)
        assertTrue(m.jvmArguments.size == parent.jvmArguments.size + 1)
    }

    @Test fun assetIndexValidation() {
        val idx = AssetIndex.parse("""{"objects":{"minecraft/sounds/a.ogg":{"hash":"0123456789abcdef0123456789abcdef01234567","size":5}}}""")
        assertEquals("01/0123456789abcdef0123456789abcdef01234567", idx.objects[0].relativeObjectPath)
        assertFailsWith<MetadataException> { AssetIndex.parse("""{"objects":{"../x":{"hash":"0123456789abcdef0123456789abcdef01234567","size":5}}}""") }
        assertFailsWith<MetadataException> { AssetIndex.parse("""{"objects":{"x":{"hash":"zz","size":5}}}""") }
    }

    @Test fun manifestParsingAndFiltering() {
        val m = VersionManifest.parse("""{"latest":{"release":"1.21","snapshot":"24w01a"},"versions":[{"id":"24w01a","type":"snapshot","url":"u1","releaseTime":"2024"},{"id":"1.21","type":"release","url":"u2","sha1":"s","releaseTime":"2024"},{"id":"b1.7.3","type":"old_beta","url":"u3","releaseTime":"2011"}]}""", java.time.Instant.now(), false)
        assertEquals(listOf("1.21"), m.filter(setOf(VersionType.RELEASE)).map { it.id })
        assertEquals(2, m.filter(setOf(VersionType.RELEASE, VersionType.SNAPSHOT)).size)
        assertEquals(listOf("b1.7.3"), m.filter(VersionType.entries.toSet(), "b1").map { it.id })
    }

    @Test fun androidPlatformExcludesDesktopNatives() {
        val dirs = pro.cslauncher.core.install.LauncherDirs(tempDir())
        val http = pro.cslauncher.core.net.UrlConnectionTransport()
        val desk = pro.cslauncher.core.install.Installer(dirs, http, pro.cslauncher.core.download.DownloadManager(http), linux64)
            .resolveLibraries(VersionMetadata.parse(modern), listOf("1.20.1"), "1.20.1")
        assertTrue(desk.libraries.any { it.isNative }); assertTrue(desk.skippedByRules.any { it.contains("windows") }); assertTrue(desk.skippedByRules.any { it.contains("objc") })
        val andr = pro.cslauncher.core.install.Installer(dirs, http, pro.cslauncher.core.download.DownloadManager(http), android)
            .resolveLibraries(VersionMetadata.parse(modern), listOf("1.20.1"), "1.20.1")
        assertTrue(andr.libraries.none { it.isNative }); assertEquals(listOf("org.lwjgl:lwjgl:3.3.1:natives-linux"), andr.desktopNativesReplaced)
        val legacyRv = pro.cslauncher.core.install.Installer(dirs, http, pro.cslauncher.core.download.DownloadManager(http), linux64)
            .resolveLibraries(VersionMetadata.parse(legacy), listOf("1.8.9"), "1.8.9")
        assertTrue(legacyRv.libraries.any { it.isNative && it.file.toString().endsWith("p/linux.jar") })
    }
}
