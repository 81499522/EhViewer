package com.hippo.ehviewer.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableUpdateTest {
    @Test fun comparesMultiDigitPatchVersionsNumerically() {
        assertTrue(isNewerStableRelease("1.15.9", "1.15.10"))
        assertFalse(isNewerStableRelease("1.15.10", "1.15.9"))
        assertTrue(isNewerStableRelease("1.15.10", "1.15.11"))
    }

    @Test fun comparesMajorAndMinorVersionsBeforeThePatch() {
        assertTrue(isNewerStableRelease("1.15.10", "1.16.0"))
        assertTrue(isNewerStableRelease("1.99.999", "2.0.0"))
        assertFalse(isNewerStableRelease("2.0.0", "1.99.999"))
        assertFalse(isNewerStableRelease("1.16.0", "1.15.999"))
    }

    @Test fun doesNotReinstallAnIdenticalStableVersion() {
        assertFalse(isNewerStableRelease("1.15.10", "1.15.10"))
    }

    @Test fun snapshotsCanSwitchToTheSameOrNewerStableRelease() {
        assertTrue(isNewerStableRelease("1.15.10-SNAPSHOT", "1.15.10"))
        assertTrue(isNewerStableRelease("1.15.10-SNAPSHOT", "1.15.11"))
        assertFalse(isNewerStableRelease("1.15.10-SNAPSHOT", "1.15.9"))
    }

    @Test fun rejectsMalformedAmbiguousAndNonStableVersions() {
        for (value in listOf("", "1.15", "1.15.10.1", "01.15.10", "1.015.10", "1.15.-1", "1.15.10-RC1", "1.15.10-SNAPSHOT", "1.15.10\n", "999999999999999999999999.0.0")) {
            assertFalse(value, isNewerStableRelease("1.15.9", value))
            if (value != "1.15.10-SNAPSHOT") assertFalse(value, isNewerStableRelease(value, "1.15.10"))
        }
    }
}
