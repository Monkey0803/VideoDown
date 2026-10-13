package com.videodown.douyin.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动器快捷方式图标必须**自带填充背景**。
 *
 * 背景：启动器不会给快捷方式图标套主题色，也不给它背景。界面里那套线条图标只描白边
 * （`strokeColor="#FFFFFF"`、无 `fillColor`），画在浅色菜单卡片上等于完全不显示——
 * 真机上表现为"长按菜单里的图标是空的"，实测非白像素占比 0.0000。
 *
 * 设备用例 `LauncherShortcutsDeviceTest#shortcutIconsAreVisibleOnALightLauncherBackground`
 * 在真机上量像素；这条 JVM 守卫负责在毫秒级把回退挡住：谁把图标换回纯线条那套就该红。
 */
class ShortcutIconGuardTest {

    private fun moduleRoot(): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory.parentFile != null && !File(directory, "src/main/res/values/strings.xml").isFile) {
            directory = directory.parentFile
        }
        return directory
    }

    private val shortcutIcons = listOf("paste", "batch_import", "tasks")

    @Test fun `every shortcut icon carries its own filled container`() {
        shortcutIcons.forEach { name ->
            val file = File(moduleRoot(), "src/main/res/drawable/ic_shiying_shortcut_$name.xml")
            assertTrue("缺少 $name 的快捷方式图标：必须先跑 scripts/generate_shiying_icons.py", file.isFile)
            val xml = file.readText()
            assertTrue(
                "$name 的快捷方式图标没有填充背景，在浅色启动器上会看不见",
                xml.contains("android:fillColor="),
            )
        }
    }

    @Test fun `the launcher shortcuts reference the container-backed icons`() {
        val source = File(moduleRoot(), "src/main/java/com/videodown/douyin/LauncherShortcuts.kt").readText()
        shortcutIcons.forEach { name ->
            assertTrue(
                "快捷方式必须用带容器的那套图标（ic_shiying_shortcut_$name），而不是界面用的线条图标",
                source.contains("R.drawable.ic_shiying_shortcut_$name"),
            )
        }
        assertEquals(
            "不该再引用纯线条的界面图标",
            0,
            Regex("""R\.drawable\.ic_shiying_(paste|batch_import|tasks)\b""").findAll(source).count(),
        )
    }
}
