# DayTrace deployment

这个目录是远端 `fn:~/work/daytrace/deploy` 使用的 Compose 配置。镜像由本机 Docker context `fn` 构建，数据和 ASR 模型分别持久化到宿主机 `/data/daytrace/data` 和 `/data/daytrace/models`。

首次启动时，faster-whisper 会在 `daytrace-models` volume 中下载 `small` 模型。12 vCPU / 16 GB 内存的纯 CPU 主机使用 `small + int8 + 8 CPU threads + 1 worker`，给系统和 SQLite 留出余量。

当前远端网络通过 `hf-mirror.com` 获取 Hugging Face 模型，配置已写入 `.env`。

```bash
cp .env.example .env
docker compose up -d
docker compose ps
curl http://127.0.0.1:8000/health
```
