# Kong AI MCP Proxy

Expose any HTTP API running in Kong as an MCP server, so that MCP-capable clients
(Claude Desktop, Cursor, opencode, etc.) can discover and invoke the API as
named tools. Uses the [Kong AI MCP Proxy plugin](https://developer.konghq.com/plugins/ai-mcp-proxy/)
(Enterprise, Gateway 3.12+).

## When to use this

You have a service with HTTP endpoints and you want an LLM agent to call them
via MCP instead of writing a bespoke tool-use integration. The plugin converts
MCP JSON-RPC tool calls into regular HTTP requests to routes you configure in
Kong, which means the same plugin stack you already use for your API (auth,
rate limiting, observability) applies uniformly to MCP traffic.

## Prerequisites

- Kong **Enterprise** (`kong-enterprise` service) running at 3.12+
  — the `ai-mcp-proxy` plugin is in the `ai_gateway_enterprise` tier and is
  absent from OSS Kong.
- A backend service to expose. The examples below assume it's called `api`.
- A valid Kong Enterprise license. The `kong-enterprise` template in this repo
  ships one via the `kong-enterprise-license` secret.

## Quick start

```sh
b bp run gateway/kong-mcp-proxy --var target=api
```

This writes `config/kong-enterprise/kong.yaml`, registers the gateway relation­
ship in `config/bluetext.yaml`, and reloads Kong. Verify:

```sh
curl -sS -X POST http://kong-enterprise.rs--development--default--main.bluetext.localhost/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

You should get back `event: message` + JSON with `serverInfo.name: "Kong"` and
a `Mcp-Session-Id` response header. `tools/list` using that session id returns
the `hello` tool; `tools/call` with `{"name":"hello","arguments":{}}` invokes
`GET /hello` on the api service and returns its JSON.

## Architecture (why the config looks the way it does)

```
MCP client ──POST /mcp──▶ Kong route "mcp-conversion"
                             │  attached plugin: ai-mcp-proxy
                             │  mode: conversion-listener
                             │
                             ▼ (for each tool call)
                          Kong routes (/hello, /health, …)
                             │
                             ▼
                          api service → api pod
```

The non-obvious bit: **when a tool call arrives, the plugin re-enters Kong via
the service upstream** (`http://localhost:8000`, i.e. Kong's own proxy port). It
does NOT call the upstream API directly. That means:

1. The `mcp-gateway` Kong Service must point at Kong itself
   (`url: http://localhost:8000`). That's the plugin's contract.
2. Every tool's `path` must resolve to a real Kong route on the backing API
   service. If no route matches, the call falls through to whatever catch-all
   you have configured (usually a 404).

The first debugging session on this got stuck here: with the plugin on the `api`
service directly and only a `/mcp` route, tool calls returned the catch-all's
body because `/hello` wasn't wired as a Kong route.

## Config walkthrough

The generated `kong.yaml` has three Kong Services:

### 1. `{{target}}` — the upstream API

```yaml
- name: api
  url: http://api                 # short name; resolves inside Kong's namespace
  routes:
    - name: api-hello
      paths: [/hello]
      strip_path: false
    - name: api-health
      paths: [/health]
      strip_path: false
```

One route per HTTP path the plugin needs to reach. `strip_path: false` keeps
the path intact so axum (or whatever the API uses) sees `/hello`, not `/`.

### 2. `mcp-gateway` — the MCP listener

```yaml
- name: mcp-gateway
  url: http://localhost:8000      # Kong calling itself
  routes:
    - name: mcp-conversion
      paths: [/mcp]
      plugins:
        - name: ai-mcp-proxy
          config:
            mode: conversion-listener
            tools:
              - description: "Returns a greeting"
                method: GET
                path: /hello
                annotations:
                  title: hello        # becomes the MCP tool name
                  read_only_hint: true
```

Important bits of the plugin schema (verified against
`GET /schemas/plugins/ai-mcp-proxy` on Kong 3.12.0.5):

| Field | Notes |
|---|---|
| `mode` | `conversion-listener` = Kong accepts MCP and calls HTTP upstream. Required. |
| `tools[]` | Declare tools manually. The plugin does NOT auto-generate from the service's OpenAPI unless you provide one (`parameters`, `request_body`). |
| `tools[].description` | **Required.** Shown to the LLM. |
| `tools[].method` / `tools[].path` | Upstream call shape. |
| `tools[].annotations.title` | Becomes the tool name. There's no `name` field on tools — the name is derived. |
| `tools[].parameters` | OpenAPI 3.0 params (query / path). Tells the LLM what arguments to pass. |
| `tools[].request_body` | OpenAPI 3.0 request body spec for POST/PUT/PATCH tools. |
| `tools[].host` / `tools[].scheme` | Override the upstream URL (default: service's url). |
| `tools[].acl` | Per-tool allow/deny lists keyed on Kong Consumers/Groups. |

Fields that the docs mention but that the live schema rejects (as of 3.12):
`name`, `responses`, `operation_id`. Don't use them.

The plugin must be scoped to a **Route**, not a Service. If it's on a
service-level plugin block Kong logs `plugin … is a MCP tool, but is not
applied to a route. This is not supported and will be skipped.` and the tool
list comes back empty.

### 3. `catch-all` — 404 for unmatched paths

Standard catch-all, returns JSON 404. This is what the plugin hits if your
tool's `path` doesn't match a route on the `{{target}}` service.

## Extending with more tools

To expose another endpoint, add a Kong route for it AND a tool entry for the
plugin:

```yaml
# in the api service:
routes:
  - name: api-echo
    paths: [/echo]
    strip_path: false
    methods: [POST]

# in ai-mcp-proxy config.tools:
- description: "Echoes back the posted JSON payload"
  method: POST
  path: /echo
  request_body:
    content:
      application/json:
        schema:
          type: object
          properties:
            message:
              type: string
          required: [message]
  annotations:
    title: echo
```

Reload Kong (`b service restart kong-enterprise` or exec `kong reload`).
`tools/list` should now show `echo` with an `inputSchema` derived from the
`request_body` OpenAPI shape.

## Opencode integration

Opencode reads MCP servers from `~/.config/opencode/opencode.json`. The
`bluetext-runner` image ships with a default entry pointing at the Kong MCP
URL for `rs--development--default--main`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": { "openrouter": {} },
  "model": "openrouter/google/gemini-3.1-pro-preview",
  "mcp": {
    "kong-api": {
      "type": "remote",
      "url": "http://kong-enterprise.rs--development--default--main.bluetext.localhost/mcp",
      "enabled": true
    }
  }
}
```

If you're using a non-default run-spec / namespace / gateway name, update the
hostname accordingly (pattern: `{service-id}.{namespace}.bluetext.localhost`
resolves to `::1` via glibc's built-in `*.localhost` handling, and k3d maps
port 80 to the runner).

Once opencode picks up the config, the tools appear prefixed with the server
name — e.g. `kong-api.hello`. Ask the agent: "call kong-api.hello" or just
"say hello via the api" and it'll invoke the tool.

## Troubleshooting

**`tools/list` returns empty array** — Plugin is attached to a Service
instead of a Route. Kong logs
`plugin … is a MCP tool, but is not applied to a route`. Move the plugin
into `routes[].plugins[]`.

**`tools/call` returns `{"message":"No routes configured"}`** — The plugin
re-entered Kong but no route matched the tool's `path`. Add a Kong route on
the target service whose path matches. Verify with a direct curl:
`curl -H "Host: kong-enterprise.<ns>.bluetext.localhost" http://localhost/<path>`.

**`tools/call` returns "HTTP call failed with status 404"** — Same root cause
as above, or the tool's `path` is wrong. Enable `logging.log_payloads: true`
on the plugin and check `kubectl logs deploy/kong-enterprise` for the outgoing
request URL.

**`parse successful` but the new config doesn't take effect** — `kong reload`
reloads the worker config, but if the YAML failed a deeper validation, Kong
keeps running the old config silently. Run `kong config parse <file>` inside
the pod to validate explicitly.

**Plugin validation error `unknown field: name` / `unknown field: responses`** —
The plugin docs are slightly ahead of the 3.12 schema. Remove those fields.
Use `annotations.title` for the tool name.

**`SID=''` after initialize** — The server didn't return an `Mcp-Session-Id`
header. Either the route didn't match and you got 404, or `Accept` header
doesn't include `text/event-stream`. MCP uses SSE responses over a single
POST endpoint.

**License warnings in Kong logs** — The shipped Kong Enterprise license in
the template expires. Update the `kong-enterprise-license` secret when a
new license is issued.

## See also

- `gateway/kong-proxy` — the non-MCP sibling, for plain HTTP routing
- Kong docs: https://developer.konghq.com/plugins/ai-mcp-proxy/
- MCP spec: https://modelcontextprotocol.io/
