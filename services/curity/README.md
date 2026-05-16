# Curity Identity Server — service template

Curity is the OAuth 2.0 + OIDC + RBAC substrate. The template ships a
hybrid bootstrap architecture because **Curity's runtime imposes
constraints the api-config pattern alone cannot satisfy**. Read this
before editing `service.yaml`, `curity.yaml`, or the manifests — the
shape is load-bearing.

## Hybrid bootstrap architecture

Two configuration surfaces flow into a deployed Curity, in this order:

```
   ┌──────────────────────────────┐    ┌──────────────────────────────┐
   │  1. File-based init dir      │    │  2. api-config Job           │
   │  /opt/idsvr/etc/init/        │ →  │  RESTCONF                    │
   │                              │    │  /admin/api/restconf/data    │
   │  • license/default (JSON)    │    │  • oauth-profiles            │
   │  • base-config.xml           │    │  • clients                   │
   │  • service-role config       │    │  • scopes                    │
   │  • XML-only configs          │    │  • roles                     │
   └──────────────────────────────┘    └──────────────────────────────┘
            ↑                                       ↑
   curity--license Secret +                api-config Job
   config-templater init container         (post-apply, this template's
   (this template's deployment.yaml)        api-config/curity/base/)
```

## Why the split

Three Curity behaviours force the file-based half:

1. **License install is not API-installable.** Every `/admin/api/
   restconf/data/*` endpoint returns `503 FeatureViolationException`
   when the runtime is unlicensed — including read-only GETs to the
   root. Curity's only license-input surface is the file
   `/opt/idsvr/etc/init/license/default`, which the startup scanner
   reads on first boot.
2. **RESTCONF is license-gated.** Even the basic admin REST API is
   unavailable pre-license. There is no "bootstrap" endpoint that can
   accept the license itself — the bootstrap is file-only.
3. **XML-only configs.** Some configuration surfaces — most notably
   `services/service-role` — are write-protected via RESTCONF and
   must be supplied via the XML init files in `/opt/idsvr/etc/init/`.
   These ship in this template's `config-files/*.xml`.

The post-license RESTCONF half exists because the dynamic surfaces
Curity exposes there (OAuth profiles, clients, scopes, roles) compose
freely across blueprints — they're the natural fit for declared-state
ensure flows owned by an api-config bundle.

## Where each piece lives in this template

| Surface | Lands at | Authoring location |
|---|---|---|
| License token (in JSON wrapper) | `/opt/idsvr/etc/init/license/default` | `config/curity/curity.yaml::secrets.license` → `curity--license` Secret → `config-templater` init container copies in |
| XML init configs | `/opt/idsvr/etc/init/*.xml` | `config-files/*.xml` (hostPath mount + `__NAMESPACE__` sed) |
| Admin credentials for RESTCONF | `/etc/bluetext/peers/self/{username,password}` on the api-config Job pod | `config/curity/curity.yaml::secrets.admin-credentials` projected by `api_config_peers.rs` |
| OAuth profiles / clients / scopes / roles | RESTCONF API once licensed | `api-config/curity/base/state.yaml` + the paired handler crate at `code/api-config/curity/base/` |

## Operator prerequisites before `b deploy`

```bash
b secret set fixed/curity-license-key   --from-env CURITY_LICENSE_KEY
b secret set fixed/curity-admin-username --from-env CURITY_ADMIN_USERNAME  # defaults: "admin"
b secret set fixed/curity-admin-password --from-env CURITY_ADMIN_PASSWORD  # operator-set
```

The first command is mandatory — Curity will start in license-gated
mode (admin port up, runtime port down) without it.

### `CURITY_LICENSE_KEY` must be the complete signed JWT

Curity downloads from the developer portal arrive as a JSON envelope:

```json
{"Company":"...","Tier":"Trial","Issued":"YYYY-MM-DD",
 "License":"<base64-header>.<base64-payload>.<base64-signature>"}
```

The value of the `License` field is the actual JWT — three base64
segments joined by **two `.` separators**. `CURITY_LICENSE_KEY` must
contain exactly that JWT string. Sanity-check before populating the
secret:

```bash
echo -n "$CURITY_LICENSE_KEY" | tr -cd '.' | wc -c   # must print 2
```

If the count is 0, the env var contains only the JWT's *payload*
section (the middle base64 between the dots — it'll base64-decode to
valid-looking JSON with `iss` and `sub` claims). Curity will reject
this with the misleading `LicenseKeyValidationCallback - License was
the wrong issuer or had not subject` error and CrashLoopBackOff;
without the header + signature it can't validate the token, so its
structural-validation error fires before it can read iss/sub.

Re-extract the full `License` field value from the portal JSON and
`b secret set fixed/curity-license-key --from-env CURITY_LICENSE_KEY`
again. Verified empirically: with the complete 2-dot JWT, Curity
boots licensed against `curity.azurecr.io/curity/idsvr:latest`
(license schema version 4.3, runtime version current).

## What NOT to assume

- **Don't assume RESTCONF can install the license.** It can't. See §
  Why the split above.
- **Don't assume the file-based channel is legacy.** It's the only
  bootstrap surface Curity offers. The api-config Job complements it,
  not replaces it.
- **Don't put license-key in `admin-credentials.keys`.** The license
  flows through `secrets.license` + the deployment's volume mount,
  not through the api-config peer projection.
- **Don't add new dynamic config to XML init files.** That's a
  candidate for the api-config bundle's RESTCONF ensure phase. XML
  init is reserved for RESTCONF-write-protected configs and the
  license bootstrap.
