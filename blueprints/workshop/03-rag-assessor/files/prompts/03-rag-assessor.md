# Step 3 — RAG + Arbetsförmedlingen risk assessor

Extend the chat agent from step 2 so that when a user message contains
an Arbetsförmedlingen URL, the assistant:

1. Extracts the job id from the URL.
2. Fetches the structured job ad from JobTech.
3. Generates a search query from the ad.
4. Calls `search_docs` on the bluetext RAG MCP server (AMLR corpus).
5. Builds a prompt of `[job description] + [retrieved snippets]`.
6. Streams an OpenRouter assessment back, with inline citations like
   `[source: <filename>]`.

The chat UI from step 2 keeps working for normal messages — the URL
detection is additive.

## Files to edit

- `code/services/{{api_service}}/src/main.rs` — extend `POST /chat`
  with the JobTech + RAG branch.
- `code/services/{{web_service}}/app/routes/home.tsx` — render
  citation pills (light styling, hover shows the filename).

You don't need to touch Cargo.toml — `reqwest`, `serde_json`, and the
streaming crates from step 2 cover everything here.

## Background: AMLR and Arbetsförmedlingen

- **Arbetsförmedlingen** is the Swedish Public Employment Service. Job
  postings live on `arbetsformedlingen.se/platsbanken/annonser/<id>`.
  The `<id>` is the only thing you need.
- **AMLR** is the Arbetsmiljölagen / Swedish Work Environment Act
  (Arbetsmiljö-lagen-och-regler). The RAG corpus seeded into the MCP
  server contains AMLR primary texts plus interpretation docs.
- The assessor's job is to surface workplace-safety risks that the
  posted job's description implies (e.g. shift work, lone work,
  exposure, lifting, hazardous environments) and ground each call-out
  in a citation from the corpus. **Source citations are mandatory** —
  participants should ignore any answer with no `[source: ...]`.

## Trigger detection

Parse the user's most recent message. If it matches roughly:

```
https://arbetsformedlingen.se/.../<digits>
```

extract `<digits>` (the last digits-only path segment) as the job id
and enter the RAG branch. Otherwise, fall through to plain chat
(step-2 behaviour).

A naive regex is fine: `r"arbetsformedlingen\.se/[^\s]*?/(\d+)"`.

## Step 3a — JobTech (no auth)

```
GET https://jobsearch.api.jobtechdev.se/ad/{job_id}
```

Returns structured JSON: `headline`, `description.text`, `employer`,
`occupation.label`, `workplace_address`, `employment_type`, `duration`,
etc. Stream a `status` event back to the client ("fetching job ad")
before the network call so the UI shows progress.

## Step 3b — Generate a search query

Use OpenRouter (the same client you already built) with a non-streaming
call. System prompt: "extract 3-7 keywords that would surface relevant
AMLR risk-assessment guidance for this job ad". User content: the ad
text. Return a single search query string.

Cheap models are fine here — Haiku 4.5 or Gemini Flash.

## Step 3c — RAG MCP server

Endpoint (the trailing slash matters — without it the server issues a
307 redirect that drops the POST body):

```
https://rag.bluetext.dev/mcp/
```

Transport: **MCP Streamable HTTP** — JSON-RPC 2.0 over HTTP POST. It
is not REST.

### Required headers on every POST

```
Authorization: Bearer rag_JkrlFRaGITL1X_WpLOefbfY98jGuiweX3vUbce-fxYc
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Session-Id: <id from the initialize response>
```

`Accept` must include `text/event-stream` — without it the server
returns 406. Omit `Mcp-Session-Id` on `initialize`; reuse the returned
id for everything after.

### Handshake

1. POST `initialize` — has an `id` field (it's a request). Read
   `Mcp-Session-Id` from the response headers.
2. POST `notifications/initialized` — no `id` field (it's a
   notification, not a request).
3. POST `tools/call` with `{ "name": "search_docs", "arguments": { "query": "..." } }`.

### Response handling

Look at `Content-Type` on the response:

- `application/json` → one JSON-RPC body to parse.
- `text/event-stream` → SSE frames, each `event: message\ndata: <json>`.
  Parse the frame, then parse the inner `data` as JSON-RPC.

### Result unwrapping (this is the bit people miss)

Every tool result is wrapped:

```json
{ "content": [ { "type": "text", "text": "<json-encoded payload>" } ] }
```

The inner `text` field is itself a JSON string and must be parsed a
second time. After that second parse you get the hit array:

```json
[
  { "text": "...", "filename": "AMLR_chapter_3.md", "score": 0.81 },
  ...
]
```

Keep the top 3-5 hits and pass them into the assessment prompt.

### Other tools (optional, not needed for the happy path)

- `list_documents` — no args.
- `get_document` — `{ "document_id": "..." }`.

## Step 3d — Build the assessment prompt

```
System: You are a workplace-safety risk assessor. Given a Swedish job
ad and excerpts from AMLR (Arbetsmiljölagen och regler), list the
top 3-5 risks. Each risk MUST end with a citation in the form
`[source: <filename>]` taken from the excerpts. If you cannot find a
risk in the excerpts, say so.

User:
JOB AD:
<job text from JobTech>

AMLR EXCERPTS:
[source: AMLR_chapter_3.md]
<text>
[source: AMLR_chapter_7.md]
<text>
...
```

Stream the response via OpenRouter (`stream: true`) the same way you
already do in step 2.

## Frontend updates

In `home.tsx`:

- When the assistant message contains substrings like
  `[source: AMLR_chapter_3.md]`, render them as a small badge / pill
  using shadcn `Badge` rather than raw text. The pill's title attribute
  can show the full filename.
- Optional: show a small "RAG: <hit_count> sources" line above the
  assistant message when the URL branch was taken (the api can emit a
  `rag_context` SSE event before the first token).

## SSE event stream (suggested)

If you want richer UI feedback than plain tokens, emit typed SSE
events instead of just `delta`:

```
status      — "fetching job ad", "querying RAG", "asking model"
job_data    — JSON of the JobTech ad
rag_context — { "query": "...", "hit_count": 5 }
token       — { "delta": "..." }
done        — terminator
error       — { "message": "..." }
```

For a plain URL message the only events are `status` → `token*` →
`done`. The mix of typed events keeps the frontend code clean.

## Smoke test (without UI)

```bash
curl -N -X POST https://api--rs--development--default--main--<user>.dm-k8s.bluetext.dev/chat \
  -H 'content-type: application/json' \
  -d '{"messages":[{"role":"user","content":"https://arbetsformedlingen.se/platsbanken/annonser/12345678"}]}'
```

Replace the id with a real one from
`https://arbetsformedlingen.se/platsbanken/`.

## Definition of done

- Pasting a real Arbetsförmedlingen URL into the chat produces a
  streamed assessment with at least one `[source: ...]` citation.
- Normal chat (no URL) still works as in step 2.
- No errors in the browser console or the api log.

## When you're done

That's the lab. You've built a chat agent, wired it to OpenRouter,
talked to an MCP server, and shipped a citation-grounded RAG flow on
top of mirrord+cargo-watch. Time to compare notes with the room.
