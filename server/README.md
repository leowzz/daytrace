# DayTrace Server

DayTrace 的本地/自托管后端原型。它接收 Android 上传的音频片段和元数据，保存原始音频，调用本地 ASR，并把可回溯的转写结果写入 SQLite。

## 当前能力

- `GET /`（或 `/viewer`）：中文数据查看页，无需单独安装或构建前端。可筛选音频、查看采集事件、播放/下载原音、对照 ASR 全文及逐句/逐词时间，点击句子或词定位播放。
- `POST /v1/audio/segments`：multipart 上传音频和 JSON 元数据，幂等键为 `segment_id`。
- 原始音频保存到 `DATA_DIR/audio/`，数据库只保存文件引用、哈希和采集时间锚点。
- 默认使用本地 `faster-whisper`；开发时可设置 `DAYTRACE_ASR_BACKEND=mock` 做接口联调。
- 转写正文、ASR 子片段、相对音频偏移和模型信息写入 SQLite。
- `GET /v1/segments`、`GET /v1/segments/{segment_id}` 查询结果。
- `GET /v1/segments/{segment_id}/audio`：读取原始音频，支持 HTTP Range 拖动播放；`?download=true` 下载。音频缺失返回 404，不触发重转写。
- `POST /v1/capture-events`：每批 1–100 条采集事件，按稳定 `event_id` 幂等保存原始 JSON；`GET /v1/capture-events` 支持按 `session_id` 和分页查询。
- 完整客户端元数据保存在 `audio_segments.metadata_json`，单段查询/上传响应以 `metadata` 对象返回，包括采样格式、来源、时间精度与未知覆盖说明。
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

## 数据与 ASR 查看页

启动后打开 `http://localhost:8000/`。页面直接读取当前配置的 SQLite 和原音目录，已有数据无需重新上传。

- 音频列表按采集时间倒序分页，支持完整会话 ID、ASR 状态、时间范围和文本搜索；搜索覆盖片段 ID、会话 ID、已保存的转写正文。
- 时间筛选按浏览器本地时区输入并转换成 UTC 毫秒。音频按与 `[from_utc_ms, to_utc_ms)` 的相交范围查询，零时长记录按起点查询；采集事件按观察时间查询。列表同时间记录使用稳定 ID 排序。
- 原音和转写置于详情顶部，句/词偏移映射到各自片段的 UTC 锚点，播放时显示当前 UTC 时间。越界偏移保留在原始数据中，禁用定位，不生成虚假绝对时间；浏览器不支持的音频可下载后核对。
- 展示模型、语言、句/词置信信息与完整元数据。`faster-whisper` 句级置信字段是平均对数概率，不是百分比准确率。mock、空转写和失败单独提示；没有人工参考文本，不计算 WER/CER。
- 采集事件单独分页，保留暂停、系统静音、中断、本地删除、恢复等原始内容及来源。未知覆盖仍标为未知；页面没有新增说话人识别或 AI 摘要。

`GET /v1/segments` 新增可选参数 `status`、`q`、`from_utc_ms`、`to_utc_ms`；响应保留 `items/limit/offset`，新增匹配结果 `total/summary` 和每项的 `transcript_preview/asr_model/asr_provider/asr_language`。`summary.duration_ms` 是匹配片段完整采样时长之和，不是去重后的覆盖时长。`GET /v1/capture-events` 新增同样的时间参数和 `total`，支持会话过滤。结束时间必须大于开始时间。

页面和音频读取接口沿用当前服务的无认证访问方式，部署在现有可信网络内；需要对外访问时应在反向代理统一配置认证。静态资源已包含在 Python wheel 和 Docker 镜像中，不依赖 CDN。

验证：`python -m pip install -e ".[test]"` 后运行 `python -m pytest -q`。

## 上传示例

`metadata` 中的 `started_at_utc_ms` 是 Android 端真实 UTC 毫秒时间戳，`start_at_utc_ms` 也会被兼容接受。

```powershell
$metadata = '{"session_id":"run-1","segment_id":"segment-1","started_at_utc_ms":1761177600000,"duration_ms":3200,"codec":"opus","capture_mode":"vad","vad_enabled":true,"bitrate_kbps":16,"source_ref":"android://device/segment-1"}'
curl.exe -X POST http://localhost:8000/v1/audio/segments `
  -F "audio=@sample.opus" `
  -F "metadata=$metadata"
```

响应中的 `status` 为 `completed` 时，`transcript` 已经写入数据库；`failed` 时可以查看 `error`，修复 ASR 后重新上传同一个 `segment_id` 会再次处理。已完成的相同音频重传返回 HTTP 200 和现有结果，不重复执行 ASR；新上传成功返回 201。相同 ID 的音频、时间或已有元数据不一致返回 409，不覆盖旧证据。客户端提供 `sha256` 时会核对内容，校验失败返回 422。

Android 0.2.4 的手动上传需要本版本服务端。现有数据库启动时自动新增 `metadata_json` 字段和 `capture_events` 表，不删除已有录音和转写。API 暂无认证。Android 当前只显示上传结果，尚未展示转写文本。

采集事件请求结构为 `{"source_id":"安装实例标识","events":[{"event_id":"64位小写SHA-256","payload":{"event":"PAUSED","session_id":"...","observed_at_utc_ms":123,"data":{}}}]}`，成功响应按原序返回 `event_ids`。事件内容完整保留，冲突 ID 返回 409；事件中记录的删除仅描述本地删除，不删除服务端音频。缺少音频的区间仍是未知覆盖，不能据此推断静音。

## 数据库表

- `audio_segments`：采集元数据、原始文件引用、SHA-256、处理状态。
- `transcripts`：每个音频片段一条最终转写。
- `transcript_chunks`：ASR 返回的相对时间片段、置信度和可选词级 JSON。

默认数据库为 `server/data/daytrace.sqlite3`，可用 `DAYTRACE_DATABASE_URL=sqlite:///绝对路径` 修改。
