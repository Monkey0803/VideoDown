# 启动器快捷方式图标修复（2026-10-12）

## 一、现象

真机（SHARK PRS-A0 / API 30）上长按应用图标，弹出的三个快捷方式——**粘贴解析（解析剪贴板中的视频链接）**、
**批量导入（一次导入多个链接）**、**下载任务（查看下载进度）**——**都没有图标**。

## 二、定位：图标注册了，但画出来是空的

`adb shell dumpsys shortcut` 显示三条快捷方式都在，并且**已经带上了图标资源**：

```
ShortcutInfo {id=quick_parse, ... longLabel=解析剪贴板中的视频链接
    icon=null
    iconRes=2131230932[drawable/ic_shiying_paste], bitmapPath=null, iconUri=null}
Total bitmap size: 0 (0 B)
```

（`icon=null` 是正常现象：资源型图标在平台侧只保留 `iconRes`，不会回填 `icon`。）

真正的原因在图标本身——那套 `ic_shiying_*` 是**只描白边、没有填充**的线条图标：

```xml
<path android:strokeColor="#FFFFFF" android:strokeWidth="2" ... />
```

它们在应用内靠 `AppIcon(..., tint = …)` **运行时染色**才成立；而快捷方式图标是**启动器**渲染的，
启动器既不套主题色、也不给背景。这台机器是浅色主题，菜单卡片是浅底 → **白线画在白底上等于什么都不显示**。

把图标真的画到白底上量像素，非白像素占比 **0.0000** ——这就是量化后的"没有图标"。

## 三、修复

图标管线（`scripts/generate_shiying_icons.py`）新增"带容器"输出：**品牌橙 `#F2784B` 圆角方块** +
用 `<group android:scaleX/Y="0.6" android:pivotX/Y="12">` 把原来的白色字形缩到中央——不需要手算任何路径。

产出三个新 drawable（`ic_shiying_shortcut_paste` / `_batch_import` / `_tasks`），`LauncherShortcuts` 改用它们；
界面里那套线条图标**保持不变**（`AppIcon` 的染色照旧）。

![三个快捷方式图标在浅色背景上的实际渲染](launcher-shortcut-icons-2026-10-12/icons-on-light.png)

系统侧确认已换到新资源：

```
ShortcutInfo {id=batch_import, ... iconRes=2131230950[drawable/ic_shiying_shortcut_batch_import]}
```

## 四、验证

**设备用例**（新增 1 项，先红后绿）：

- `LauncherShortcutsDeviceTest#shortcutIconsAreVisibleOnALightLauncherBackground`：
  把每个快捷方式图标画到 96×96 白底上，量非白像素占比并断言 > 5%。
  **修复前**报 `粘贴解析 的图标在浅色背景上几乎不可见（非白像素占比 0.0000）`；**修复后**通过。
  真机与模拟器各 **5/5** 通过（`LauncherShortcutsDeviceTest` 原有 4 项 + 新增 1 项）。

**JVM 守卫**（新增 `ShortcutIconGuardTest` 2 项）：

- 每个 `ic_shiying_shortcut_*.xml` 必须含 `android:fillColor`（自带背景）；
- `LauncherShortcuts` 必须引用 `ic_shiying_shortcut_*`，且**不得**再引用纯线条的界面图标。

整套 JVM **411 项**通过，`assembleDebug` 通过。

### 一个值得记的测试细节

平台侧 `ShortcutInfo.getIcon()` 对**资源型图标恒为 null**（`dumpsys` 里 `icon=null` + `iconRes=…` 可证），
所以"图标能不能看清"不能对着 `ShortcutManagerCompat.getDynamicShortcuts()` 量。
为此把要发布的列表抽成了 `LauncherShortcuts.shortcuts(context)`，测试直接对着**我们自己要发布的那份定义**量像素。

## 五、未覆盖 / 边界

1. **没有启动器菜单的截图**：这台 MIUI 的长按菜单无法用 adb 稳定触发（首页没有该图标、最近任务的图标长按、
   抽屉手势都试过）。证据是与启动器同一渲染路径的图标位图 + 像素断言；你自己长按一次即可确认。
2. 容器参数（`#F2784B`、圆角 6、字形缩放到 0.6）是**保守的工程选择**，不是设计稿定稿；
   要更贴品牌的容器形状需要设计给一版。
3. 快捷方式是在应用启动时发布的（`VideoDownApp.onCreate`）。**升级安装后首次启动之前**，
   启动器里可能还显示旧图标（启动器自身有图标缓存）——重装或重启启动器后会刷新。
