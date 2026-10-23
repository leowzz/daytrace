# ssh fn：Qwen3-ASR CPU 实测

## 结论

在 `fn` 的 Xeon E5-2680 v4 上，Qwen3-ASR-0.6B 的 **BF16 配置识别正确但慢于实时；实验性动态 INT8 配置快于实时，但把“人声”识别成“人生”**。本次没有找到同时通过这条准确性样本和实时速度要求的远端 CPU 配置。不能据此断言所有 CPU 推理后端或量化方法都不可行。

目前已验证的较好选择仍是本机 RTX 4060 Ti：同一原音约 0.209 秒，文本正确，见[首次对比报告](REPORT-2026-10-23.md)。这只是一个短样本的证据，不是整体准确性保证。

## 环境与方法

- 远端 `ssh fn`：VMware 虚拟机，Xeon E5-2680 v4 @ 2.40 GHz，12 vCPU，约 15.62 GiB RAM；有 AVX2，没有 AVX512/BF16 指令。测试前约 3.1 GiB 内存可用，无 swap。
- 独立 Docker 容器：8 CPU 配额、模型 8 线程、内存上限 2816 MiB（2.75 GiB）、禁止 swap；没有挂载正式音频库或数据库。原服务继续运行，未更换线上引擎。
- 基础镜像 `daytrace-server:cpu`，ID `sha256:ce34aa80d3d71d6311e450ae42baa87786e0f03ac6b6a1fc436623cbdabe1c64`。在独立虚拟环境中安装 Qwen 依赖，复用镜像的 CPU PyTorch。
- Python 3.12、PyTorch 2.13.0+cpu、qwen-asr 0.0.6、Transformers 4.57.6、PyAV 19.0.2、NumPy 2.2.6；模型 revision、文件哈希和完整计时见原始 JSON。远端权重 SHA-256 已核对与首次评测相同。
- 输入为 Git 中原始 4.14 秒 Opus 样本 `107b514e-3b27-4aed-b2ef-4d81beff6590`，解码到 16 kHz mono；没有裁剪、提示词、热词，也没有把人工参考交给模型。只指定中文。
- Transformers 整段推理，SDPA、batch=1、max_new_tokens=128；不含网络、端点检测、排队、文件解码或时间对齐。一次首次推理后连续计时五次；RTF = 推理耗时 / 4.14 秒。

## 结果

| 配置 | 输出 | 后续 5 次中位耗时 | 耗时范围 | RTF | 样本 CER |
| --- | --- | ---: | ---: | ---: | ---: |
| BF16 | 啊，现在是人声录制。 | 8.797 s | 6.749–15.471 s | 2.125 | 0% |
| 动态 INT8 / FBGEMM | 啊，现在是人生录制。 | 0.738 s | 0.722–0.886 s | 0.178 | 12.5% |

每种配置的六次输出文本各自一致。CER 忽略标点和空白，保留语气词及同音字；参考共八个字，INT8 的 12.5% 表示一个替换错误。INT8 的速度约为音频时长的 5.6 倍，但不满足用户确认的“人声”这一关键字要求。不能用词表或提示答案掩盖这个错误。

| 配置 | 初始化（含库导入及量化） | 加载后的首次推理 | 整个进程峰值 RSS |
| --- | ---: | ---: | ---: |
| BF16 | 24.666 s | 15.666 s | 2.21 GiB |
| 动态 INT8 | 126.938 s | 7.186 s | 2.53 GiB |

RSS 为 Linux `ru_maxrss`，包含初始化峰值，不是纯权重大小或稳态内存。BF16 后续耗时从 15.47 秒逐渐降至 6.75 秒，有明显波动；这是当前共享机器、内存限制及缓存状态下的实测，不能视为脱离这些条件的纯计算下限。INT8 首次推理有 308 次主缺页，后续每轮 0–5 次；首次较慢不能全部归因于模型计算。

## INT8 配置及限制

这是本仓库实验配置，不是 Qwen 官方发布的 INT8 checkpoint：加载原 BF16 权重，逐层转 FP32，再用 PyTorch `torch.ao.quantization.quantize_dynamic` 默认动态 qint8 配置、FBGEMM 后端转换 **308 个 Linear 层**，其余模块用 FP32。动态量化在线性算子内量化输入，算子接口和其他计算仍使用 FP32。每个子树转换后释放临时对象及 glibc 缓存，减少初始化峰值。

第一次未主动释放临时缓存的加载尝试被容器内存限制终止（OOM，exit 137），没有产出识别结果；加入临时内存回收后，使用相同 2.75 GiB 限制完成上述六次推理。量化与内存回收计入初始化，因此此次启动开销较高。模型常驻和预先转换权重是后续工程方向，尚未作为服务实现。该量化 API 已有弃用警告，重现需固定已测 PyTorch 版本。

FP32 **没有在 fn 上实测**：仅权重约 3.75 GB，超过测试时可用内存，另需运行时空间。不能把本机 Ryzen 的 0.994 秒 FP32 成绩当作 fn 成绩，也不能从 BF16 成绩推算 fn 的 FP32 耗时。

## 复现与记录

在具有上述 CPU PyTorch 与 Qwen 依赖的独立环境中，仓库根目录执行：

```sh
export HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 OMP_NUM_THREADS=8 MKL_NUM_THREADS=8
python evals/asr/benchmark.py --engine qwen3 --device cpu --dtype bfloat16 --threads 8 --model-dir artifacts/asr-benchmark/models/qwen3 --host-label fn-Xeon-E5-2680v4-12vCPU-Linux-container-8CPU --output artifacts/asr-benchmark/qwen3-remote-cpu-bf16.json
python evals/asr/benchmark.py --engine qwen3 --device cpu --quantization dynamic-int8 --threads 8 --model-dir artifacts/asr-benchmark/models/qwen3 --host-label fn-Xeon-E5-2680v4-12vCPU-Linux-container-8CPU --output artifacts/asr-benchmark/qwen3-remote-cpu-int8.json
```

原始记录：[BF16](results/2026-10-23/qwen3-remote-cpu-bf16.json)、[动态 INT8](results/2026-10-23/qwen3-remote-cpu-int8.json)。BF16 测量早于增加缺页计数的脚本版本，因此该文件没有每轮缺页与 CPU 时间字段。评测容器已退出并删除；模型和独立评测环境作为缓存留在远端 `/tmp/daytrace-asr-benchmark-20261023`。

本次没有修改线上识别结果，也没有实现流式 API 或词时间对齐。后续若继续探索 CPU 量化，需要用独立人工标注的留出集验证准确性，不能只围绕这一条录音调参。
