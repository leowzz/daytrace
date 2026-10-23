"""Single-sample ASR comparison. Never sends the reference text to a model."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import platform
from pathlib import Path
import statistics
import time
import unicodedata
from datetime import datetime, timezone


def normalize(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKC", text).casefold()
                   if not c.isspace() and not unicodedata.category(c).startswith("P"))


def score(reference: str, hypothesis: str) -> dict:
    ref, hyp = normalize(reference), normalize(hypothesis)
    previous = list(range(len(hyp) + 1))
    for i, a in enumerate(ref, 1):
        current = [i]
        for j, b in enumerate(hyp, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (a != b)))
        previous = current
    return {"normalized_reference": ref, "normalized_hypothesis": hyp,
            "edit_distance": previous[-1], "reference_characters": len(ref),
            "cer": previous[-1] / len(ref) if ref else None, "exact_match": ref == hyp}


def decode_audio(path: Path):
    import av
    import numpy as np
    frames = []
    resampler = av.AudioResampler(format="fltp", layout="mono", rate=16000)
    with av.open(str(path)) as container:
        for frame in container.decode(audio=0):
            frames.extend(part.to_ndarray().reshape(-1) for part in resampler.resample(frame))
        frames.extend(part.to_ndarray().reshape(-1) for part in resampler.resample(None))
    return np.concatenate(frames).astype(np.float32)


def quantize_linears_in_place(module):
    """Convert one layer at a time to avoid a full FP32 copy of the model.

    This is an experimental PyTorch dynamic INT8 configuration, not an
    upstream Qwen quantized checkpoint. Nonlinear modules remain FP32.
    """
    import torch
    count = 0
    for name, child in list(module.named_children()):
        if isinstance(child, torch.nn.Linear):
            wrapper = torch.nn.Sequential(child.float())
            quantized = torch.ao.quantization.quantize_dynamic(
                wrapper, {torch.nn.Linear}, dtype=torch.qint8, inplace=True)
            setattr(module, name, quantized[0])
            count += 1
            del wrapper, quantized
        else:
            count += quantize_linears_in_place(child)
    if platform.system() == "Linux":
        # Release temporary FP32 packing buffers before converting the next
        # subtree; otherwise glibc arenas can retain several model copies.
        import ctypes
        import gc
        gc.collect()
        trim = getattr(ctypes.CDLL(None), "malloc_trim", None)
        if trim is not None:
            trim(0)
    return count


def build_engine(args):
    directory = args.model_dir
    if args.engine in {"sensevoice", "paraformer-streaming", "paraformer"}:
        import sherpa_onnx
        common = {"tokens": str(directory / "tokens.txt"), "num_threads": args.threads, "provider": "cpu"}
        if args.engine == "sensevoice":
            model = sherpa_onnx.OfflineRecognizer.from_sense_voice(
                model=str(directory / "model.int8.onnx"), language="zh", use_itn=False, **common)
        elif args.engine == "paraformer":
            model = sherpa_onnx.OfflineRecognizer.from_paraformer(paraformer=str(directory / "model.int8.onnx"), **common)
        else:
            model = sherpa_onnx.OnlineRecognizer.from_paraformer(
                encoder=str(directory / "encoder.int8.onnx"), decoder=str(directory / "decoder.int8.onnx"),
                enable_endpoint_detection=False, **common)

        def transcribe(samples):
            stream = model.create_stream()
            partials = []
            if args.engine == "paraformer-streaming":
                import numpy as np
                # 100 ms delivery chunks; 1 s zero padding only flushes the stream.
                # The file is replayed without sleeping; do not call compute time end-to-end latency.
                padded = np.concatenate([samples, np.zeros(16000, dtype=np.float32)])
                last_text = ""
                for start in range(0, len(padded), 1600):
                    stream.accept_waveform(16000, padded[start:start + 1600])
                    while model.is_ready(stream):
                        model.decode_stream(stream)
                    text = model.get_result(stream)
                    if text != last_text:
                        partials.append({"audio_supplied_ms": min(start + 1600, len(padded)) / 16,
                                         "text": text})
                        last_text = text
                stream.input_finished()
                while model.is_ready(stream):
                    model.decode_stream(stream)
                return {"text": model.get_result(stream), "partials": partials,
                        "tail_padding_ms": 1000, "timestamps": None}
            stream.accept_waveform(16000, samples)
            model.decode_stream(stream)
            result = stream.result
            return {"text": result.text, "timestamps": list(result.timestamps), "tokens": list(result.tokens)}
        return transcribe, lambda: None

    if args.engine == "qwen3":
        import torch
        from qwen_asr import Qwen3ASRModel
        torch.set_num_threads(args.threads)
        device = "cuda:0" if args.device == "cuda" else "cpu"
        dtype = args.dtype if args.dtype != "auto" else ("bfloat16" if args.device == "cuda" else "float32")
        loading_dtype = "bfloat16" if args.quantization == "dynamic-int8" else dtype
        model = Qwen3ASRModel.from_pretrained(str(directory), dtype=getattr(torch, loading_dtype),
                                             device_map=device, attn_implementation="sdpa",
                                             max_inference_batch_size=1, max_new_tokens=128)
        if args.quantization == "dynamic-int8":
            torch.backends.quantized.engine = "fbgemm"
            args.quantized_linear_layers = quantize_linears_in_place(model.model)
            model.model.float()
            model.dtype = torch.float32
        def transcribe(samples):
            with torch.inference_mode():
                result = model.transcribe(audio=(samples, 16000), language="Chinese")[0]
            return {"text": result.text, "language": result.language, "timestamps": None}
        return transcribe, torch.cuda.synchronize if args.device == "cuda" else lambda: None
    raise ValueError(args.engine)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=["sensevoice", "paraformer-streaming", "paraformer", "qwen3"], required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--sample-dir", type=Path, default=Path(__file__).parent / "samples/107b514e-3b27-4aed-b2ef-4d81beff6590")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--device", choices=["cpu", "cuda"], default="cuda", help="Qwen device; sherpa models always use CPU")
    parser.add_argument("--dtype", choices=["auto", "float32", "bfloat16"], default="auto",
                        help="Qwen precision; auto uses FP32 on CPU, BF16 on CUDA")
    parser.add_argument("--quantization", choices=["none", "dynamic-int8"], default="none",
                        help="Experimental Qwen CPU linear-layer dynamic INT8 (FBGEMM); FP32 activations")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--host-label", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.runs < 1 or args.threads < 1:
        parser.error("runs and threads must be positive")
    if args.quantization != "none" and (args.engine != "qwen3" or args.device != "cpu" or args.dtype == "bfloat16"):
        parser.error("dynamic-int8 requires Qwen CPU and auto/float32 dtype")
    manifest = json.loads((args.sample_dir / "sample.json").read_text(encoding="utf-8"))
    audio = args.sample_dir / manifest["audio"]["file"]
    assert hashlib.sha256(audio.read_bytes()).hexdigest() == manifest["audio"]["sha256"]
    reference = (args.sample_dir / manifest["reference"]["file"]).read_text(encoding="utf-8").rstrip("\r\n")
    started = time.perf_counter()
    samples = decode_audio(audio)
    decode_seconds = time.perf_counter() - started
    duration_seconds = len(samples) / 16000
    started = time.perf_counter()
    transcribe, synchronize = build_engine(args)
    synchronize()
    init_seconds = time.perf_counter() - started
    results = []
    for index in range(args.runs + 1):
        if platform.system() == "Linux":
            import resource
            usage_before = resource.getrusage(resource.RUSAGE_SELF)
        synchronize()
        started = time.perf_counter()
        result = transcribe(samples)
        synchronize()
        elapsed = time.perf_counter() - started
        if platform.system() == "Linux":
            usage_after = resource.getrusage(resource.RUSAGE_SELF)
            result["major_page_faults"] = usage_after.ru_majflt - usage_before.ru_majflt
            result["cpu_seconds"] = (usage_after.ru_utime + usage_after.ru_stime
                                     - usage_before.ru_utime - usage_before.ru_stime)
        result.update({"seconds": elapsed, "rtf": elapsed / duration_seconds, "score": score(reference, result["text"])})
        results.append(result)
        print(json.dumps({"run": index, "cold": index == 0, **result}, ensure_ascii=True), flush=True)
    packages = {}
    for name in ["sherpa-onnx", "av", "numpy", "torch", "qwen-asr", "transformers"]:
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            pass
    output = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "engine": args.engine,
              "host": args.host_label, "platform": platform.platform(), "python": platform.python_version(),
              "packages": packages, "threads": args.threads, "device": args.device if args.engine == "qwen3" else "cpu",
              "dtype": (args.dtype if args.dtype != "auto" else ("bfloat16" if args.device == "cuda" else "float32")) if args.engine == "qwen3" else "int8",
              "quantization": args.quantization if args.engine == "qwen3" else "upstream-int8",
              "quantized_linear_layers": getattr(args, "quantized_linear_layers", None),
              "sample_id": manifest["sample_id"],
              "audio_sha256": manifest["audio"]["sha256"], "duration_seconds": duration_seconds,
              "audio_decode_seconds": decode_seconds, "model_init_seconds": init_seconds,
              "model_provenance": json.loads((args.model_dir / "provenance.json").read_text(encoding="utf-8")),
              "normalization": "NFKC, casefold, remove Unicode punctuation and whitespace; retain fillers",
              "reference": reference, "reference_sent_to_model": False,
              "cold_run": results[0], "warm_runs": results[1:],
              "warm_median_seconds": statistics.median(r["seconds"] for r in results[1:]),
              "warm_median_rtf": statistics.median(r["rtf"] for r in results[1:]),
              "historical_baseline": {"text": manifest["source_segment"]["transcript"]["text"],
                                      "score": score(reference, manifest["source_segment"]["transcript"]["text"])},
              "limitations": ["one short sample, not a general accuracy estimate", "excludes network and endpoint detection latency",
                              "streaming replay is unpaced; audio_supplied_ms is input offset, not measured wall-clock latency"]}
    if args.engine == "qwen3" and args.device == "cuda":
        import torch
        output["gpu"] = {"name": torch.cuda.get_device_name(0), "peak_allocated_bytes": torch.cuda.max_memory_allocated(),
                         "peak_reserved_bytes": torch.cuda.max_memory_reserved()}
    if platform.system() == "Linux":
        import resource
        output["process_peak_rss_bytes"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"saved": str(args.output), "warm_median_seconds": output["warm_median_seconds"]}, ensure_ascii=True))


if __name__ == "__main__":
    main()
