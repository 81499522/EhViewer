package verification

import com.hippo.ehviewer.jni.benchmarkArchives
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeBenchmarkInputsTest {
    @Test
    fun generatedCorporaAreDeterministicAndBothCodecsContainIdenticalPages() = temporary { directory ->
        val first = directory.resolve("first")
        val second = directory.resolve("second")
        for (output in listOf(first, second)) {
            benchmarkArchives(Options(listOf("--output", output.toString(), "--page-bytes", "65536", "--pages", "12", "--pattern", "mixed")))
        }
        for (name in listOf("large-stored.zip", "large-deflated.zip")) assertEquals(sha256(first.resolve(name)), sha256(second.resolve(name)))
        ZipFile(first.resolve("large-stored.zip").toFile()).use { stored ->
            ZipFile(first.resolve("large-deflated.zip").toFile()).use { deflated ->
                val names = stored.entries().asSequence().map { it.name }.toList()
                assertEquals(12, names.size)
                assertEquals(names, deflated.entries().asSequence().map { it.name }.toList())
                for (name in names) {
                    assertEquals(65536L, stored.getEntry(name).size)
                    assertArrayEquals(stored.getInputStream(stored.getEntry(name)).readBytes(), deflated.getInputStream(deflated.getEntry(name)).readBytes())
                }
            }
        }
    }

    @Test
    fun threePageProtocolPreservesTheOriginalNamesAndContent() = temporary { directory ->
        benchmarkArchives(Options(listOf("--output", directory.toString(), "--page-bytes", "4096")))
        ZipFile(directory.resolve("large-stored.zip").toFile()).use { zip ->
            assertEquals(listOf("page10.jpg", "page2.jpg", "page1.jpg"), zip.entries().asSequence().map { it.name }.toList())
            for (page in listOf(10, 2, 1)) assertArrayEquals(ByteArray(4096) { page.toByte() }, zip.getInputStream(zip.getEntry("page$page.jpg")).readBytes())
        }
    }
}
