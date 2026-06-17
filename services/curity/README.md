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
   bluetext-render init container          api-config Job
   renders config/files/curity/curity/    (post-apply, this template's
   → tmpfs, substitutes secrets::           api-config/curity/base/)
   (this template's deployment.yaml)
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
| License token (in JSON wrapper) | `/opt/idsvr/etc/init/license/default` | `config-files/curity/license/default` holds `$bt{{ secrets::curity-license-wrapped }}`; the `bluetext-render` init container substitutes the Vault-backed value (projected as `bt-secret--curity-license-wrapped`) into the rendered tmpfs in-pod |
| XML init configs | `/opt/idsvr/etc/init/*.xml` | `config-files/curity/*.xml`, rendered through file-config; deploy tokens are `$bt{{ deploy::namespace }}` / `deploy::service-url.*`, resolved at render time (no `__NAMESPACE__` sed) |
| Admin credentials for RESTCONF | `/etc/bluetext/peers/self/{username,password}` on the api-config Job pod | `config/curity/curity.yaml::secrets.admin-credentials` projected by `api_config_peers.rs` |
| OAuth profiles / clients / scopes / roles | RESTCONF API once licensed | `api-config/curity/base/state.yaml` + the paired handler crate at `code/api-config/curity/base/` |

## Operator prerequisites before `b deploy`

The license has to land in Vault as the JSON-wrapped form Curity reads
at startup (`{"License":"<jwt>"}`). The wrapper script next to this
README normalizes whatever shape `$CURITY_LICENSE_KEY` happens to
carry — full portal envelope JSON, just the raw JWT, or (failing
loud) the payload-only blob the portal sometimes leaves on the
clipboard:

```bash
# Recommended — straight from the file the portal hands you:
export CURITY_LICENSE_KEY=$(jq -r .License /path/to/cillers.com_Trial_*.json)

# Pipe through the normalizer and write to Vault:
CURITY_LICENSE_WRAPPED=$(printf '%s' "$CURITY_LICENSE_KEY" \
    | "$BLUETEXT_TEMPLATES_DIR/services/curity/scripts/wrap-license.sh") \
    b secret set curity-license-wrapped --from-env CURITY_LICENSE_WRAPPED

# Admin credentials for the RESTCONF surface:
b secret set curity-admin-username --from-env CURITY_ADMIN_USERNAME  # default: "admin"
b secret set curity-admin-password --from-env CURITY_ADMIN_PASSWORD  # operator-set
```

Every `auth/*-curity-*` context bundles these steps already; the form
above is only relevant for ad-hoc / scripted setups outside a context.

### Why the wrap exists

Curity reads the license from the file `/opt/idsvr/etc/init/license/
default`, and the format must be the JSON envelope `{"License":"<jwt>"}`
— not the raw JWT. The file-config file `config-files/curity/license/
default` carries `$bt{{ secrets::curity-license-wrapped }}`; the deploy
pipeline emits the per-value-id `bt-secret--curity-license-wrapped`
ExternalSecret from the Vault-stored wrapped bytes and projects it into
the `bluetext-render` init container, which substitutes it into the
rendered tmpfs file Curity reads.

### Why payload-only inputs fail loud

The portal sometimes leaves only the JWT's *middle* base64 segment
on the clipboard (no header, no signature, zero `.` separators).
It decodes cleanly to JSON with `iss`/`sub` claims, so it *looks*
right — but Curity rejects it at boot with the misleading
`LicenseKeyValidationCallback - License was the wrong issuer or had
not subject` and the pod CrashLoopBackOffs. The wrapper script
catches this at `b secret set` time and emits the actionable fix
(re-extract from the portal JSON) instead of letting the error
surface 90 seconds into a deploy. Verified empirically: with the
complete 2-dot JWT, Curity boots licensed against
`curity.azurecr.io/curity/idsvr:11.2.0` (license schema 4.3,
runtime 11.2.0).

## What NOT to assume

- **Don't assume RESTCONF can install the license.** It can't. See §
  Why the split above.
- **Don't assume the file-based channel is legacy.** It's the only
  bootstrap surface Curity offers. The api-config Job complements it,
  not replaces it.
- **Don't put license-key in `admin-credentials.keys`.** The license
  flows through the file-config `secrets::curity-license-wrapped`
  placeholder (substituted by `bluetext-render` at pod startup), not
  through the api-config peer projection.
- **Don't add new dynamic config to XML init files.** That's a
  candidate for the api-config bundle's RESTCONF ensure phase. XML
  init is reserved for RESTCONF-write-protected configs and the
  license bootstrap.
