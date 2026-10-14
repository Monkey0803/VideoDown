# 拾影 1.2.0（Keepsake）

这一版把「动效」和「已存整理」两件事做完整，并修掉了几个会打断使用的问题。

- 新：统一动效 token（`AppMotion`），卡片式弹窗骨架、骨架屏扫光、已存空状态插图都接到同一套时长与缓动上；图片管线迁到 Coil 3。
- 新：已存封面到播放器使用共享元素过渡，从列表点开时缩略图会连续放大到播放页。
- 新：已存列表可拖拽排序，顺序持久化到记录上（自定义排序 + 无搜索/过滤时才出现把手，长按菜单与无障碍操作也能重排）。
- 新：设置备份带走已存的自定义顺序；同一台设备导出再恢复能把手动排好的顺序还原（换机恢复按记录 id 匹配不上，顺序保持原样）。
- 修复：弹窗页脚在横屏 + 键盘弹起时被键盘挡住（竖屏也会被挤扁）。现在按可用高度决定整卡滚动，保存/取消始终可点。
- 修复：全部任务暂停后服务退出，独立通知卡随之消失，「从通知继续」失去入口。现在暂停的卡会留在通知栏（可划掉、只带「继续」），点它会把服务重新拉起来。
- 修复：网页解析失败后底部状态栏一片空白（失败原因存在 key 里、被当成空文案显示）。现在会显示真正的失败原因；扫完一个候选都没有时也会明说「本页没有发现可下载的视频」。
- 说明：视频号分享链接（`weixin.qq.com/sph/…`）不支持解析——播放地址在微信内动态签发，网页里没有可直接下载的地址；现在失败时会直接说明这一点，而不是让用户去试「打开网页播放」。若已拿到直链，粘贴直链仍可正常下载。
- 其余行为与 1.1.1 一致。

# Shiying 1.2.0 (Keepsake)

This release finishes the motion system and in-library organisation, and fixes a few things that interrupted normal use.

- New: one motion token set (`AppMotion`); the card dialog skeleton, list shimmer and the empty-state illustration share the same durations and easing, and the image pipeline moved to Coil 3.
- New: a shared-element transition from the saved cover into the player — tapping a thumbnail expands it continuously.
- New: drag to reorder the saved list, with the order persisted per record (the handle only appears for custom sort with no search/filter; the long-press menu and accessibility actions reorder too).
- New: settings backup now carries the saved custom order — exporting and restoring on the same device brings the hand-made order back (ids do not match across devices, so there the order stays as it is).
- Fixed: dialog footers were hidden behind the keyboard in landscape (and squeezed in portrait). The whole card now scrolls according to the available height, so save/cancel stay reachable.
- Fixed: when every task was paused the service exited and took the per-task notification with it, leaving no way to resume from the notification. Paused cards now stay on the notification bar (dismissible, resume only), and tapping resume starts the service again.
- Fixed: after a failed parse the bottom status line in the web page panel was blank (the reason is stored as a key and was shown as an empty message). It now shows the real reason, and a scan that found nothing says so explicitly.
- Note: WeChat Channels links (`weixin.qq.com/sph/…`) are not supported — the playable address is signed inside WeChat and the page has no downloadable address. Failures now say exactly that instead of suggesting you play the page; a direct link you already have still downloads normally.
- Everything else matches 1.1.1.
