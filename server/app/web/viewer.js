"use strict";

const $ = (id) => document.getElementById(id);
const state = {view: "segments", offset: 0, limit: 20, total: 0, selected: null, listRequest: 0, detailRequest: 0, filters: {}};
const statusNames = {completed: "已完成", processing: "处理中", failed: "失败"};
const eventNames = {START: "开始采集", PAUSED: "暂停", MIC_SILENCED: "系统静音", MIC_UNSILENCED: "系统静音解除", ERROR: "采集中断 / 错误", DELETED_SEGMENT: "本地删除片段", RECOVERED_SEGMENT: "恢复片段"};
const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
$("timezone").textContent = `显示时区：${timeZone}。片段按时间范围相交筛选，结束边界不包含在内；事件按观察时间筛选。`;

function node(tag, className, text) {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (text !== undefined) result.textContent = text;
  return result;
}
function display(value) { return value === null || value === undefined || value === "" ? "未提供" : String(value); }
function localTime(ms) {
  if (!Number.isFinite(ms)) return "时间未知";
  const date = new Date(ms);
  return Number.isNaN(date.getTime()) ? "时间超出显示范围" : date.toLocaleString("zh-CN", {hour12: false}) + "." + String(date.getMilliseconds()).padStart(3, "0");
}
function utcTime(ms) {
  const date = new Date(ms);
  return Number.isFinite(ms) && !Number.isNaN(date.getTime()) ? date.toISOString() : "时间未知";
}
function seconds(ms) { return Number.isFinite(ms) ? (ms / 1000).toFixed(3) + " s" : "未提供"; }
function duration(ms) { return ms < 60000 ? (ms / 1000).toFixed(1) + " 秒" : (ms / 60000).toFixed(1) + " 分钟"; }
function badge(status, mock = false) { return node("span", `badge ${mock ? "mock" : status}`, mock ? "MOCK · 演示" : statusNames[status] || status); }
function metadata(entries) {
  const dl = node("dl", "metadata");
  for (const [key, value] of entries) dl.append(node("dt", "", key), node("dd", "", display(value)));
  return dl;
}
function disclosure(title, data) {
  const details = node("details");
  details.append(node("summary", "", title), node("pre", "", JSON.stringify(data, null, 2)));
  return details;
}
async function request(url) {
  const response = await fetch(url, {cache: "no-store"});
  if (!response.ok) throw new Error(`请求失败（HTTP ${response.status}），请检查服务后刷新。`);
  return response.json();
}
function feedback(message, isError = false) {
  $("feedback").className = message ? `notice ${isError ? "error" : ""}` : "";
  $("feedback").textContent = message;
}
function clearDetail(message = "选择一条记录，查看原音与证据。") {
  ++state.detailRequest;
  $("detail").querySelector("audio")?.pause();
  $("detail").replaceChildren(node("div", "empty", message));
  state.selected = null;
}
function markSelected(id) {
  state.selected = id;
  for (const button of $("list").querySelectorAll(".record")) button.setAttribute("aria-current", String(button.dataset.id === id));
}
function pager() {
  $("prev").disabled = state.offset === 0;
  $("next").disabled = state.offset + state.limit >= state.total;
  $("page").textContent = state.total ? `${Math.floor(state.offset / state.limit) + 1} / ${Math.ceil(state.total / state.limit)}` : "0 / 0";
}
function renderStats(summary) {
  $("stats").replaceChildren();
  for (const [value, label] of [[summary.total, "筛选内片段"], [duration(summary.duration_ms), "采样时长合计（非覆盖并集）"], [summary.completed, "ASR 完成（含 mock）"], [`${summary.failed} / ${summary.processing}`, "失败 / 处理中"]]) {
    const card = node("div", "stat");
    card.append(node("strong", "", value), node("span", "", label));
    $("stats").append(card);
  }
}
async function loadList() {
  const version = ++state.listRequest;
  clearDetail("正在加载数据…");
  feedback("");
  $("list").setAttribute("aria-busy", "true");
  $("list").replaceChildren(node("div", "empty", "正在加载…"));
  $("prev").disabled = $("next").disabled = true;
  $("result-count").textContent = "";
  $("page").textContent = "";
  $("stats").replaceChildren();
  const params = new URLSearchParams({...state.filters, limit: state.limit, offset: state.offset});
  if (state.view === "events") { params.delete("q"); params.delete("status"); }
  try {
    const data = await request(`/v1/${state.view === "events" ? "capture-events" : "segments"}?${params}`);
    if (version !== state.listRequest) return;
    state.total = data.total;
    if (state.offset && state.offset >= data.total) {
      state.offset = Math.max(0, Math.floor((data.total - 1) / state.limit) * state.limit);
      return loadList();
    }
    $("result-count").textContent = `${data.total} 条 · 最新在前`;
    if (state.view === "segments") renderStats(data.summary);
    $("list").replaceChildren();
    for (const item of data.items) $("list").append(state.view === "segments" ? segmentCard(item) : eventCard(item));
    pager();
    if (data.items.length) {
      if (state.view === "segments") selectSegment(data.items[0].segment_id);
      else selectEvent(data.items[0]);
    } else {
      $("list").append(node("div", "empty", "没有符合筛选条件的记录。"));
      clearDetail("暂无可显示的数据。可调整筛选条件或上传录音后刷新。");
    }
  } catch (error) {
    if (version !== state.listRequest) return;
    $("list").replaceChildren(node("div", "empty", "数据加载失败"));
    clearDetail("点击「刷新数据」重试。");
    feedback(error.message, true);
  } finally {
    if (version === state.listRequest) $("list").setAttribute("aria-busy", "false");
  }
}
function segmentCard(item) {
  const button = node("button", "record");
  button.type = "button";
  button.dataset.id = item.segment_id;
  const top = node("div", "record-top");
  top.append(node("span", "", localTime(item.started_at_utc_ms)), badge(item.status));
  button.append(top, node("div", "record-id", item.segment_id));
  if (item.asr_provider === "mock") button.append(badge("", true));
  button.append(node("p", "preview", item.transcript_preview || (item.status === "completed" ? "空转写 · 请听原音核对" : item.status === "failed" ? "ASR 失败 · 查看错误与原音" : "尚无转写结果")),
    node("div", "record-meta", `${duration(item.duration_ms)} · ${item.codec} · ${display(item.capture_mode)} · ${display(item.asr_model)}`));
  button.addEventListener("click", () => selectSegment(item.segment_id));
  return button;
}
function eventCard(item) {
  const button = node("button", "record");
  button.type = "button";
  button.dataset.id = item.event_id;
  button.append(node("div", "record-top", localTime(item.payload.observed_at_utc_ms)),
    node("p", "preview", eventNames[item.payload.event] || item.payload.event),
    node("div", "record-id", item.payload.session_id || "无会话 ID"), node("div", "record-meta", item.payload.event));
  button.addEventListener("click", () => selectEvent(item));
  return button;
}
async function selectSegment(id) {
  clearDetail("正在加载片段…");
  markSelected(id);
  const version = state.detailRequest;
  try {
    const segment = await request(`/v1/segments/${encodeURIComponent(id)}`);
    if (version !== state.detailRequest) return;
    renderSegment(segment);
  } catch (error) {
    if (version === state.detailRequest) $("detail").replaceChildren(node("div", "notice error", error.message));
  }
}
function renderSegment(segment) {
  const detail = $("detail");
  detail.replaceChildren();
  const heading = node("div", "detail-header");
  heading.append(node("h2", "", "原音与转写"), badge(segment.status));
  detail.append(heading, node("div", "detail-id", `segment_id · ${segment.segment_id}`));
  const meta = segment.metadata || {};
  const sourceDetails = node("details");
  sourceDetails.append(node("summary", "", "片段时间、采样格式与来源"), metadata([
    ["会话 ID", segment.session_id], ["本地起止", `${localTime(segment.started_at_utc_ms)} → ${localTime(segment.started_at_utc_ms + segment.duration_ms)}`],
    ["UTC 起止", `${utcTime(segment.started_at_utc_ms)} → ${utcTime(segment.started_at_utc_ms + segment.duration_ms)}`],
    ["采样时长 / 格式", `${seconds(segment.duration_ms)} · ${segment.codec} / ${display(meta.container)}`],
    ["采样率 / 声道", `${display(meta.input_sample_rate_hz)} Hz / ${display(meta.channels)}`],
    ["采集模式 / VAD", `${display(segment.capture_mode)} / ${segment.vad_enabled == null ? "未知" : segment.vad_enabled ? "开启" : "关闭"}`],
    ["来源引用", segment.source_ref], ["时间精度", meta.time_accuracy_status || "未提供校准信息"],
  ]));
  detail.append(node("p", "caption", `${localTime(segment.started_at_utc_ms)} · ${duration(segment.duration_ms)} · ${segment.codec} · ${display(segment.capture_mode)}`));
  const events = node("button", "secondary", "查看此会话采集事件 →");
  events.type = "button";
  events.addEventListener("click", () => {
    $("session").value = segment.session_id;
    $("from").value = $("to").value = "";
    readFilters();
    switchView("events");
  });
  detail.append(events);
  const audioBox = node("div", "audio-box");
  const audioTop = node("div", "audio-top");
  const download = node("a", "", "下载原始音频 ↗");
  const url = `/v1/segments/${encodeURIComponent(segment.segment_id)}/audio`;
  download.href = url + "?download=true";
  audioTop.append(node("strong", "", "原始音频"), download);
  const audio = node("audio");
  audio.controls = true;
  audio.preload = "metadata";
  audio.src = url;
  const position = node("div", "caption", "播放后显示当前原音偏移和 UTC 时间。");
  const audioError = node("div");
  audioError.setAttribute("role", "status");
  audio.addEventListener("error", () => {
    audioError.className = "notice error";
    audioError.textContent = "无法播放：原音可能不可用、网络异常或浏览器不支持该格式。可尝试下载原音核对。";
  });
  audioBox.append(audioTop, audio, position, audioError);
  detail.append(audioBox);
  audio.addEventListener("timeupdate", () => {
    const offset = Math.round(audio.currentTime * 1000);
    position.textContent = `原音偏移 ${seconds(offset)} · UTC ${utcTime(segment.started_at_utc_ms + offset)}`;
  });
  if (segment.error) detail.append(node("div", "notice error", segment.error));
  const transcript = segment.transcript;
  if (!transcript) {
    detail.append(node("div", "notice", segment.status === "failed" ? "ASR 失败，尚无可用转写；失败不代表没有声音。" : "尚无转写结果；不能据此判断没有事件。"), sourceDetails, disclosure("完整片段数据与元数据", segment));
    return;
  }
  if (transcript.provider === "mock") detail.append(node("div", "notice", "这是 mock 演示结果，没有实际识别音频，不能用于评价 ASR 效果。"));
  else if (segment.status !== "completed") detail.append(node("div", "notice", "下方为已保存的历史转写；当前处理尚未成功。"));
  const confidenceLabel = transcript.provider === "faster-whisper" ? "平均 log probability" : "模型置信信息";
  detail.append(node("p", "caption", `${transcript.provider} / ${transcript.model} · 语言 ${display(transcript.language)}`));
  const modelDetails = node("details");
  modelDetails.append(node("summary", "", "模型与置信信息"), metadata([["ASR 引擎 / 模型", `${transcript.provider} / ${transcript.model}`], ["识别语言", transcript.language],
    ["解码音频时长", seconds(transcript.duration_ms)], [confidenceLabel, transcript.confidence], ["转写生成时间", transcript.created_at_utc]]));
  modelDetails.append(node("p", "caption", "模型置信信息不是准确率；faster-whisper 句级值为平均对数概率，词级值为模型概率。没有人工参考文本，暂不计算 WER / CER。"));
  detail.append(node("h3", "", "转写全文"), node("p", "transcript", transcript.text || "（空转写，不代表静音，请核对原音。）"));
  detail.append(node("h3", "", "逐句对照"), node("p", "caption", "点击句子或词从对应偏移播放。UTC = 此片段起始锚点 + 原音偏移；时间对齐可能有误差。"));
  const decodedDuration = Number.isFinite(transcript.duration_ms) ? transcript.duration_ms : segment.duration_ms;
  function validOffset(part) {
    return Number.isFinite(part.start_ms) && Number.isFinite(part.end_ms) && part.start_ms >= 0 && part.end_ms >= part.start_ms && part.end_ms <= decodedDuration;
  }
  async function seek(part) {
    if (!validOffset(part)) return;
    try {
      if (!audio.readyState) {
        audioError.className = "notice";
        audioError.textContent = "原音尚未加载完成，请稍后再次点击。";
        audio.load();
        return;
      }
      if (Number.isFinite(audio.duration) && part.end_ms > Math.round(audio.duration * 1000)) throw new Error("该偏移超出浏览器解码时长，未定位播放。请核对原始 ASR 数据。");
      audio.currentTime = part.start_ms / 1000;
      await audio.play();
      audioError.textContent = "";
      audioError.className = "";
    } catch (error) {
      audioError.className = "notice error";
      audioError.textContent = error.message;
    }
  }
  const chunkRows = [];
  for (const chunk of transcript.chunks || []) {
    const row = node("div", "chunk");
    const valid = validOffset(chunk);
    const button = node("button", "chunk-seek");
    button.type = "button";
    button.disabled = !valid;
    button.append(node("span", "chunk-time", `▶ ${seconds(chunk.start_ms)} – ${seconds(chunk.end_ms)}`), node("span", "chunk-text", chunk.text));
    button.addEventListener("click", () => seek(chunk));
    row.append(button, node("div", "caption", valid ? `${utcTime(segment.started_at_utc_ms + chunk.start_ms)} → ${utcTime(segment.started_at_utc_ms + chunk.end_ms)}` : "偏移无效或超出解码时长，未生成绝对时间，已禁用定位。"), node("div", "caption", `句子 #${chunk.chunk_index} · ${confidenceLabel}：${display(chunk.confidence)}`));
    if (chunk.words?.length) {
      const wordsDetail = node("details");
      const words = node("div", "words");
      wordsDetail.append(node("summary", "", `逐词对齐 · ${chunk.words.length} 词`), words);
      for (const word of chunk.words) {
        const wordButton = node("button", "word", word.text);
        wordButton.type = "button";
        wordButton.disabled = !validOffset(word);
        wordButton.title = `${seconds(word.start_ms)} – ${seconds(word.end_ms)} · 模型概率 ${display(word.confidence)}${validOffset(word) ? ` · ${utcTime(segment.started_at_utc_ms + word.start_ms)} → ${utcTime(segment.started_at_utc_ms + word.end_ms)}` : " · 偏移无效"}`;
        wordButton.addEventListener("click", () => seek(word));
        words.append(wordButton);
      }
      row.append(wordsDetail);
    }
    chunkRows.push({row, chunk, valid});
    detail.append(row);
  }
  if (!chunkRows.length) detail.append(node("p", "muted", "此结果未提供分句时间偏移。"));
  audio.addEventListener("timeupdate", () => {
    const offset = Math.round(audio.currentTime * 1000);
    for (const {row, chunk, valid} of chunkRows) row.classList.toggle("active", valid && offset >= chunk.start_ms && offset < chunk.end_ms);
  });
  detail.append(sourceDetails, modelDetails, disclosure("完整片段、ASR 偏移与元数据", segment));
}
function selectEvent(item) {
  clearDetail();
  markSelected(item.event_id);
  const payload = item.payload;
  const detail = $("detail");
  detail.replaceChildren(node("h2", "", eventNames[payload.event] || payload.event), node("div", "detail-id", `event_id · ${item.event_id}`));
  detail.append(metadata([["事件类型", payload.event], ["会话 ID", payload.session_id], ["观察时间", localTime(payload.observed_at_utc_ms)], ["UTC 时间", utcTime(payload.observed_at_utc_ms)], ["来源 ID", item.source_id]]),
    node("div", "notice", "事件反映采集端报告的状态，观察时间不等于声音精确发生时间。本地删除事件不表示服务端原音已删除；未记录的区间仍为未知覆盖。"),
    node("h3", "", "原始事件内容"), node("pre", "", JSON.stringify(payload, null, 2)));
}
function readFilters() {
  const filters = {};
  for (const [id, key] of [["session", "session_id"], ["query", "q"], ["status", "status"]]) {
    const value = $(id).value.trim();
    if (value) filters[key] = value;
  }
  for (const [id, key] of [["from", "from_utc_ms"], ["to", "to_utc_ms"]]) {
    if ($(id).value) {
      const value = new Date($(id).value).getTime();
      if (!Number.isFinite(value) || value < 0) throw new Error("请选择有效的时间（不早于 1970 年）。");
      filters[key] = value;
    }
  }
  if (filters.from_utc_ms !== undefined && filters.to_utc_ms !== undefined && filters.from_utc_ms >= filters.to_utc_ms) throw new Error("结束时间必须晚于开始时间。");
  state.filters = filters;
}
function switchView(view) {
  state.view = view;
  state.offset = 0;
  $("segments-tab").setAttribute("aria-pressed", String(view === "segments"));
  $("events-tab").setAttribute("aria-pressed", String(view === "events"));
  $("stats").hidden = view !== "segments";
  for (const field of document.querySelectorAll(".segment-filter")) field.hidden = view !== "segments";
  $("list-title").textContent = view === "segments" ? "音频片段" : "采集事件";
  loadList();
}
$("filters").addEventListener("submit", (event) => {
  event.preventDefault();
  try { readFilters(); state.offset = 0; loadList(); } catch (error) { feedback(error.message, true); }
});
$("reset-filters").addEventListener("click", () => { $("filters").reset(); state.filters = {}; state.offset = 0; loadList(); });
$("refresh").addEventListener("click", loadList);
$("prev").addEventListener("click", () => { state.offset = Math.max(0, state.offset - state.limit); loadList(); });
$("next").addEventListener("click", () => { state.offset += state.limit; loadList(); });
$("segments-tab").addEventListener("click", () => switchView("segments"));
$("events-tab").addEventListener("click", () => switchView("events"));
loadList();
