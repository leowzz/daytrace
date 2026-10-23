# ASR 评测样本

此目录保存纳入 Git 版本管理的原始音频与人工参考文本，用于比较后续 ASR 模型、语言设置和识别参数。当前只有一个样本，不能代表整体识别准确率。已提供单样本评测脚本，首次对比见 [2026-10-23 评测报告](REPORT-2026-10-23.md)。

## 样本 107b514e-3b27-4aed-b2ef-4d81beff6590

- [原始音频](samples/107b514e-3b27-4aed-b2ef-4d81beff6590/audio.opus)：4.14 秒，Opus，VAD 采集；从服务端按原始字节下载，未转码、裁剪或拼接。
- [人工参考文本](samples/107b514e-3b27-4aed-b2ef-4d81beff6590/reference.txt)：`啊, 现在是人声录制`，由用户提供并确认。
- [样本信息及服务端快照](samples/107b514e-3b27-4aed-b2ef-4d81beff6590/sample.json)：包含来源 URL、下载时间、SHA-256、字节数，以及原始 segment_id/session_id、UTC 锚点、采样时长、格式、采集模式、来源引用和既有 ASR 结果（含句/词偏移）。

收录时的服务端结果是 `现在是人生努力`，引擎为 `faster-whisper`，模型为 `small`，识别语言为 `zh`。该结果仅作历史基线，不是参考答案。完整模型版本和推理参数未包含在原接口响应中，不应据此声称可以精确复现当时的识别过程。

## 使用约定

1. 评测前核对 `audio.opus` 的 SHA-256 与 `sample.json` 中的 `audio.sha256` 一致；参考文本以 UTF-8 读取，仅去除文件末尾换行。
2. 后续识别结果与模型/参数另存，不覆盖原音、参考文本或 `source_segment` 历史快照。不要修改服务端原始转写来充当评测结果。
3. 比较中文内容时可使用字符错误率（CER），但必须明确标点、空白的归一化规则；不能丢弃“啊”等短语气词，否则会掩盖漏识别。
4. 人工参考只有整段文本，没有人工句/词时间对齐；`reference.alignment` 为 `null`。不能把旧 ASR 的偏移套用到人工修正后的文字。
5. 服务端已有句/词偏移属于 `source_segment.transcript` 中的旧识别文本；绝对时间按该片段 `started_at_utc_ms + offset_ms` 映射。片段之外覆盖未知，源接口未提供的采集信息保持缺失。

音频直接使用普通 Git 托管；`.gitattributes` 将 Opus 标记为二进制，避免文本转换。

## 重现评测

在仓库根目录运行以下命令；模型权重写入忽略的 `artifacts/`，不要加入 Git。建议使用独立虚拟环境。`download_models.py` 固定模型仓库 revision，校验大文件的上游 SHA-256，并记录每个下载文件的哈希。

```powershell
python -m pip install -r evals/asr/requirements-cpu.txt
python evals/asr/download_models.py sensevoice paraformer-streaming paraformer
python evals/asr/benchmark.py --engine sensevoice --model-dir artifacts/asr-benchmark/models/sensevoice --threads 4 --host-label my-cpu --output artifacts/asr-benchmark/my-sensevoice.json
```

Paraformer 使用 `--engine paraformer` 或 `--engine paraformer-streaming`，并对应更换 `--model-dir`。流式回放每次喂入 100 ms 原音，末尾补 1 秒零值用于 flush；这只是推理计算耗时评测，未按墙钟实时播放，也未测网络和端点检测延迟。

Qwen 使用单独依赖文件，PyTorch 按设备安装。此次 Windows 实测环境沿用已有的 `torch 2.5.1+cu121`，在独立虚拟环境中安装其余依赖：

```powershell
python -m pip install -r evals/asr/requirements-qwen.txt
python evals/asr/download_models.py qwen3
python evals/asr/benchmark.py --engine qwen3 --device cuda --model-dir artifacts/asr-benchmark/models/qwen3 --threads 4 --host-label my-gpu --output artifacts/asr-benchmark/my-qwen-gpu.json
python evals/asr/benchmark.py --engine qwen3 --device cpu --model-dir artifacts/asr-benchmark/models/qwen3 --threads 8 --host-label my-cpu --output artifacts/asr-benchmark/my-qwen-cpu.json
```

`--runs` 默认为 5，另计一次模型加载后的首次推理。结果记录模型 revision、运行库版本、样本哈希、原始文本、CER、冷/热耗时以及模型提供的原始时间信息。脚本不把人工参考、提示词或热词传给识别模型；仅指定已知语言。原始音频使用 PyAV 解码并重采样到 16 kHz mono，Opus pre-skip 由解码器处理，结果均为 66,240 个采样 / 4.14 秒。

评分规则：NFKC、大小写归一化、移除 Unicode 标点和空白，保留语气词与同音字差异。指标验证运行 `python -m pytest -q evals/asr/test_benchmark.py`。结果为字符错误率，不是模型自身的置信度。
