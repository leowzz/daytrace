# 第三方依赖

## libopus 1.6.1

- 上游：https://opus-codec.org/downloads/ ，https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz
- 下载包 SHA-256：`6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1`，已核对上游公布值。
- 发行源码保留于 `android/app/src/main/cpp/vendor/opus-1.6.1`，未修改上游算法。
- 许可证见上游 `COPYING`，同时打包到 APK `assets/licenses/opus.txt`。
- 未启用 DRED/OSCE 神经网络扩展，使用常规 VOIP 编码、复杂度 5 和 CBR。

## WebRTC VAD

- WebRTC GMM VAD C 实现，经 https://github.com/wiseman/py-webrtcvad 的 `cbits/webrtc` 集成。
- 固定提交：`e283ca41df3a84b0e87fb1f5cb9b21580a286b09`。
- 源码在 `android/app/src/main/cpp/vendor/webrtc`；不依赖 Python 运行时，没有用能量阈值模拟 WebRTC VAD。
- WebRTC 采用 BSD 许可证，py-webrtcvad 包装和测试资料采用 MIT；完整声明在 `LICENSE-py-webrtcvad`，同时打包到 APK `assets/licenses/webrtc.txt`。
- Android 测试 `speech-16k.pcm` 来自该提交的公开 `leak-test.wav`，8 kHz PCM16 通过确定性的样本复制转换为 16 kHz。仅测试 APK 包含，不是用户录音。

JNI 资源管理、VAD 起止分段、Ogg 封装、加密存储和 UI 为本项目实现。源码随仓库交付，CMake 不使用未固定版本的在线下载。
