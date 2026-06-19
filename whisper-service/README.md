---
title: Heardly Whisper Service
emoji: 🎙️
colorFrom: blue
colorTo: green
sdk: docker
app_port: 7860
pinned: false
---

# Heardly Whisper Service

Speech-to-text sidecar for [Heardly](https://github.com/) built on FastAPI +
`faster-whisper`. Deployed as a Docker Space.

## Endpoints
- `GET /health` — model/device status.
- `POST /transcribe` — multipart `file` upload → `{language, duration, full_text, segments}`.

The `app_port: 9000` line above tells Hugging Face Spaces to route traffic to
the port this container listens on (set in the `Dockerfile`).
