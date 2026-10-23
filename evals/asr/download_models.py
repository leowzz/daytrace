"""Download pinned public model files; weights stay outside Git in artifacts/."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

MODELS = {
    "paraformer": ("csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09",
                   "906992d326ebf0c5171cde675aa0902be9e5bc6c", ["model.int8.onnx", "tokens.txt"]),
    "sensevoice": ("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                   "2365baeacb507f821a0c8120fcee3d484dba7a07", ["model.int8.onnx", "tokens.txt"]),
    "paraformer-streaming": ("csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
                             "8e40c43232a1c5c66c82111efc5820d3accca11b", ["encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"]),
    "qwen3": ("Qwen/Qwen3-ASR-0.6B", "5eb144179a02acc5e5ba31e748d22b0cf3e303b0",
              ["config.json", "generation_config.json", "preprocessor_config.json", "tokenizer_config.json",
               "chat_template.json", "merges.txt", "vocab.json", "model.safetensors"]),
}


def file_hash(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download(name, root, endpoint):
    repo, revision, files = MODELS[name]
    directory = root / name
    directory.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(f"{endpoint}/api/models/{repo}/revision/{revision}?blobs=true", timeout=60) as response:
        metadata = json.load(response)
    siblings = {item["rfilename"]: item for item in metadata["siblings"]}

    def get(filename):
        path = directory / filename
        expected = siblings[filename].get("lfs", {}).get("sha256")
        if path.exists() and (not expected or file_hash(path) == expected):
            return {"file": filename, "sha256": file_hash(path), "size": path.stat().st_size}
        digest = hashlib.sha256()
        temporary = path.with_suffix(path.suffix + ".download")
        with urllib.request.urlopen(f"{endpoint}/{repo}/resolve/{revision}/{filename}", timeout=120) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
        if expected and digest.hexdigest() != expected:
            raise ValueError(f"Model hash mismatch: {filename}")
        temporary.replace(path)
        print(f"Downloaded {name}/{filename}: {path.stat().st_size} bytes", flush=True)
        return {"file": filename, "sha256": digest.hexdigest(), "size": path.stat().st_size}

    with ThreadPoolExecutor(max_workers=3) as pool:
        downloaded = list(pool.map(get, files))
    (directory / "provenance.json").write_text(json.dumps({"repo": repo, "revision": revision, "files": downloaded}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("models", nargs="+", choices=MODELS)
    parser.add_argument("--root", type=Path, default=Path("artifacts/asr-benchmark/models"))
    parser.add_argument("--endpoint", default="https://huggingface.co")
    args = parser.parse_args()
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda name: download(name, args.root, args.endpoint.rstrip("/")), args.models))
