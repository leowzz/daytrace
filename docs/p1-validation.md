# Opus / WebRTC VAD 验证（0.2.0）

日期：2026-10-23（Asia/Shanghai）。设备：USB Xiaomi 24031PN0DC，`fa7b189f`，Android API 36、arm64-v8a。

## 自动化结果

- Debug APK 构建成功，已覆盖安装，保留原始录音及暂停 session。
- JVM 测试 **13/13**：旧版加密/损坏恢复，VAD 静音过滤/门限/前置缓存/尾部静音/时间线/30 秒切分，Ogg 头/pre-skip/EOS/加密恢复。
- Android 仪器测试 **3/3**：固定语音经真实 WebRTC/libopus JNI、Ogg 和 AES-GCM；真实手机 Opus 导出；旧 PCM 兼容。
- Lint：0 errors，9 warnings（版本/target SDK、同步持久化与空间检查等）。
- `llvm-readelf` 核对 arm64 JNI ELF LOAD 对齐均为 `0x4000`（16 KiB）。

## 固定输入对比

输入为 5 秒静音 + 公开测试语音 + 5 秒静音，切为完整 20 ms 帧，共 **19.36 秒 / 968 帧**。这是确定性测试输入，不是环境录音。

| 项目 | 连续 Opus | WebRTC VAD + Opus |
|---|---:|---:|
| 输入音频 | 19.36 秒 | 19.36 秒 |
| 保留音频 | 19.36 秒 | 8.66 秒 |
| Ogg 大小 | 40,384 字节 | 18,152 字节 |
| 解码 PCM16 / 16 kHz | 619,520 字节 | 277,120 字节 |

该样本下 VAD 比连续 Opus 再减少约 **55%** 体积；连续 Opus 相比 PCM 小约 **15.3 倍**。比例随环境变化，不是通用保证。

两种输出均经 Android MediaExtractor/MediaPlayer prepare、电脑 **FFmpeg 完整解码**。采样数与保留帧数精确匹配，编码延迟/结尾裁剪未造成丢帧。ffprobe 容器 duration 包含 6.5 ms pre-skip，实际播放长度以解码采样数为准。

复核数据：`artifacts/device/opus/results.json`、`ffmpeg-results.json`（忽略版本控制）。

## 实际麦克风

升级后手机实际进行了两次录音和暂停：采集增量 **14.46 秒**，保留增量 **9.50 秒**，WebRTC 人声判定增量 **6.94 秒**。人声判定时长不含额外前后缓存，不能等同于文件长度。生成 3 个加密 Opus 片段，报告中断 0 次。最新片段通过 Android 实际导出/解码测试。旧 PCM 仍可解密导出 WAV。

系统能力为 `c2.android.opus.encoder`，实际采用 `libopus-1.6.1`。采音、VAD、编码、落盘均不使用网络。

## 尚未覆盖

- 本轮未做长时间锁屏，不能宣称 2/8/24 小时不中断或达到每日 5%–10% 额外耗电。
- 固定样本不覆盖远场轻声、音乐、电视、人群/车辆噪声；重要录音可关闭筛选。
- 通话争用、磁盘满、厂商省电清理、真机强杀恢复仍需专项验证；认证块截断恢复已有自动化测试。
