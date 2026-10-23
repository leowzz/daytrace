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

## 2026-10-23：数据与 ASR 查看页

已更新到 [数据查看页](http://192.168.177.100:8000/)（`/viewer` 同样可用）。支持音频筛选、原音播放/下载、逐句和逐词定位、UTC 时间映射、模型信息及采集事件查看。

当前镜像 `daytrace-server:cpu` 同时标记为 `daytrace-server:viewer-20261023`，镜像 ID 为 `sha256:ce34aa80d3d71d6311e450ae42baa87786e0f03ac6b6a1fc436623cbdabe1c64`。复用上一版本依赖（FastAPI 0.136.3 / Starlette 1.6.0，支持音频 Range），更新应用代码、静态文件及包配置；构建文件位于本机 `artifacts/deploy/server-viewer.Dockerfile`。本次无数据库结构迁移。

更新前创建 SQLite online backup：`/data/daytrace/data/backups/pre-viewer-20261023T025536Z.sqlite3`。候选容器使用备份副本和只读原音挂载，通过页面、数据查询、事件分页与 Range 验证后切换正式实例。上线后健康检查正常，已有 18 段音频、29 条采集事件；逐表比对旧记录未改变，全部原音 SHA-256 和数据库完整性通过。测试数据仅存在隔离环境。

本机 8 项接口测试通过；浏览器验证了句/词定位、越界保护、分页、搜索空结果、重置、失败提示、事件入口及手机布局。真实 Opus 已在浏览器成功解码加载。这些验证针对查看功能，不代表 ASR 准确率验收。页面与 API 沿用现有可信网络和无认证配置。

回滚本次应用更新（保留新上传数据）：

```bash
docker tag daytrace-server:pre-viewer-20261023 daytrace-server:cpu
docker compose -f /home/leo/work/daytrace/deploy/docker-compose.yml up -d --no-deps --timeout 240 daytrace-server
```

## 2026-10-23：0.2.4 上传与采集事件更新

已部署至 `http://192.168.177.100:8000`，运行镜像 `daytrace-server:cpu` 同时标记为 `daytrace-server:0.2.4`，镜像 ID 为 `sha256:b7132fb6c10df0a8f891c287c8c91db1be40d0315343cda7ecb4d14099b815fa`。

此次核对 `pyproject.toml` 与旧镜像完全一致后，沿用旧镜像中的依赖，复制最新 `server/app` 构建；部署用 Dockerfile 保留在本机 `artifacts/deploy/server-0.2.4.Dockerfile`。现有 `.env`、音频/模型挂载保持不变。SQLite 已自动增加完整元数据字段与采集事件表，API 无认证。

更新前使用 SQLite online backup 创建 `/data/daytrace/data/backups/pre-0.2.4-20261023T020049Z.sqlite3`，旧镜像保留为 `daytrace-server:pre-0.2.4-20261023`。候选镜像在隔离容器内验证了备份迁移、元数据上传和事件幂等；测试数据未写入正式数据库。正式实例健康检查通过，音频 SHA-256 与数据库完整性检查通过，验证时已有 8 段音频、8 条转写、29 条真实采集事件。

需要回滚应用代码时，在远端执行以下命令；数据库新增字段/表兼容旧服务，不要回拷旧数据库覆盖更新后的新数据：

```bash
docker tag daytrace-server:pre-0.2.4-20261023 daytrace-server:cpu
docker compose -f /home/leo/work/daytrace/deploy/docker-compose.yml up -d --no-deps --timeout 240 daytrace-server
```
