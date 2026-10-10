# 拾影 1.1.1（Keepsake）

- 修复：部分真机上点「保险箱锁」不弹系统验证窗的问题（缺 `USE_BIOMETRIC` 声明，验证请求被系统拒绝后被静默吞掉）。
- 修复：Android 9 上进入保险箱可能崩溃（门禁改为要求 Android 10 及以上；更低版本明确提示设备不支持，不再假装可用）。
- 新增：更新说明支持中英双语（清单里的 `notesEn`），「检查更新」弹窗按界面语言显示对应的一版，缺一版自动回落。
- 修复：切换语言后，部分提示与文件名（导出链接清单、设置备份、分享/保存失败提示等 47 处）不会跟着变成新语言。
- 代码健康：`lintDebug` 的错误从 53 条收敛到 0 条。
- 其余行为与 1.1.0 一致。

# Shiying 1.1.1 (Keepsake)

- Fixed: on some devices tapping the vault lock showed no system prompt (the `USE_BIOMETRIC` declaration was missing, so the framework rejected the request and the failure was swallowed).
- Fixed: entering the vault could crash on Android 9 (the gate now requires Android 10 or newer; older releases say so instead of pretending to be usable).
- New: release notes can ship in both Chinese and English (`notesEn` in the manifest); the update dialog shows the one matching the interface language and falls back to the other.
- Fixed: after switching language, some prompts and suggested file names (export link list, settings backup, share/save failures — 47 places) stayed in the previous language.
- Code health: `lintDebug` errors went from 53 to 0.
- Everything else matches 1.1.0.
