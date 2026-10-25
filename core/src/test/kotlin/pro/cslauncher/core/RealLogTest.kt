package pro.cslauncher.core

import pro.cslauncher.core.diagnostics.CrashAnalyzer
import pro.cslauncher.core.diagnostics.FailureKind
import pro.cslauncher.core.launch.Log4jXml
import pro.cslauncher.core.launch.Milestone
import pro.cslauncher.core.launch.StartupMonitor
import kotlin.test.*

/** Regression tests built from the real output of Minecraft 1.18.2 launched by the host E2E (offline, Xvfb, llvmpipe). */
class RealLogTest {
    private val raw = javaClass.getResource("/fixtures/mc-1.18.2-offline-start.log")!!.readText()
    private fun flattened(): List<String> { val x = Log4jXml(); return raw.lines().flatMap { x.feed(it) } }

    @Test fun log4jXmlIsFlattenedToReadableLines() {
        val lines = flattened()
        assertTrue(lines.none { it.contains("<log4j:") || it.contains("]]>") }, lines.filter { it.contains("<log4j:") }.take(3).toString())
        assertTrue(lines.any { Regex("^\\[\\d{2}:\\d{2}:\\d{2}] \\[Render thread/INFO]: Setting user: CSHostTest$").matches(it) }, lines.take(10).toString())
        assertTrue(lines.any { it.startsWith("\tat com.mojang.authlib") }, "stack traces kept")
        assertTrue(lines.any { it.contains("[Render thread/ERROR]: Failed to verify authentication") })
        assertEquals("plain stdout line", Log4jXml().feed("plain stdout line").single())
    }

    @Test fun normalStartReachesReadyWithoutFalseCrash() {
        val m = StartupMonitor(); flattened().forEach(m::onLine)
        assertTrue(Milestone.READY in m.milestones)
        assertFalse(Milestone.CRASH_DETECTED in m.milestones, "Narrator/OpenAL errors are not crashes: ${m.crashLine}")
    }

    @Test fun benignStartupErrorsDoNotDriveDiagnosis() {
        assertTrue(CrashAnalyzer.analyze(raw, 0).isEmpty())
        val d = CrashAnalyzer.analyze(raw, 1)
        assertEquals(listOf(FailureKind.UNKNOWN), d.map { it.kind }, d.joinToString { "${it.kind}: ${it.evidence.firstOrNull()}" })
        // A real fatal error after those lines is still found.
        assertEquals(FailureKind.OUT_OF_MEMORY, CrashAnalyzer.analyze(raw + "\njava.lang.OutOfMemoryError: Java heap space", 1).first().kind)
    }

    @Test fun fatalMarkersStillDetected() {
        for (l in listOf("---- Minecraft Crash Report ----", "Exception in thread \"Render thread\" java.lang.IllegalStateException", "# A fatal error has been detected by the Java Runtime Environment:")) {
            val m = StartupMonitor(); m.onLine(l); assertTrue(Milestone.CRASH_DETECTED in m.milestones, l)
        }
        val m = StartupMonitor(); m.onLine("java.lang.UnsatisfiedLinkError: Unable to load library 'fliteWrapper'"); m.onLine("[LWJGL] GLFW_PLATFORM_ERROR error")
        assertFalse(Milestone.CRASH_DETECTED in m.milestones)
    }
}
