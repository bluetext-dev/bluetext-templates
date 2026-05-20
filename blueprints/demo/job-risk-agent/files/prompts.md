# Workshop: Job training risk assessment agent

Build a Rust API + React web app that takes an Arbetsförmedlingen job
posting URL and streams an LLM-generated risk assessment back to the
browser. The assessor:

1. Fetches structured job data from the JobTech public API.
2. Retrieves relevant legal context via the bluetext RAG MCP server.
3. Asks OpenRouter to produce a streaming assessment.
4. Forwards tokens to the browser over Server-Sent Events.

> Environment quirks (mirrord, `/lib64` linker, Axum SSE headers, outgoing
> traffic from mirrord'd pods) are covered in the **lab-level `CLAUDE.md`**
> two directories up. Read it before fighting the environment.

---

## Topology

```
[runner container]  ← you are here
└─ k3d cluster (inside DinD sidecar)
   └─ namespace: rs--development--default--main
      ├─ api pod   ← mirrord steals traffic to local cargo process
      └─ web-app pod (oven/bun, `bun run dev --host`, Vite HMR)
```

External URLs (browser login required, see system README):

- Web app: `https://web-app--rs--development--default--main--<user>.dm-k8s.bluetext.dev/`
- API:     `https://api--rs--development--default--main--<user>.dm-k8s.bluetext.dev/`

Request flow:

```
Browser → Vite dev server → /api/* → http://api.rs--development--default--main.svc.cluster.local
       → mirrord traffic steal → local cargo process
```

Vite strips the `/api` prefix before forwarding.

---

## Frontend (`code/services/web-app/app/routes/home.tsx`)

UI flow: user pastes a job URL → clicks **Assess** → frontend opens an SSE
stream → UI updates incrementally.

Components:

- Job URL input
- **Assess** button (POST `/api/assess`)
- Job metadata card (title, employer, occupation, location, employment
  type, duration) — render as soon as `job_data` arrives
- RAG context pill showing the query and hit count
- Streaming assessment area — render tokens with a live cursor, minimal
  markdown only (headings, bold, italic, bullet lists; no external lib)

SSE event types emitted by the API:

```
status, job_data, rag_context, token, done, error
```

---

## API (`code/services/api/src/main.rs`)

Single endpoint: `POST /assess` returning `text/event-stream`. Pipeline:

1. Fetch the job JSON from JobTech.
2. Open an MCP session, call `search_docs` for legal snippets.
3. Call OpenRouter with the job description + retrieved snippets.
4. Forward incremental tokens to the frontend as `token` SSE events.

Also keep `GET /health → { "status": "ok" }` and a permissive `CorsLayer`
(`allow_origin(Any).allow_methods(Any).allow_headers(Any)`).

---

## JobTech (no auth)

```
GET https://jobsearch.api.jobtechdev.se/ad/{job_id}
```

Extract `{job_id}` as the last path segment containing only digits from
the Arbetsförmedlingen URL the user pastes.

---

## RAG MCP server

Endpoint (trailing slash required — without it the server 307-redirects
and some clients lose the POST body):

```
https://rag.bluetext.dev/mcp/
```

Transport: MCP Streamable HTTP (JSON-RPC 2.0 over HTTP POST — *not* REST).

Auth: `Authorization: Bearer rag_JkrlFRaGITL1X_WpLOefbfY98jGuiweX3vUbce-fxYc`

Every POST must carry:

```
Authorization: Bearer <token>
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: <id>
```

`Accept` must include `text/event-stream` or the server returns 406.
Omit `Mcp-Session-Id` on `initialize`; reuse the returned session ID for
everything that follows.

Handshake:

1. POST `initialize` (include an `id` field). Capture `Mcp-Session-Id`
   from the response headers.
2. POST notification `notifications/initialized` (no `id` field — it's
   a notification).
3. POST `tools/call` with `{"name": "<tool>", "arguments": {...}}`.

Inspect `Content-Type` on responses:

- `application/json` → single JSON-RPC body.
- `text/event-stream` → SSE frames, each `event: message\ndata: <json-rpc>`.

Tools:

| Tool             | Arguments                  | Returns                           |
|------------------|----------------------------|-----------------------------------|
| `search_docs`    | `{ "query": "..." }`       | hits: `{ text, filename, score }` |
| `list_documents` | none                       | document list                     |
| `get_document`   | `{ "document_id": "..." }` | document body                     |

Tool results are wrapped:

```json
{ "content": [ { "type": "text", "text": "<json string>" } ] }
```

The inner `text` field is JSON-encoded and must be parsed *again*.

---

## OpenRouter

```
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer <key>
Content-Type: application/json
HTTP-Referer: https://bluetext.dev
```

Streaming: set `"stream": true`. Parse standard OpenAI SSE format:

```
data: {"choices":[{"delta":{"content":"..."}}]}
...
data: [DONE]
```

---

## Credentials

Hard-code everything in source — no env-var lookups required.

- **Browser login** (for opening the web-app/API ingress in a browser):
  see `/home/user/project/README.md`.
- **OpenRouter API key**: `echo $OPENROUTER_API_KEY` in the runner.
- **RAG token**: embedded above.
- **Bluetext URL pattern**: documented in the system README.
