# Step 2 — OpenRouter chat agent

Add a chat UI to the web-app and a streaming `POST /chat` endpoint to
the api. The api calls OpenRouter and streams tokens back to the
browser as Server-Sent Events. State stays on the client (memory +
`localStorage`) — no database, no auth.

> Environment quirks (mirrord recovery, `outgoing: false` rationale,
> the Axum SSE `HeaderMap` quirk) are in `/home/user/project/CLAUDE.md`.
> Read it once before debugging anything network-related.

## Files to edit

- `code/services/{{web_service}}/app/routes/home.tsx` — chat UI.
- `code/services/{{api_service}}/src/main.rs` — `POST /chat` endpoint.

The api's Cargo.toml has just been overwritten with the streaming-ready
deps you need (`reqwest` with `rustls-tls`, `tokio-stream`,
`futures-util`, `tower-http` cors, `anyhow`). No further dependency
changes required.

## Backend — `POST /chat`

```
POST /chat
Content-Type: application/json
Body: { "messages": [{ "role": "user" | "assistant", "content": "..." }] }

Response: text/event-stream
  data: {"delta":"hello"}
  data: {"delta":" world"}
  ...
  data: [DONE]
```

Pipeline:

1. Deserialize messages from the request body.
2. POST to OpenRouter (see wire reference below) with `stream: true`.
3. Parse OpenRouter's SSE response (one `data: {...}` frame per chunk).
4. Forward each chunk to the client as your own SSE frame. Terminate
   with `data: [DONE]`.

Keep the existing `GET /health` and `GET /` endpoints; just add `/chat`
to the router.

### CORS

Use a credential-safe CORS layer — mirror the request dynamically instead of
`Any`. The CORS spec forbids combining a wildcard `*` (for origin, methods, or
headers) with credentials, so the moment any browser client sends
`credentials: "include"` (e.g. to hit the api cross-origin behind the lab
gateway with its auth cookie), an `Any`-based layer is rejected. Mirroring is
just as permissive in dev but stays spec-compliant:

```rust
use tower_http::cors::{AllowHeaders, AllowMethods, AllowOrigin, CorsLayer};

let cors = CorsLayer::new()
    .allow_origin(AllowOrigin::mirror_request())
    .allow_methods(AllowMethods::mirror_request())
    .allow_headers(AllowHeaders::mirror_request())
    .allow_credentials(true);
```

Attach with `.layer(cors)` on the router.

## OpenRouter wire reference

```
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer <OPENROUTER_API_KEY>
Content-Type: application/json
HTTP-Referer: https://bluetext.dev
```

Body:

```json
{
  "model": "anthropic/claude-sonnet-4-5",
  "messages": [...],
  "stream": true
}
```

Streaming response (one frame per token-ish chunk):

```
data: {"choices":[{"delta":{"content":"hello"}}]}
data: {"choices":[{"delta":{"content":" world"}}]}
...
data: [DONE]
```

Pick the model you like — Sonnet 4.5 is a good default, Haiku 4.5 is
cheaper, Gemini Flash works too. See https://openrouter.ai/models.

### Where the key comes from

The runner pod has `OPENROUTER_API_KEY` injected as an env var by the
lab orchestrator. Read it from Rust with:

```rust
let key = std::env::var("OPENROUTER_API_KEY")
    .expect("OPENROUTER_API_KEY missing");
```

Don't hard-code keys in source.

## Frontend — chat UI

In `home.tsx`:

- Message list (scrollable, latest at the bottom).
- Text input + Send button.
- Persist messages in `localStorage` under a key like
  `"workshop.chat.messages"`. Load on mount, save on every update.

On Send:

1. Append the user message to state.
2. Open an SSE stream from `/api/chat` (Vite proxies `/api/*` to the
   api in-cluster).
3. Append an assistant message that grows token-by-token as `delta`
   frames arrive. Render a blinking cursor at the end while streaming.
4. On `data: [DONE]`, close the stream.

`fetch` doesn't expose SSE directly — use the `EventSource` browser
API, OR `fetch` with `ReadableStream` and parse `data: ...` lines
yourself. Either is fine.

shadcn/ui ships `Button`, `Input`, `Card`, `ScrollArea` — use them so
you spend zero time on styling.

## Dev loop (Rust side)

If `cargo watch -x run` isn't already running under mirrord, start it:

```bash
cd code/services/{{api_service}}
nohup mirrord exec \
  --target deployment/{{api_service}} \
  --target-namespace rs--development--default--main \
  --config-file .mirrord.json \
  -- cargo watch -x run \
  > /tmp/api-dev.log 2>&1 &

tail -f /tmp/api-dev.log
```

Wait for `listening on 0.0.0.0:3030`.

If you hit `detected dirty iptables`, run
`mirrord-clean app={{api_service}} rs--development--default--main` and
try again. See `/home/user/project/CLAUDE.md` for the full runbook.

## Browser URLs

- Web app: `https://web-app--rs--development--default--main--<user>.dm-k8s.bluetext.dev/`
- API direct: `https://api--rs--development--default--main--<user>.dm-k8s.bluetext.dev/health`

Use the direct api URL to smoke-test `/chat` from `curl -N` before
wiring up the frontend:

```bash
curl -N -X POST https://api--rs--development--default--main--<user>.dm-k8s.bluetext.dev/chat \
  -H 'content-type: application/json' \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
```

## Definition of done

- Typing a message and hitting Send streams a response from OpenRouter
  token-by-token in the browser.
- Refresh the page → chat history is still there.
- `cargo check` clean, browser console clean.

## When you're done

Tell me you're ready for step 3 — we'll extend the chat agent with RAG
retrieval and an Arbetsförmedlingen risk-assessment flow.
