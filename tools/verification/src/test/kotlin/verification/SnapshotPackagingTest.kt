package verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotPackagingTest {
    private fun metadata(version: String = "2.34.56-SNAPSHOT", architectures: Set<String> = abis.keys + "universal"): Map<String, Any?> = mapOf(
        "artifactType" to mapOf("type" to "APK"),
        "applicationId" to "moe.tarsin.ehviewer",
        "elements" to architectures.map { abi ->
            mapOf("filters" to if (abi == "universal") emptyList() else listOf(mapOf("filterType" to "ABI", "value" to abi)), "outputFile" to "app-$abi-release.apk", "versionName" to version, "versionCode" to 123)
        },
    )

    @Test fun stagesExactlyFourConventionallyNamedSnapshotApks() {
        assertEquals((abis.keys + "universal").map { "EhViewer-2.34.56-$it.apk" }.toSet(), snapshotPlan(metadata()).map { it.second }.toSet())
    }

    @Test fun rejectsStableDatedMissingOrUnsafeInputs() {
        for (version in listOf("2.34.56", "2.34.56-nightly.20261017.1234", "../../bad-SNAPSHOT", "02.34.56-SNAPSHOT")) {
            assertThrows(IllegalArgumentException::class.java) { snapshotPlan(metadata(version)) }
        }
        assertThrows(IllegalArgumentException::class.java) { snapshotPlan(metadata(architectures = setOf("arm64-v8a"))) }
        assertThrows(IllegalArgumentException::class.java) { snapshotPlan(metadata() + ("applicationId" to "other.app")) }
    }
}
