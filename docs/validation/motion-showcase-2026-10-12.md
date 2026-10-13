# 真机动效录屏（2026-10-12）

动效里唯一没法用断言验收的就是**手感**：测试能证明"装了绘制""形状确实变了""会停"，
证明不了"好不好看、快慢是否合适"。这份录屏就是给人工复核用的。

设备：SHARK PRS-A0 / API 30，**三项动画缩放均为 1.0**（真实节奏，不是关闭动画的那一档）。

录屏按顺序走了一遍（时长约 15 秒）：

1. **已存空状态插图**：圆形 → 圆角方形的入场 morph（约 400ms）。
2. **骨架屏扫光**：放入一条带封面的已存记录后，封面解码期间占位上的扫光。
3. **共享元素形变**：点已存封面进入播放器，封面从网格单元飞到视频框（250ms）。
4. **列表重排**：返回已存后拖动封面，条目让位与归位的动画。

录像文件：[motion-on-real-device.mp4](motion-showcase-2026-10-12/motion-on-real-device.mp4)

## 复现方法

```bash
# 真机动画缩放保持 1.0
adb shell screenrecord --time-limit 26 --bit-rate 2000000 /sdcard/motion.mp4 &
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.videodown.douyin.MotionShowcaseDeviceTest
adb pull /sdcard/motion.mp4
```

用例是 `MotionShowcaseDeviceTest`：它**不控测试时钟**，让动画按自然节奏走一遍（其它用例为了稳定都停了时钟或只截几帧），
所以它就是为录屏存在的；断言只保证流程走完（停在已存页）。

## 请重点看这几件事

- **共享元素形变的时长**是否合适（改点只有一处：`SharedCover.kt` 里的 `boundsTransform`）。
- 形变过程中**封面是否遮挡播放器标题与控件**（真机取帧显示不遮挡；这一版的实现只给源侧进共享层）。
- **扫光的速度与亮度**是否合适（`Shimmer.kt` 的 `SWEEP_MS` 与颜色 alpha）。
- **空状态 morph** 的幅度是否自然（`EmptyStateIllustration.kt` 的两个多边形参数）。
- **拖拽是否跟手**、让位动画是否够顺（`animateItem` 的曲线来自 `Motion.kt` 的 token）。

任何一条觉得不对，改动点都只有上面这一处，不需要动结构。
