# Heardly (OtterFree) — Technical Documentation

> An open, self-hostable alternative to Otter.ai. Heardly records or ingests
> meeting audio, transcribes it locally with Whisper, generates AI insights
> (summary, action items, decisions, topics) with an LLM, lets users chat with
> their transcripts, search across them, export them, and fan summaries out to
> Slack / Email.

**Status:** working end-to-end for upload → transcribe → insights → chat →
search → export, plus Recall.ai meeting-bot capture. The outbound-integration
backend (Slack/Email) is fully implemented and wired; the integrations *UI* is
still presented as "coming soon" (connect buttons disabled).

---

## Table of contents

1. [System overview](#1-system-overview)
2. [Architecture](#2-architecture)
3. [Technology stack](#3-technology-stack)
4. [Data model](#4-data-model)
5. [Component reference](#5-component-reference)
6. [End-to-end flows](#6-end-to-end-flows)
   - [6.1 Authentication](#61-authentication-signup--login)
   - [6.2 Upload → transcript → insights](#62-upload--transcript--insights-core-pipeline)
   - [6.3 Meeting bot capture (Recall.ai)](#63-meeting-bot-capture-recallai)
   - [6.4 AI chat (per-recording and global)](#64-ai-chat-per-recording-and-global)
   - [6.5 Full-text search](#65-full-text-search)
   - [6.6 Export (DOCX / PDF / TXT)](#66-export-docx--pdf--txt)
   - [6.7 Outbound integrations (Slack / Email)](#67-outbound-integrations-slack--email)
7. [Asynchronous & scheduled processing](#7-asynchronous--scheduled-processing)
8. [Configuration reference](#8-configuration-reference)
9. [Security model](#9-security-model)
10. [HTTP API reference](#10-http-api-reference)
11. [Running locally](#11-running-locally)
12. [Known gaps & future work](#12-known-gaps--future-work)

---

## 1. System overview

Heardly turns spoken meetings into searchable, structured knowledge. There are
two ways audio enters the system:

1. **Upload** — a user uploads an existing audio file (`.mp3`, `.wav`, etc.).
2. **Meeting bot** — a user pastes a Zoom / Google Meet / Teams link; Heardly
   dispatches a [Recall.ai](https://recall.ai) bot that joins the call, records
   the mixed audio, and hands the recording back when the meeting ends.

From there both paths converge on the same pipeline:

```
audio ──► Whisper (local transcription) ──► Transcript
                                              │
                                              ▼
                                   LLM (OpenAI) ──► Insight
                                              │   (title, summary,
                                              │    action items, decisions,
                                              │    topics)
                                              ▼
                              Slack / Email summary fan-out
```

On top of the stored transcript and insight, the user can:

- **Chat** with a single recording, or **globally** across all their recordings.
- **Search** full-text across every transcript (Postgres FTS).
- **Export** a recording (transcript + insights) to DOCX, PDF, or plain text.

---

## 2. Architecture

Heardly is a modular monolith (one Spring Boot application) plus one sidecar
microservice for transcription, and several external SaaS dependencies.

```
                         ┌─────────────────────────────────────────────┐
                         │            Browser (Thymeleaf UI)            │
                         │  login · dashboard · upload · transcript ·   │
                         │  schedule · chat · search · integrations     │
                         └───────────────┬─────────────────────────────┘
                                         │ HTTP (session cookie auth)
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                    Heardly — Spring Boot 4 application (port 8080)              │
│                                                                                │
│  Controllers ── Services ── Repositories (Spring Data JPA)                     │
│                    │                                                            │
│   ┌────────────────┼───────────────────────────────────────────────────────┐  │
│   │ RecordingService  MeetingService  InsightService  ChatService           │  │
│   │ SearchService     ExportService   IntegrationService  UserService       │  │
│   │ Clients: WhisperClient · RecallClient · OpenAiClient · SlackClient ·     │  │
│   │          EmailClient                                                     │  │
│   └──────────────────────────────────────────────────────────────────────┘   │
│   Async executor (2–4 threads) + @Scheduled poller                             │
└──────┬─────────────┬───────────────┬──────────────┬────────────┬──────────────┘
       │             │               │              │            │
       ▼             ▼               ▼              ▼            ▼
 ┌──────────┐  ┌────────────┐  ┌───────────┐  ┌──────────┐  ┌──────────────┐
 │PostgreSQL│  │  Whisper   │  │ Recall.ai │  │  OpenAI  │  │ Slack webhook│
 │          │  │  service   │  │  (bots /  │  │  Chat    │  │   / SMTP     │
 │ (JPA)    │  │ (FastAPI,  │  │ recording)│  │Completions│ │  (Gmail)     │
 │          │  │  port 9000)│  │           │  │          │  │              │
 └──────────┘  └────────────┘  └───────────┘  └──────────┘  └──────────────┘
```

### Components

| Component | Role | Tech |
|-----------|------|------|
| **Heardly web app** | All business logic, persistence, UI, API | Spring Boot 4, Java 17 |
| **Whisper service** | Speech-to-text; returns text + timed segments | Python, FastAPI, `faster-whisper` |
| **PostgreSQL** | System of record + full-text search engine | Postgres |
| **Recall.ai** | Meeting-join bot + cloud recording | External SaaS (REST) |
| **OpenAI** | Insight extraction + chat | External SaaS (`gpt-4o-mini`) |
| **Slack / SMTP** | Outbound summary delivery | Incoming webhook / Gmail SMTP |
| **Local disk** | Audio file storage (`~/otterfree/audio`) | Filesystem |

The Whisper service runs locally (Docker), so **raw audio never leaves the
host** — only the resulting transcript text is sent to OpenAI for insights/chat.

---

## 3. Technology stack

**Backend**
- Java 17, Spring Boot **4.0.6** (`spring-boot-starter-parent`)
- Spring MVC (`spring-boot-starter-webmvc`), Spring Data JPA, Spring Security
- Spring Security OAuth2 client + Spring Mail starters
- Thymeleaf (server-side templates) + `thymeleaf-extras-springsecurity6`
- Jackson 2.16.1 (explicitly pinned for Boot 4.x compatibility — the new
  `tools.jackson` artifacts are excluded in `pom.xml`)
- Apache POI 5.2.5 (`.docx` export) + OpenPDF 1.3.30 (`.pdf` export)
- Spring Cloud / Spring Cloud GCP BOMs imported (dependency management)
- PostgreSQL JDBC driver (runtime)

**Transcription service**
- Python 3.11, FastAPI 0.115, Uvicorn, `faster-whisper` 1.1.1, `ffmpeg`

**Persistence**
- PostgreSQL; Hibernate with `ddl-auto=update` (schema auto-managed from
  entities)

**Build / run**
- Maven (wrapper `mvnw` included), Spring Boot Maven plugin
- Docker / docker-compose for the Whisper sidecar

---

## 4. Data model

All primary keys are `UUID`. Most are assigned in application code
(`UUID.randomUUID()`); `Transcript.id` uses `@GeneratedValue(UUID)`. Timestamps
are `Instant`, set via JPA lifecycle callbacks (`@PrePersist` / `@PreUpdate`).

### Entity relationship diagram (logical)

```
   User (1) ───────────────< (N) Recording
     │                            │  1
     │                            ├──────── (0..1) Transcript     (unique recording_id)
     │                            ├──────── (0..1) Insight        (unique recording_id)
     │                            └──────── (N)    ChatMessage    (recording-scoped)
     │
     ├──────< (N) Meeting          (meeting.recording_id ─► Recording, nullable)
     ├──────< (N) Integration      (unique per user_id+type)
     └──────< (N) ChatMessage      (global chat: recording_id IS NULL)
```

Relationships are modeled by **UUID foreign-key columns**, not JPA
associations (no `@ManyToOne`/`@OneToMany`). Joins are done explicitly in
services/repositories. This keeps entities flat and avoids lazy-loading
surprises (`spring.jpa.open-in-view=false`).

### Tables

#### `users`
| Column | Type | Notes |
|--------|------|-------|
| `id` | uuid (PK) | app-assigned |
| `email` | varchar(254), unique, indexed | normalized lowercase |
| `password_hash` | varchar(100) | BCrypt |
| `display_name` | varchar(128), nullable | |
| `enabled` | boolean | login gate |
| `created_at` | timestamp | |

#### `recordings`
Represents one piece of audio (uploaded or bot-captured).
| Column | Type | Notes |
|--------|------|-------|
| `id` | uuid (PK) | |
| `user_id` | uuid, indexed | owner |
| `original_filename` | varchar(512) | display name |
| `content_type` | varchar(128) | e.g. `audio/mpeg` |
| `size_bytes` | bigint | |
| `audio_file_path` | varchar(1024) | absolute path on disk |
| `status` | enum, indexed | `UPLOADED · TRANSCRIBING · COMPLETED · FAILED` |
| `error_message` | varchar(2048) | failure detail |
| `created_at` / `updated_at` | timestamp | |

#### `transcripts` (0..1 per recording, `recording_id` unique)
| Column | Type | Notes |
|--------|------|-------|
| `id` | uuid (PK) | generated |
| `recording_id` | uuid, unique index | |
| `language` | varchar(16) | detected |
| `duration_seconds` | double | |
| `full_text` | TEXT | concatenated transcript |
| `segments_json` | TEXT | JSON array `[{start,end,text}]` |
| `created_at` | timestamp | |

#### `insights` (0..1 per recording, `recording_id` unique)
| Column | Type | Notes |
|--------|------|-------|
| `id` | uuid (PK) | |
| `recording_id` | uuid, unique index | |
| `status` | enum | `PENDING · COMPLETED · FAILED` |
| `smart_title` | varchar(256) | AI title |
| `summary` | TEXT | |
| `action_items_json` | TEXT | `[{task,owner,due}]` |
| `key_topics_json` | TEXT | `["topic", ...]` |
| `decisions_json` | TEXT | `["decision", ...]` |
| `model_name` | varchar(64) | e.g. `gpt-4o-mini` |
| `error_message` | varchar(2048) | |
| `created_at` / `updated_at` | timestamp | |

#### `meetings` (Recall.ai bot lifecycle)
| Column | Type | Notes |
|--------|------|-------|
| `id` | uuid (PK) | |
| `user_id` | uuid, indexed | |
| `meeting_url` | varchar(2048) | Zoom/Meet/Teams link |
| `bot_name` | varchar(100) | display name shown in the call |
| `recall_bot_id` | varchar(64), indexed | id returned by Recall |
| `status` | enum, indexed | see below |
| `recording_id` | uuid, nullable | linked once audio is downloaded |
| `error_message` | varchar(2048) | |
| `created_at` / `updated_at` | timestamp | |

`MeetingStatus`: `SCHEDULED → JOINING → WAITING → RECORDING → ENDED →
DOWNLOADING_AUDIO → DONE`, plus terminal `FAILED` / `CANCELLED`.

#### `chat_messages`
Stores both per-recording and global chat turns.
| Column | Type | Notes |
|--------|------|-------|
| `id` | uuid (PK) | |
| `recording_id` | uuid, nullable | **NULL ⇒ global chat** |
| `user_id` | uuid | owner |
| `role` | enum | `USER · ASSISTANT` |
| `content` | TEXT | |
| `model_name` | varchar(64) | on assistant turns |
| `created_at` | timestamp | composite index `(recording_id, user_id, created_at)` |

#### `integrations`
| Column | Type | Notes |
|--------|------|-------|
| `id` | uuid (PK) | |
| `user_id` | uuid | unique with `type` |
| `type` | enum | `SLACK · GMAIL` |
| `target` | varchar(2048) | Slack webhook URL **or** destination email |
| `enabled` | boolean | |
| `created_at` / `updated_at` | timestamp | |

---

## 5. Component reference

### Controllers
| Class | Base path | Responsibility |
|-------|-----------|----------------|
| `AuthController` | `/login`, `/signup` | Render auth pages; handle signup + auto-login |
| `ViewController` | `/`, `/upload`, `/meetings/new`, `/integrations`, `/chat`, `/recordings/{id}` | Server-rendered Thymeleaf pages |
| `RecordingController` | `/api/recordings` | Upload, list, get, delete, retry, transcript/insight fetch, regenerate, exports |
| `MeetingController` | `/api/meetings` | Schedule bot, list, get, cancel |
| `ChatController` | `/api/recordings/{id}/chat` | Per-recording chat history / ask / clear |
| `GlobalChatController` | `/api/chat` | Cross-recording chat history / ask / clear |
| `SearchController` | `/api/search` | Full-text search |

### Services
| Class | Responsibility |
|-------|----------------|
| `RecordingService` | Upload persistence, async transcription orchestration, stats, delete/retry, cascade cleanup |
| `MeetingService` | Recall bot scheduling, status polling, audio download → Recording creation |
| `InsightService` | LLM prompt + JSON parsing into structured insight; regenerate |
| `ChatService` | Per-recording + global RAG-style chat (builds context, history window) |
| `SearchService` | Postgres `tsvector` full-text query with highlighted snippets |
| `ExportService` | DOCX (POI) and PDF (OpenPDF) rendering |
| `IntegrationService` | Connection management + summary fan-out to Slack/Email |
| `FileStorageService` | Save/resolve audio files under the configured audio dir |
| `UserService` | `UserDetailsService` + signup with BCrypt |

### Clients (external system adapters)
| Class | Talks to | Notes |
|-------|----------|-------|
| `WhisperClient` | Whisper FastAPI service | multipart POST `/transcribe` |
| `RecallClient` | Recall.ai REST API | create/get/leave bot; parse status & audio URL |
| `OpenAiClient` | OpenAI Chat Completions | `complete()` (JSON mode) + `chat()`; `@Primary` `LlmClient` |
| `SlackClient` | Slack incoming webhook | POST `{text}` |
| `EmailClient` | SMTP (Gmail) | HTML email via `JavaMailSender`; auto-disabled if no host |

`LlmClient` is an interface; `OpenAiClient` is the `@Primary` implementation,
so swapping LLM providers later is a single-bean change.

### Configuration classes
- `SecurityConfig` — form login, BCrypt, public/protected route rules.
- `AsyncConfig` — `@EnableAsync` + `@EnableScheduling`, the `taskExecutor`
  thread pool, and a customized Jackson `ObjectMapper` (JSR-310 dates, ISO
  format).

---

## 6. End-to-end flows

### 6.1 Authentication (signup & login)

**Signup** (`POST /signup`, form-encoded):
1. `UserService.signup` validates email (contains `@`) and password (≥ 8 chars),
   rejects duplicates (case-insensitive), BCrypt-hashes the password, saves the
   `User`.
2. `AuthController` immediately builds an authenticated
   `UsernamePasswordAuthenticationToken`, stores it in the `SecurityContext`,
   and persists it to the HTTP session — so the user is logged in right after
   signup and redirected to `/`.

**Login** is standard Spring Security form login (`/login`), backed by
`UserService` implementing `UserDetailsService`. Every user has authority
`ROLE_USER`. Sessions are cookie-based (`JSESSIONID`).

Throughout the app, controllers resolve the current user from the
`UserDetails` principal: `userService.getByEmail(principal.getUsername())`
→ `User.id` (a `UUID`), which scopes every query.

---

### 6.2 Upload → transcript → insights (core pipeline)

This is the heart of the system. Sequence:

```
Browser            RecordingController     RecordingService      Whisper       (events)         InsightService        IntegrationService
  │  POST /api/recordings (multipart)                                                                                   
  ├───────────────────►│                                                                                               
  │                    │ upload(file,user) ──► FileStorageService.save → Recording(status=UPLOADED) persisted          
  │                    │ transcribeAsync(id)  [returns immediately, runs on async pool]                                
  │ ◄── 201 Created ───┤                                                                                               
  │                                          │ status=TRANSCRIBING                                                     
  │                                          │ POST /transcribe (multipart) ──►│                                       
  │                                          │ ◄── {language,duration,full_text,segments}                              
  │                                          │ save Transcript; status=COMPLETED                                       
  │                                          │ publish TranscriptionCompletedEvent ─────►│                             
  │                                                                            (if OpenAI enabled) generateAsync(id)   
  │                                                                                       │ status=PENDING            
  │                                                                                       │ LLM complete() → parse JSON
  │                                                                                       │ save Insight(COMPLETED)   
  │                                                                                       │ publish InsightGeneratedEvent ─►│
  │                                                                                                                  dispatchSummary(id)
```

**Step by step:**

1. **Upload** — `RecordingController.upload` (`POST /api/recordings`,
   `multipart/form-data`, field `file`). Max size 500 MB
   (`spring.servlet.multipart.max-file-size`).
   - `RecordingService.upload` rejects empty files, generates a recording
     `UUID`, and `FileStorageService.save` writes the bytes to
     `{audio-dir}/{uuid}{ext}`. A `Recording` row is saved with status
     `UPLOADED`.
   - The controller then calls `transcribeAsync(id)` and returns `201` with the
     `RecordingResponse` — the HTTP request does **not** block on transcription.

2. **Transcription** — `RecordingService.transcribeAsync` (`@Async`):
   - Sets status `TRANSCRIBING`.
   - Reads the audio path, verifies the file exists on disk.
   - `WhisperClient.transcribe` POSTs the file (multipart) to the Whisper
     service `/transcribe` and maps the response to a `TranscriptionResult`
     (`language`, `duration`, `full_text`, `segments`).
   - Persists a `Transcript` (segments serialized to JSON). Sets status
     `COMPLETED`.
   - Publishes a `TranscriptionCompletedEvent`.
   - On any exception: status `FAILED` with a truncated error message.

3. **Whisper service** (`whisper-service/app.py`):
   - On startup loads the `faster-whisper` model (default `base`, `int8`, CPU).
   - `/transcribe` streams the upload to a temp file, runs the model with
     `vad_filter=True` and `beam_size=1`, and returns `language`,
     `language_probability`, `duration`, `full_text`, and timed `segments`.
   - `/health` reports model/device status.

4. **Insight generation** — `InsightEventListener.onTranscriptionCompleted`:
   - If OpenAI is **not** configured, logs and skips (transcription still
     succeeds — insights are optional).
   - Otherwise calls `InsightService.generateAsync` (also `@Async`).
   - `InsightService.generate`:
     - Loads recording + transcript (must be non-empty).
     - Upserts an `Insight` with status `PENDING`.
     - Builds a strict **JSON-schema system prompt** and sends the transcript
       (truncated to 60k chars) to `OpenAiClient.complete` using OpenAI
       **JSON mode** (`response_format: json_object`, `temperature 0.3`).
     - Parses `title`, `summary`, `key_topics`, `decisions`, `action_items` and
       stores them (lists kept as JSON strings). Status `COMPLETED`,
       `model_name` recorded.
     - On failure: status `FAILED` + error message.
   - On success it publishes an `InsightGeneratedEvent`.

5. **Summary fan-out** — `InsightEventListener.onInsightGenerated` →
   `IntegrationService.dispatchSummary` (see [6.7](#67-outbound-integrations-slack--email)).

**Polling for completion (UI):** the recording detail page
(`/recordings/{id}`) and the dashboard render current DB status. Clients can
poll `GET /api/recordings/{id}` (status), `/transcript`, and `/insights`
(which return `404` with `{"error": "...not ready yet"}` until available).

**Retry & regenerate:**
- `POST /api/recordings/{id}/retry` resets a `FAILED`/`COMPLETED` recording to
  `UPLOADED` and re-runs `transcribeAsync` (rejects if currently
  `TRANSCRIBING` or audio missing).
- `POST /api/recordings/{id}/insights/regenerate` re-runs `generateAsync`
  against the existing transcript.

**Deletion** (`DELETE /api/recordings/{id}`) cascades in `RecordingService`:
deletes chat messages, transcript, insight, unlinks any meetings
(`recording_id → null`), deletes the recording row, then best-effort deletes
the audio file from disk.

---

### 6.3 Meeting bot capture (Recall.ai)

Lets a user capture a live Zoom / Google Meet / Teams meeting without uploading
anything.

**Scheduling** (`POST /api/meetings`, body `{meetingUrl, botName?}`):
1. `MeetingService.schedule` validates the URL is a supported platform
   (`zoom.us/`, `meet.google.com/`, `teams.microsoft.com/`) and picks a bot
   name (default `Heardly Notetaker`).
2. Saves a `Meeting` with status `SCHEDULED`.
3. `RecallClient.createBot` POSTs to Recall `/bot/` requesting
   `audio_mixed_mp3` recording. The returned bot `id` is stored; status → `JOINING`.
4. On failure, status → `FAILED` with the error captured.

**Polling** — `MeetingService.pollActiveBots` is `@Scheduled`
(`fixedDelay = otterfree.recall.poll-interval-ms`, default 30s; initial delay
5s):
1. Loads all meetings in active states (`JOINING, WAITING, RECORDING, ENDED`).
2. For each, `RecallClient.getBot` fetches current state.
   `extractStatusCode` reads the latest `status_changes[].code` and
   `mapRecallStatus` maps Recall codes to `MeetingStatus`:
   - `joining_call → JOINING`, `in_waiting_room → WAITING`,
     `in_call_recording / recording_permission_allowed / in_call_not_recording → RECORDING`,
     `call_ended → ENDED`, `done → DONE`,
     `fatal / recording_permission_denied → FAILED`.
3. On transition to **`DONE`**:
   - `extractAudioDownloadUrl` digs the mixed-audio `download_url` out of the
     nested Recall response (`recordings[0].media_shortcuts.audio_mixed.data.download_url`).
   - `downloadAndCreateRecording` streams the MP3 to
     `{audio-dir}/{uuid}.mp3`, creates a `Recording` (status `UPLOADED`, owner =
     meeting owner), links `meeting.recording_id`, and calls
     `recordingService.transcribeAsync(...)`.
   - **From here the meeting joins the exact same pipeline as an upload**
     (transcript → insights → integrations).
4. On `FAILED`, captures the Recall `sub_code` if present.

**Cancel** (`DELETE /api/meetings/{id}`): if the bot is still active,
`RecallClient.leaveBot` is called and status → `CANCELLED`.

---

### 6.4 AI chat (per-recording and global)

Both modes are retrieval-grounded: the transcript(s) are injected into the
system prompt, and the model is instructed to answer **only** from that context.

**Per-recording chat** (`ChatService.ask`, via
`POST /api/recordings/{id}/chat`):
1. Requires OpenAI to be enabled, and a question.
2. Loads recording (ownership-checked) and transcript.
3. Takes up to the last **20** prior turns (`MAX_HISTORY`) for continuity.
4. Builds a system prompt embedding the transcript (truncated to **80k chars**),
   with strict rules: answer only from the transcript, say "I don't see that in
   this recording" when absent, be concise.
5. Persists the user turn, calls `OpenAiClient.chat` (free-form, no JSON mode,
   `temperature 0.3`), persists the assistant turn (with `model_name`), and
   returns it.
- `GET` returns history; `DELETE` clears it.

**Global chat** (`ChatService.globalAsk`, via `POST /api/chat`):
1. `buildGlobalContext` assembles a structured digest of the user's **completed**
   recordings (up to **60**): title, filename, date, duration, and the
   insight's summary / topics / decisions / action items (falling back to a
   600-char transcript snippet if no insight).
2. The system prompt includes **today's date** so the model can resolve
   relative phrases ("this week", "yesterday") and is told to cite recordings by
   title + date.
3. Same history-window + persistence pattern as per-recording chat. Global chat
   turns are stored with `recording_id = NULL`.

This is a lightweight RAG: context is built from pre-computed insights/snippets
rather than a vector store, which keeps the system dependency-free at the cost
of a bounded context window.

---

### 6.5 Full-text search

`SearchService.search` (via `GET /api/search?q=...`) runs a single native
PostgreSQL query, scoped to the user:

- Matches transcripts via `to_tsvector('english', full_text) @@
  plainto_tsquery('english', :q)`, **or** matches the recording filename via
  `LIKE`.
- Produces a highlighted snippet with `ts_headline` (custom markers `⟪ ⟫`),
  falling back to the first 160 chars when there's no headline.
- Ranks results by transcript-match first, then `ts_rank`, then recency; limit
  20.
- Returns `SearchHit(recordingId, filename, createdAt, snippet, matchedTranscript)`.

No separate search index/table is maintained — Postgres computes the
`tsvector` at query time over `transcripts.full_text`.

---

### 6.6 Export (DOCX / PDF / TXT)

`ExportService` renders a recording (transcript + insight) to downloadable
documents, exposed on `RecordingController`:

- `GET /api/recordings/{id}/transcript.txt` — raw `full_text`.
- `GET /api/recordings/{id}/transcript.docx` — Apache POI `XWPFDocument`.
- `GET /api/recordings/{id}/transcript.pdf` — OpenPDF document.

Both rich formats share structure: title (AI smart title, or filename),
metadata line (date · minutes · language · size), then — when insights are
`COMPLETED` — Summary, Action items, Key topics, Decisions, followed by the
**timestamped transcript** (each segment prefixed with `mm:ss → mm:ss`). If no
segments exist, the full text is emitted as one block. `safeFilename` slugifies
the title for the `Content-Disposition` header.

---

### 6.7 Outbound integrations (Slack / Email)

**Backend: fully implemented.** When an insight finishes, the
`InsightGeneratedEvent` triggers `IntegrationService.dispatchSummary`
(`@Async`, read-only):
1. Loads the recording, its owner, and the `COMPLETED` insight.
2. Loads the owner's `Integration` rows. For each enabled one:
   - **Slack** → `SlackClient.send(webhookUrl, mrkdwn)` — posts a formatted
     message: title, summary, action items, decisions, and a deep link
     `{base-url}/recordings/{id}`.
   - **Gmail/Email** → `EmailClient.send(to, subject, html)` — an HTML summary
     email with a "View full transcript" button.
3. Each send is wrapped in try/catch — a failing integration is logged and never
   breaks transcription or insight generation.

**Connection management** (`IntegrationService.connect / disconnect / sendTest`):
- `connect` validates the target (Slack webhook must start with
  `https://hooks.slack.com/`; Gmail must contain `@`) and upserts the per-user
  `Integration` (unique on `user_id + type`).
- `sendTest` posts a "Heardly is connected" verification message.
- `EmailClient` auto-disables unless `spring.mail.host` is set, so the app runs
  fine without SMTP.

**UI status:** the `/integrations` page (`integrations.html`) currently presents
Slack / Google Drive / Email as **"Coming soon"** with disabled Connect buttons,
while Recall.ai, OpenAI, and local Whisper are shown as connected. The Slack/
Email **backend and dispatch are live**, but there is **no `IntegrationController`
yet** wiring the connect/disconnect/test UI to `IntegrationService`. Completing
the feature is a matter of adding that controller + enabling the form.

---

## 7. Asynchronous & scheduled processing

`AsyncConfig` enables both async and scheduling:

- **`taskExecutor`** — `ThreadPoolTaskExecutor`, core 2 / max 4 / queue 50,
  thread prefix `otterfree-async-`. Backs every `@Async` method:
  `RecordingService.transcribeAsync`, `InsightService.generateAsync`,
  `IntegrationService.dispatchSummary`.
- **Scheduling** — `MeetingService.pollActiveBots` runs on a fixed delay to
  advance meeting state machines.
- **Events** — Spring `ApplicationEventPublisher` decouples the pipeline stages:
  `TranscriptionCompletedEvent` → insight generation;
  `InsightGeneratedEvent` → integration dispatch. Listeners live in
  `InsightEventListener`.

This event-driven design means each stage is independently retriable and
failure-isolated: a failed insight doesn't fail the transcript; a failed Slack
post doesn't fail the insight.

**Concurrency note:** with only 2–4 async threads and an in-process executor,
this is sized for a single-instance deployment. Multiple uploads beyond the
queue capacity (50) will be rejected by the executor; horizontal scaling would
require an external queue.

---

## 8. Configuration reference

All settings live in `src/main/resources/application.properties`. Secrets
should be supplied via environment variables in real deployments.

| Property | Default | Purpose |
|----------|---------|---------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/postgres` | DB connection |
| `spring.datasource.username/password` | `postgres/postgres` | DB creds |
| `spring.jpa.hibernate.ddl-auto` | `update` | auto-manage schema from entities |
| `spring.jpa.open-in-view` | `false` | no lazy loading in views |
| `otterfree.storage.audio-dir` | `${user.home}/otterfree/audio` | audio storage root |
| `otterfree.whisper.base-url` | `http://localhost:9000` | Whisper service |
| `otterfree.recall.base-url` | `https://us-west-2.recall.ai/api/v1` | Recall API |
| `otterfree.recall.api-key` | *(set in file)* | **Recall token — move to env/secret** |
| `otterfree.recall.default-bot-name` | `Heardly Notetaker` | bot display name |
| `otterfree.recall.poll-interval-ms` | `30000` | bot poll cadence |
| `otterfree.openai.base-url` | `https://api.openai.com/v1` | OpenAI endpoint |
| `otterfree.openai.api-key` | `${OPENAI_API_KEY:}` | enables insights + chat when set |
| `otterfree.openai.model` | `gpt-4o-mini` | LLM model |
| `otterfree.base-url` | `http://localhost:8080` | base for share links in summaries |
| `spring.mail.host/port/username/password` | empty / 587 | SMTP (Gmail App Password) — enables Email integration |
| `otterfree.mail.from` | `${MAIL_USERNAME}` | sender address |
| `spring.servlet.multipart.max-file-size` | `500MB` | upload limit |

**Whisper service env** (`whisper-service`): `WHISPER_MODEL` (`base`),
`WHISPER_COMPUTE_TYPE` (`int8`), `WHISPER_DEVICE` (`cpu`), `WHISPER_LANGUAGE`
(auto).

> ⚠️ **Security note:** the committed `application.properties` contains a
> hard-coded Recall API key. This should be rotated and moved to an environment
> variable / secret manager before any non-local use. OpenAI and mail
> credentials are already env-driven.

---

## 9. Security model

- **Authentication:** Spring Security form login; session cookie
  (`JSESSIONID`). Passwords hashed with **BCrypt**.
- **Authorization:** every page and `/api/**` endpoint requires authentication
  except: `/login`, `/signup`, static assets (`/css/**`, `/js/**`, favicons),
  `/actuator/**`, and `/error`.
- **Ownership scoping:** all data access is filtered by the authenticated
  user's `UUID` (`findByIdAndUserId`, `findAllByUserId...`), so users can never
  read or mutate another user's recordings, meetings, chats, or integrations.
- **CSRF:** currently **disabled** (`csrf().disable()`). Acceptable for a
  token-less local/JSON API, but should be re-enabled (with proper token
  handling for the Thymeleaf forms) before public deployment.
- **Data locality:** audio is transcribed by the **local** Whisper service, so
  audio bytes don't leave the host. Transcript text is sent to OpenAI for
  insights/chat (a deliberate trade-off; can be swapped for a local LLM via the
  `LlmClient` interface).

---

## 10. HTTP API reference

All `/api/**` endpoints require an authenticated session and are scoped to the
current user. Errors are returned as `{"error": "..."}` with appropriate status
codes (`400` bad request, `404` not found, `409` conflict).

### Recordings — `/api/recordings`
| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/` | Upload audio (`multipart`, field `file`) → `201` + recording; starts transcription |
| `GET` | `/` | List the user's recordings |
| `GET` | `/{id}` | Get one recording |
| `DELETE` | `/{id}` | Delete recording + transcript/insight/chat + audio file |
| `POST` | `/{id}/retry` | Re-run transcription |
| `GET` | `/{id}/transcript` | Transcript JSON (`404` until ready) |
| `GET` | `/{id}/insights` | Insight JSON (`404` until generated) |
| `POST` | `/{id}/insights/regenerate` | Re-run insight generation |
| `GET` | `/{id}/transcript.txt` | Plain-text transcript |
| `GET` | `/{id}/transcript.docx` | DOCX export |
| `GET` | `/{id}/transcript.pdf` | PDF export |

### Meetings — `/api/meetings`
| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/` | Schedule a Recall bot (`{meetingUrl, botName?}`) |
| `GET` | `/` | List meetings |
| `GET` | `/{id}` | Get one meeting |
| `DELETE` | `/{id}` | Cancel (bot leaves the call) |

### Chat
| Method | Path | Description |
|--------|------|-------------|
| `GET/POST/DELETE` | `/api/recordings/{id}/chat` | Per-recording chat history / ask / clear |
| `GET/POST/DELETE` | `/api/chat` | Global chat history / ask / clear |

### Search
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/search?q=...` | Full-text search across the user's transcripts |

### Pages (Thymeleaf, GET)
`/login`, `/signup`, `/` (dashboard), `/upload`, `/meetings/new`,
`/integrations`, `/chat`, `/recordings/{id}`.

---

## 11. Running locally

**Prerequisites:** Java 17+, Maven (or the bundled `./mvnw`), Docker
(for Whisper), and a running PostgreSQL with a `postgres` database.

1. **Start the Whisper service:**
   ```bash
   cd whisper-service
   docker compose up --build      # serves http://localhost:9000
   ```
   Verify: `curl http://localhost:9000/health`.

2. **Start PostgreSQL** (any local instance matching the datasource config),
   e.g. via Docker:
   ```bash
   docker run -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres
   ```
   Hibernate creates/updates the schema automatically (`ddl-auto=update`).

3. **(Optional) Enable AI features:**
   ```bash
   export OPENAI_API_KEY=sk-...
   ```
   Without it, transcription still works; insights and chat are disabled.

4. **(Optional) Enable Email integration:**
   ```bash
   export MAIL_HOST=smtp.gmail.com MAIL_USERNAME=you@gmail.com MAIL_PASSWORD=<app-password>
   ```

5. **Run the app:**
   ```bash
   ./mvnw spring-boot:run         # serves http://localhost:8080
   ```

6. Open `http://localhost:8080`, sign up, and upload an audio file or schedule a
   meeting bot.

---

## 12. Known gaps & future work

- **Integrations UI not wired** — `IntegrationService` (Slack/Email) and the
  async dispatch are implemented and tested via events, but there is no
  `IntegrationController` and the `/integrations` page shows "Coming soon" with
  disabled buttons. Next step: add a controller + enable the connect/test forms,
  and pass the user's connections into the page model.
- **Hard-coded Recall API key** in `application.properties` — rotate and move to
  a secret/env var.
- **CSRF disabled** — re-enable with token handling before public exposure.
- **Single-instance async model** — the in-process executor (2–4 threads,
  queue 50) won't scale horizontally; a shared queue (e.g. SQS/Rabbit) would be
  needed for multiple app instances.
- **Chat is bounded-context RAG** (no vector store) — fine for moderate
  transcript sizes; very long/many recordings will hit the 60–80k char / 60-
  recording context caps.
- **Coming-soon tiles** (Google Drive, broader Slack OAuth) are placeholders.

---

*Generated as a technical reference for the Heardly (OtterFree) codebase.*
