package com.hippo.ehviewer.updater

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.MainActivity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutUpdateTest {
    @Test fun dailyChannelWorksWithoutATokenPreference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        fun clickPreference(text: String) {
            device.waitForIdle()
            var control = requireNotNull(device.wait(Until.findObject(By.text(text)), 10_000))
            while (!control.isClickable) control = requireNotNull(control.parent)
            if (control.isCheckable) {
                control.click(100)
            } else {
                check(control.accessibilityNodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
        }
        fun awaitChannel(expected: Boolean): Boolean {
            val value = runBlocking {
                withTimeout(5_000) { Settings.useCIUpdateChannel.valueFlow().first { it == expected } }
            }
            check(device.wait(Until.hasObject(By.checkable(true).checked(expected).hasDescendant(By.text(context.getString(R.string.use_ci_update_channel)))), 5_000))
            return value
        }
        val signIn = Settings.needSignIn.value
        val interval = Settings.updateIntervalDays.value
        val channel = Settings.useCIUpdateChannel.value
        val location = with(Settings) { listOf(downloadScheme, downloadAuthority, downloadPath, downloadQuery, downloadFragment) }
        downloadLocation = File(context.cacheDir, "about-smoke-downloads").apply { mkdirs() }.toOkioPath()
        Settings.needSignIn.value = false
        Settings.updateIntervalDays.value = 0
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                try {
                    device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 15_000)
                    device.swipe(1, device.displayHeight / 2, device.displayWidth * 3 / 4, device.displayHeight / 2, 30)
                    if (!device.wait(Until.hasObject(By.text(context.getString(R.string.settings))), 3_000)) {
                        UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(context.getString(R.string.settings))
                    }
                    clickPreference(context.getString(R.string.settings))
                    clickPreference(context.getString(R.string.settings_about))
                    device.wait(Until.hasObject(By.text(context.getString(R.string.settings_about_declaration))), 10_000)
                    if (!device.hasObject(By.text(context.getString(R.string.settings_about_check_for_updates)))) {
                        device.wait(Until.hasObject(By.scrollable(true)), 10_000)
                        UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(context.getString(R.string.settings_about_check_for_updates))
                    }
                    assertNotNull(device.findObject(By.text(context.getString(R.string.settings_about_check_for_updates))))
                    assertFalse(device.hasObject(By.textContains("GitHub token")))
                    clickPreference(context.getString(R.string.use_ci_update_channel))
                    assertEquals(!channel, awaitChannel(!channel))
                    clickPreference(context.getString(R.string.use_ci_update_channel))
                    assertEquals(channel, awaitChannel(channel))
                    assertFalse(device.hasObject(By.textContains("GitHub token")))
                } finally {
                    device.waitForIdle()
                    device.takeScreenshot(File(context.cacheDir, "daily-about.png"))
                    device.dumpWindowHierarchy(File(context.cacheDir, "daily-about.xml"))
                }
            }
        } finally {
            Settings.needSignIn.value = signIn
            Settings.updateIntervalDays.value = interval
            Settings.useCIUpdateChannel.value = channel
            with(Settings) {
                downloadScheme = location[0]
                downloadAuthority = location[1]
                downloadPath = location[2]
                downloadQuery = location[3]
                downloadFragment = location[4]
            }
        }
    }
}
