# System: Job training risk assessment agent (workshop)

You are operating on the **job-risk-agent workshop** scaffold. The end-user
task lives in `prompts.md` at this system's root — read it first.

General lab context (network topology, b CLI, mirrord workflow, the
`mirrord-clean` helper, the Axum SSE `HeaderMap` quirk, and why
`outgoing: false` in `.mirrord.json` matters) is in `../CLAUDE.md`, which
pi loads automatically. This file only covers workshop-specific contracts.

## Local development reminders

- API service path: `code/services/api/`. The Cargo.toml is already
  populated with streaming-ready deps (`reqwest` with `rustls-tls`,
  `tokio-stream`, `futures-util`, `tower-http` cors, `anyhow`).
- Web app path: `code/services/web-app/`. Edit `app/routes/home.tsx`.
- Hot reload: `cargo watch -x run` via mirrord (see lab CLAUDE.md for the
  exact invocation). Vite HMR works out of the box for the frontend.

## RAG MCP — wire-level reference

Endpoint: `https://rag.bluetext.dev/mcp/` (the trailing slash is required;
without it the server issues a 307 and some clients drop the body).

Transport is **MCP Streamable HTTP** — JSON-RPC 2.0 over HTTP POST. It is
not REST. Every request needs:

```
Authorization: Bearer rag_JkrlFRaGITL1X_WpLOefbfY98jGuiweX3vUbce-fxYc
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: <id from initialize response>
```

The `Accept` header must include `text/event-stream` or the server
returns 406. Do **not** send `Mcp-Session-Id` on `initialize` — capture
it from the response, then reuse for every subsequent call.

Handshake order:

1. `initialize` (request — has an `id` field). Read `Mcp-Session-Id` from
   the response headers.
2. `notifications/initialized` (notification — must not have an `id`).
3. `tools/call` with body `{ "name": "<tool>", "arguments": {...} }`.

Inspect `Content-Type` on responses:

- `application/json` → one JSON-RPC body to parse.
- `text/event-stream` → SSE frames, each `event: message\ndata: <json>`.

Tools available:

- `search_docs` — `{ "query": "..." }` → hits with `text`, `filename`, `score`.
- `list_documents` — no args.
- `get_document` — `{ "document_id": "..." }`.

**Result unwrapping (important):** Every tool result comes wrapped as

```json
{ "content": [ { "type": "text", "text": "<json-encoded payload>" } ] }
```

The inner `text` is itself a JSON string and must be parsed a second time.

## OpenRouter

```
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer <OPENROUTER_API_KEY>
Content-Type: application/json
HTTP-Referer: https://bluetext.dev
```

Set `"stream": true` to enable streaming. The wire format is OpenAI's SSE:
each frame is `data: {"choices":[{"delta":{"content":"..."}}]}`, terminated
by `data: [DONE]`.

The API key is exported as `OPENROUTER_API_KEY` in the runner shell.
Hard-coding it directly in `src/main.rs` is fine for this workshop.

## JobTech (no auth)

```
GET https://jobsearch.api.jobtechdev.se/ad/{job_id}
```

`{job_id}` is the last digits-only segment of the Arbetsförmedlingen URL
the user pastes.

## SSE contract the frontend expects

Event types emitted by `POST /assess`:

```
status, job_data, rag_context, token, done, error
```

The frontend (`home.tsx`) reads these and updates the metadata card,
RAG-context pill, and streaming assessment area incrementally.
