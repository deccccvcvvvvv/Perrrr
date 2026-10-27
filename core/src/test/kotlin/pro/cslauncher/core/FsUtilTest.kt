package pro.cslauncher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files

class FsUtilTest {
    @Test fun usableSpaceReadsExistingPathWithoutNioFileStore() {
        val dir = Files.createTempDirectory("cs-fs-space-")
        try {
            assertTrue(FsUtil.usableSpace(dir) > 0L)
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test fun usableSpaceUsesNearestExistingParent() {
        val dir = Files.createTempDirectory("cs-fs-parent-")
        try {
            val missing = dir.resolve("not-created").resolve("instance")
            assertEquals(dir.toFile().usableSpace, FsUtil.usableSpace(missing))
        } finally {
            Files.deleteIfExists(dir)
        }
    }
}
