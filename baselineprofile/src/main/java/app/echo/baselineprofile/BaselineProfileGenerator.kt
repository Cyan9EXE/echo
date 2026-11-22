package app.echo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/main/baseline-prof.txt from a real release-mode run.
 *
 * The routes below are the cold paths that matter for Echo: welcome -> chat
 * (restored conversation), drawer + search, settings, theme switch, help and
 * the About sheet. Keeping the walkthrough stable matters more than covering
 * every screen: an unstable UI test only produces a partial profile.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        // Welcome -> most recent conversation, scroll to exercise message rows.
        val resume = device.findObject(By.textContains("继续上次"))
        if (resume != null) {
            resume.click()
            device.waitForIdle()
            repeat(4) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    12
                )
                device.waitForIdle()
            }
        }

        // Chat -> settings -> theme color / day-night / About.
        val settings = device.findObject(By.desc("设置"))
        if (settings != null) {
            settings.click()
            device.waitForIdle()
            device.findObject(By.descContains("切换主题色"))?.click()
            device.waitForIdle()
            device.findObject(By.descContains("当前日间"))?.click()
            device.waitForIdle()
            device.findObject(By.descContains("当前夜间"))?.click()
            device.waitForIdle()
            device.findObject(By.desc("关于 Echo"))?.click()
            device.waitForIdle()
            device.findObject(By.desc("关闭关于"))?.click()
            device.waitForIdle()
            val close = device.findObject(By.desc("关闭"))
            if (close != null) {
                close.click()
                device.waitForIdle()
            }
        }

        // Welcome -> drawer -> search -> open a hit.
        val menu = device.findObject(By.desc("会话列表"))
        if (menu != null) {
            menu.click()
            device.waitForIdle()
            val search = device.findObject(By.clazz("android.widget.EditText"))
            if (search != null) {
                search.click()
                search.text = "CPU"
                device.waitForIdle()
                val hit = device.findObject(By.textContains("CPU"))
                if (hit != null) {
                    hit.click()
                    device.waitForIdle()
                }
            }
            val closeDrawer = device.findObject(By.desc("关闭"))
            if (closeDrawer != null) {
                closeDrawer.click()
                device.waitForIdle()
            }
        }

        // Help / FAQ sheet from the drawer is an infrequent but heavy dialog.
        device.pressHome()
        startActivityAndWait()
        val menu2 = device.findObject(By.desc("会话列表"))
        if (menu2 != null) {
            menu2.click()
            device.waitForIdle()
            device.findObject(By.descContains("打开答疑"))?.click()
            device.waitForIdle()
            device.findObject(By.desc("关闭答疑"))?.click()
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "app.xuxie"
    }
}
