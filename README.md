# DayTrace · 本地语音记忆 Demo

原生 Kotlin + Jetpack Compose Android 应用。当前 **0.2.3：后台录音 + Opus + WebRTC VAD + 时间线分析包 + 可配置分段时长 + 服务连接设置**。

最终目标是让远端按时间回溯事件和上下文。时间映射和后续转写/摘要协议见 [事件回溯数据契约](docs/memory-data-contract.md)。

## 功能

- AudioRecord：16 kHz、单声道、PCM16、20 ms 帧；microphone 前台服务和录音期间持有的 Partial WakeLock。
- 独立采音与 VAD/编码/写盘线程；有限队列溢出时明确停止，不静默丢帧。
- 默认仅保存人声：真实 WebRTC GMM VAD，300 ms 前置缓存、600 ms 尾部静音、400 ms 最小累计人声、30000 ms 最大片段。四项均可在设置页以毫秒配置。
- 关闭人声筛选后连续保存；两种模式均用 libopus 1.6.1、VOIP/语音配置、复杂度 5、CBR。默认 16 kbps，可选 12/16/24/32 kbps。
- 标准 Ogg/Opus：OpusHead、OpusTags、48 kHz granule、pre-skip、EOS 裁剪、CRC。导出 .opus 可由 Android/FFmpeg 解码。
- Android Keystore AES-256-GCM 本地加密；暂停/继续/停止、通知操作、时间线、试听、导出和删除。旧 PCM 仍支持 WAV 导出。
- 显示采集/保留/人声判定时长、输入电平、VAD 状态；可导出诊断 CSV。
- 记录页可导出录音与时间索引的 ZIP 分析包；Opus 单文件也携带 UTC 时间和片段标识。目前尚无转写文本。
- 4 GiB 存储上限、128 MiB 剩余空间下限，不自动覆盖录音。
- 设置页支持保存 HTTP / HTTPS 服务地址并测试 `/health`，支持局域网 IP、端口和反向代理路径前缀；API 暂不认证。

16 kbps 连续保存约 8 MB/小时，VAD 模式随说话比例减少。尚未实现 Room、上传、ASR、AI 总结与长时间功耗报告。

## 服务端原型

`server/` 目录提供了一个 Python/FastAPI 后端：接收音频片段和现有的 `session_id`、`segment_id`、UTC 时间、时长、codec 等元数据，保留原始音频，使用本地 faster-whisper 做 ASR，并将转写正文、相对时间片段和采集元数据写入 SQLite。启动和上传示例见 [server/README.md](server/README.md)。Android 上传端尚未接入。

## 构建

安装包：`artifacts/DayTrace-0.2.3-debug.apk`。

需要 JDK 17、SDK 35、NDK 28.0.13004108、CMake 3.22.1。Gradle 8.9 wrapper 已包含，Kotlin 2.0.21 / AGP 8.7.3。原生源码固定在 `android/app/src/main/cpp/vendor`，构建不在线下载原生依赖。支持 arm64-v8a、x86_64。

```powershell
cd android
# local.properties 设置 sdk.dir 为你的 SDK 路径
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb -s fa7b189f install -r app/build/outputs/apk/debug/app-debug.apk
adb -s fa7b189f shell am start -n com.daytrace.app/.MainActivity
```

## 使用

开始录音并允许麦克风/通知。纯静音和不足门限的候选语音不生成文件，采集计时仍增长。人声结束、达到配置的最大片段时长或暂停/停止时完成有效分段。时间线保留真实开始时间，不把过滤的静音拼接成虚假的连续时间线。

设置页可关闭筛选、选择比特率和四档筛选强度；暂停/停止后修改，下次启动生效。筛选越严格可能越容易漏掉轻声；重要录音可用连续模式。试听需先暂停。Opus 录音导出 .opus，旧 PCM 导出 .wav。

设置 → 服务连接：填写例如 `http://192.168.177.100:8000`，点击「保存地址」后重启应用仍保留。点击「测试连通性」会检查当前输入地址（无需先保存）的 `/health`，仅当 HTTP 200 且 JSON 的 `status` 为 `ok` 时显示成功。连接和读取超时各为 5 秒；认证错误、找不到接口、重定向及异常响应会显示失败原因。手机需能访问该地址，局域网部署通常要求处于可互通的网络。当前不发送认证信息，也尚未接入录音上传。

设置 → 分段时长（毫秒）：修改后点「保存时长」，下次开始/继续录音生效，重启应用仍保留。「填入默认值」仅填回默认值，需再保存。四项均要求 20 ms 的整数倍：前置缓存 0–5000、静音结束间隔 20–5000、最短累计人声 20–2000、最大片段 20–300000；最大时长必须大于前三项之和。最大片段也用于连续模式。最短累计人声调大可能漏掉简短回应。实际使用的四项参数随每次 START 写入分析包的采集日志。

## 文件与恢复

私有 .dta 是加密容器，不能直接播放。DTA1 兼容旧 PCM；DTA2 存储完整 Ogg 页，认证信息绑定 session、segment、UTC 开始时间、VAD、码率、页号和累计音频长度。

每约 1 秒落盘完整认证页，每 5 页 fsync。未完成文件为 .part；下次启动恢复完整且认证成功的块。恢复 Ogg 导出时补最后一页 EOS/CRC。认证失败原件保留供诊断，不冒充有效录音。进程死亡可能丢失内存队列、候选语音和未落盘尾页。

试听在私有 cache 临时解密，结束/界面销毁/下次启动清理；主动导出的文件和分析包未加密。音频不上传、不进入备份/迁移；卸载删除文件及密钥。

系统 Opus 能力会被探测并记入诊断；当前统一使用固定 libopus，稳定控制帧长、延迟和容器。Native 启用 -O2，JNI 对齐 16 KiB。

## 后台边界

小米/HyperOS 建议将省电策略设为「无限制」并在最近任务锁定应用。服务为 START_NOT_STICKY，WakeLock 带超时续期。强停、重启、撤销权限或厂商杀进程可能中断；下次打开提示手动重启。不隐藏通知、不自动开机录音、不绕过权限。

通话/其他应用可能让系统返回静音，回调会记录和提示。持续 15 秒无采样、写盘阻塞或空间不足时显式停止。VAD 无法保证识别所有人声。

## 验证

0.2.3：构建成功，26 项 JVM 测试通过，Lint 0 errors / 9 warnings。新增 6 项测试覆盖地址校验、局域网及代理路径、无认证健康检查、异常/过大响应、HTTP 错误与重定向、连接拒绝。开发机访问 `http://192.168.177.100:8000/health` 返回 `{"status":"ok"}`；本次无连接的 Android 设备，尚未进行新界面的真机操作及持久化验收。

0.2.2：20 项 JVM 测试、6 项真机测试通过，Lint 0 errors / 9 warnings。覆盖自定义阈值、短回应/上下文保留、连续分段、候选缓存跨片段的时间连续性、无效值拒绝、设置持久化与录音中禁止修改。USB 24031PN0DC 已安装；实测设置页修改/保存成功并恢复原值，交付时保持暂停。界面截图：`artifacts/device/timing/settings.png`。未新增长时间锁屏验收。

[P1 测试报告](docs/p1-validation.md) · [P0 测试记录](docs/p0-validation.md) · [第三方依赖](docs/third-party.md)

```powershell
cd android
.\gradlew.bat assembleDebugAndroidTest
adb -s fa7b189f install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s fa7b189f shell am instrument -w -e class com.daytrace.app.OpusVadDeviceTest com.daytrace.app.test/androidx.test.runner.AndroidJUnitRunner
```

OpusVadDeviceTest 使用公开固定语音，不启动麦克风；已有 Opus 录音时也验证最新片段。旧 DeviceAudioTest 需要已有 PCM。**仪器测试会重启进程，运行前必须暂停/停止录音。** 测试输出在私有 files/codec-verification，不混入录音列表。

长时间验证：手动开始并锁屏，然后运行 `scripts/monitor-recording.ps1 -Serial fa7b189f -Minutes 120`。脚本只观察。目前未完成 2/8/24 小时验收。

```powershell
adb -s fa7b189f shell run-as com.daytrace.app cat files/status.json
adb -s fa7b189f shell run-as com.daytrace.app cat files/diagnostics.csv
```

debug APK 允许授权 ADB run-as；正式发布需非 debuggable 签名构建。

依据：[麦克风前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)、[音频争用](https://developer.android.com/media/platform/sharing-audio-input)、[Ogg/Opus RFC 7845](https://www.rfc-editor.org/rfc/rfc7845)。
