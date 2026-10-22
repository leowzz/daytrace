# DayTrace Server

DayTrace 的本地/自托管后端原型。它接收 Android 上传的音频片段和元数据，保存原始音频，调用本地 ASR，并把可回溯的转写结果写入 SQLite。

## 当前能力

- `POST /v1/audio/segments`：multipart 上传音频和 JSON 元数据，幂等键为 `segment_id`。
- 原始音频保存到 `DATA_DIR/audio/`，数据库只保存文件引用、哈希和采集时间锚点。
- 默认使用本地 `faster-whisper`；开发时可设置 `DAYTRACE_ASR_BACKEND=mock` 做接口联调。
- 转写正文、ASR 子片段、相对音频偏移和模型信息写入 SQLite。
- `GET /v1/segments`、`GET /v1/segments/{segment_id}` 查询结果。
- ASR 失败会保留上传记录和音频，状态标记为 `failed`，不会伪造转写内容。

## 启动

需要 Python 3.11+。

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e .
$env:DAYTRACE_ASR_BACKEND = "mock" # 先联调 API；正式使用时改为 faster-whisper
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

正式本地 ASR：

```powershell
python -m pip install -e ".[asr]"
$env:DAYTRACE_ASR_BACKEND = "faster-whisper"
$env:DAYTRACE_ASR_MODEL = "small"
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

首次请求会下载并缓存模型。正式环境建议预热一次并固定模型版本；服务不把 ASR 不可用误报成成功。

## 上传示例

`metadata` 中的 `started_at_utc_ms` 是 Android 端真实 UTC 毫秒时间戳，`start_at_utc_ms` 也会被兼容接受。

```powershell
$metadata = '{"session_id":"run-1","segment_id":"segment-1","started_at_utc_ms":1761177600000,"duration_ms":3200,"codec":"opus","capture_mode":"vad","vad_enabled":true,"bitrate_kbps":16,"source_ref":"android://device/segment-1"}'
curl.exe -X POST http://localhost:8000/v1/audio/segments `
  -F "audio=@sample.opus" `
  -F "metadata=$metadata"
```

响应中的 `status` 为 `completed` 时，`transcript` 已经写入数据库；`failed` 时可以查看 `error`，修复 ASR 后重新上传同一个 `segment_id` 会更新该记录并再次处理。

## 数据库表

- `audio_segments`：采集元数据、原始文件引用、SHA-256、处理状态。
- `transcripts`：每个音频片段一条最终转写。
- `transcript_chunks`：ASR 返回的相对时间片段、置信度和可选词级 JSON。

默认数据库为 `server/data/daytrace.sqlite3`，可用 `DAYTRACE_DATABASE_URL=sqlite:///绝对路径` 修改。
