# Whisper transcription service — Hugging Face Spaces (Docker SDK) compatible.
# Docs: https://huggingface.co/docs/hub/spaces-sdks-docker
FROM python:3.11

# ffmpeg is required by faster-whisper to decode audio. Installed as root
# before we drop to the non-root "user" that Hugging Face expects.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1000 user
USER user
ENV PATH="/home/user/.local/bin:$PATH"

WORKDIR /app

COPY --chown=user ./requirements.txt requirements.txt
RUN pip install --no-cache-dir --upgrade -r requirements.txt

COPY --chown=user . /app

ENV WHISPER_MODEL=base \
    WHISPER_COMPUTE_TYPE=int8 \
    WHISPER_DEVICE=cpu

# Hugging Face Spaces route traffic to port 7860 by default.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "7860"]
