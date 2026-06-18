import os
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from faster_whisper import WhisperModel

MODEL_NAME = os.environ.get("WHISPER_MODEL", "base")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "int8")
DEVICE = os.environ.get("WHISPER_DEVICE", "cpu")
LANGUAGE = os.environ.get("WHISPER_LANGUAGE") or None

_state: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    print(f"[whisper] loading model={MODEL_NAME} device={DEVICE} compute_type={COMPUTE_TYPE}", flush=True)
    _state["model"] = WhisperModel(MODEL_NAME, device=DEVICE, compute_type=COMPUTE_TYPE)
    print("[whisper] model ready", flush=True)
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "device": DEVICE,
        "compute_type": COMPUTE_TYPE,
        "language": LANGUAGE or "auto",
    }


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    if file.filename is None and not file.content_type:
        raise HTTPException(status_code=400, detail="missing file")

    suffix = Path(file.filename or "").suffix or ".wav"
    chunk_size = 1024 * 1024
    bytes_written = 0
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp_path = tmp.name
        while True:
            chunk = await file.read(chunk_size)
            if not chunk:
                break
            tmp.write(chunk)
            bytes_written += len(chunk)
    print(f"[whisper] received {bytes_written / (1024*1024):.1f} MB → {tmp_path}", flush=True)

    try:
        model: WhisperModel = _state["model"]
        segments_iter, info = model.transcribe(
            tmp_path,
            language=LANGUAGE,
            vad_filter=True,
            beam_size=1,
        )
        segments = []
        full_text_parts = []
        for s in segments_iter:
            text = s.text.strip()
            segments.append({
                "start": round(s.start, 3),
                "end": round(s.end, 3),
                "text": text,
            })
            full_text_parts.append(text)
        return {
            "language": info.language,
            "language_probability": round(info.language_probability, 3),
            "duration": round(info.duration, 3),
            "full_text": " ".join(full_text_parts),
            "segments": segments,
        }
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
