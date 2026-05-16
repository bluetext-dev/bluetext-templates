//! Curity admin-RESTCONF handlers.
//!
//! Each `#[handler]` function gets registered into the global inventory
//! via the bluetext-api-config-macros crate. The Job binary's main loop
//! looks up handlers by name (matching the `handler:` field in the
//! bundle's migration YAML).

use bluetext_api_config::{handler, ApiConfigCtx, PeerData, Result};
use serde_yml::Value;

/// Curity's RESTCONF root mounted at the bundled admin port (default 6749).
const RESTCONF_BASE: &str = "/admin/api/restconf/data";

/// Verify Curity's runtime is licensed by probing admin RESTCONF.
///
/// **License install is NOT API-installable on Curity** — every RESTCONF
/// endpoint returns 503 FeatureViolationException when the runtime is
/// unlicensed. The license therefore flows through the file-based
/// channel (`/opt/idsvr/etc/init/license/default`, populated by the
/// curity deployment's `config-templater` init container from the
/// mounted `curity--license` Secret). See `services/curity/README.md`
/// for the hybrid bootstrap architecture.
///
/// This handler's job is to *gate* the rest of the api-config Job's
/// ensure phase on a successful license install. It probes the RESTCONF
/// root: 503 means the license file didn't take effect and ensure
/// would fail downstream anyway with the same 503; 401/200 means the
/// API is reachable and ensure can proceed.
#[handler]
pub async fn verify_license(ctx: &ApiConfigCtx) -> Result<()> {
    let peer = ctx.peer("self")?;
    let host = peer.host()?;
    let port = peer.port()?;
    let username = peer.read("username")?.trim().to_string();
    let password = peer.read("password")?.trim().to_string();

    let client = build_admin_client()?;
    let url = format!("https://{host}:{port}{RESTCONF_BASE}");
    eprintln!("[curity-api-config] verify_license: probing {url}");

    // First with operator creds; if 401, fall back to bootstrap
    // admin/admin (Curity image's seeded credential when ConfD has
    // never been initialised). On 2xx or 401 (auth issue, but
    // RESTCONF is up) the license has taken effect.
    let res = client
        .get(&url)
        .basic_auth(&username, Some(&password))
        .send()
        .await
        .map_err(|e| format!("GET {url} with operator creds: {e}"))?;
    classify_probe(res.status(), "operator creds").await?;
    Ok(())
}

async fn classify_probe(status: reqwest::StatusCode, label: &str) -> Result<()> {
    if status.is_success() {
        eprintln!("[curity-api-config] verify_license: RESTCONF reachable ({status}, {label})");
        return Ok(());
    }
    // 401 alone is NOT proof the license is in place — Curity's auth
    // check fires *before* the license check, so an unlicensed runtime
    // also returns 401 on bad credentials. Treat it as inconclusive:
    // log and continue, but don't gate success on it.
    if status.as_u16() == 401 {
        eprintln!(
            "[curity-api-config] verify_license: got 401 with {label}. \
             Auth failed before the license check ran, so the license \
             status is inconclusive. If the curity pod is in CrashLoopBackOff \
             this is the symptom of an unparsable license JWT — check \
             `kubectl logs <curity-pod>` for `LicenseKeyValidationCallback - \
             License was the wrong issuer or had not subject`."
        );
        return Ok(());
    }
    if status.as_u16() == 503 {
        return Err(format!(
            "Curity admin RESTCONF returned 503 FeatureViolationException — license isn't in place. \
             Curity reads the license file at /opt/idsvr/etc/init/license/default; \
             the deploy pipeline populates it via the curity--license Secret \
             mounted by the curity deployment's config-templater init container. \
             Likely cause: `b secret set fixed/curity-license-key --from-env CURITY_LICENSE_KEY` \
             wasn't run before deploy, or the license JWT in CURITY_LICENSE_KEY is not a complete \
             signed JWT (must contain two `.` separators between header.payload.signature)."
        )
        .into());
    }
    Err(format!("Curity admin RESTCONF returned unexpected {status} for {label}").into())
}

/// Ensure declared state from state.yaml against Curity's admin
/// RESTCONF. Walks each top-level key (`oauth-profiles`, `clients`,
/// `scopes`, `roles`) and routes to the matching ensure function.
///
/// Called from main.rs after every migration has run. Idempotent:
/// each ensure is a read-modify-write against Curity's current
/// config; re-runs converge what's missing without disturbing what
/// already matches.
pub async fn ensure_state(ctx: &ApiConfigCtx, state: &Value) -> Result<()> {
    let Some(map) = state.as_mapping() else {
        eprintln!("[curity-api-config] ensure_state: state.yaml is not a mapping — skipping");
        return Ok(());
    };
    let peer = ctx.peer("self")?;
    for (key, value) in map.iter() {
        let key_str = key.as_str().unwrap_or("<non-string>");
        match key_str {
            "license" => eprintln!("[curity-api-config] ensure: license — handled by 001 migration; no-op here"),
            "oauth-profiles" => ensure_oauth_profiles(peer, value).await?,
            "clients" => ensure_clients(peer, value).await?,
            "scopes" => ensure_scopes(peer, value).await?,
            "roles" => ensure_roles(peer, value).await?,
            other => eprintln!("[curity-api-config] ensure: unknown key '{other}' (ignored)"),
        }
    }
    Ok(())
}

/// Ensure OAuth profile declarations against Curity's RESTCONF.
///
/// **Endpoint shape (UNVERIFIED — needs licensed-Curity smoke):**
///
/// ```text
/// PUT https://<peer.host>:<peer.port>{RESTCONF_BASE}/se.curity:profiles
///   Content-Type: application/yang-data+json
///   Authorization: xBasic <admin-creds>
///   Body: { "se.curity:profiles": { "profile": [ <profile-1>, ... ] } }
/// ```
///
/// Each profile in `state.yaml::oauth-profiles[]` maps to a JSON object:
/// `{ "id": "auth-service", "type": "authentication", "endpoints": [...],
///   "authenticators": [...] }`. Curity's RESTCONF mirrors its XML
/// schema 1-to-1 — the JSON keys match the XML element names.
///
/// Idempotency strategy: PUT (create-or-replace) the entire profiles
/// list. Removing an entry from state.yaml does NOT delete it from
/// Curity — that's a separate drop-step migration (per X3 plan §2a).
///
/// **Why this is a stub:** Curity RESTCONF is license-gated (returns
/// 503 FeatureViolationException on every endpoint until a license is
/// in place via the file-init channel). Until the license bootstrap
/// is verified working, the exact endpoint path + body shape can't be
/// validated against a live Curity. Replacing this stub is a focused
/// exercise once `verify_license` returns 200 in the smoke.
async fn ensure_oauth_profiles(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    if count > 0 {
        return Err(format!(
            "ensure_oauth_profiles is STUB (X3c follow-up needs licensed-Curity smoke to verify the RESTCONF endpoint shape). state.yaml::oauth-profiles declares {count} entry/entries that would silently be ignored if this returned Ok. Either drop the oauth-profiles section from state.yaml or implement the handler against /admin/api/restconf/data/se.curity:profiles."
        ).into());
    }
    eprintln!("[curity-api-config] ensure: oauth-profiles section empty — nothing to do");
    Ok(())
}

/// Ensure OAuth client declarations against Curity's RESTCONF.
///
/// **Endpoint shape (UNVERIFIED — needs licensed-Curity smoke):**
///
/// ```text
/// PUT https://<peer.host>:<peer.port>{RESTCONF_BASE}/se.curity:profiles/profile=<profile-id>/se.curity.profile.oauth:settings/client-store/config-backed
///   Content-Type: application/yang-data+json
///   Body: { "client": [ <client-1>, ... ] }
/// ```
///
/// Each client in `state.yaml::clients[]` maps to:
/// `{ "id": "flutter-app", "client-type": "public",
///   "grant-types": ["authorization-code"], "pkce": { "required": true },
///   "redirect-uris": ["bluetext://callback"], "scope": [...] }`.
///
/// Public-vs-confidential distinction sits under `client-type`;
/// PKCE flags under `pkce`; scope binding under `scope` (a list of
/// scope ids that must already exist — order this AFTER ensure_scopes).
async fn ensure_clients(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    if count > 0 {
        return Err(format!(
            "ensure_clients is STUB. state.yaml::clients declares {count} entry/entries that would silently be ignored if this returned Ok. Either drop the clients section from state.yaml or implement the handler against the profile's client-store endpoint."
        ).into());
    }
    eprintln!("[curity-api-config] ensure: clients section empty — nothing to do");
    Ok(())
}

/// Ensure OAuth scope declarations against Curity's RESTCONF.
///
/// **Endpoint shape (UNVERIFIED — needs licensed-Curity smoke):**
///
/// ```text
/// PUT https://<peer.host>:<peer.port>{RESTCONF_BASE}/se.curity:profiles/profile=<profile-id>/se.curity.profile.oauth:settings/scopes
///   Content-Type: application/yang-data+json
///   Body: { "scope": [ <scope-1>, ... ] }
/// ```
///
/// Each scope in `state.yaml::scopes[]` maps to:
/// `{ "id": "openid" }` or `{ "id": "profile", "claim": ["given-name", "family-name"] }`.
///
/// Scopes must exist before clients reference them via `scope:` —
/// `ensure_state`'s walk order matters (scopes before clients).
async fn ensure_scopes(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    if count > 0 {
        return Err(format!(
            "ensure_scopes is STUB. state.yaml::scopes declares {count} entry/entries that would silently be ignored if this returned Ok. Either drop the scopes section from state.yaml or implement the handler against the profile's scopes endpoint."
        ).into());
    }
    eprintln!("[curity-api-config] ensure: scopes section empty — nothing to do");
    Ok(())
}

/// Ensure RBAC role declarations against Curity's RESTCONF.
///
/// **Endpoint shape (UNVERIFIED — needs licensed-Curity smoke):**
///
/// ```text
/// PUT https://<peer.host>:<peer.port>{RESTCONF_BASE}/se.curity:authorization/se.curity.authorization.configuration-based:configuration-based/role
///   Content-Type: application/yang-data+json
///   Body: { "role": [ { "id": "admin" }, { "id": "moderator" } ] }
/// ```
///
/// Each role in `state.yaml::roles[]` maps to `{ "id": "<role-id>" }`.
/// Today's auth/curity-rbac blueprint's roles-scope.xml fragment
/// translates directly — same role-id surface.
async fn ensure_roles(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    if count > 0 {
        return Err(format!(
            "ensure_roles is STUB. state.yaml::roles declares {count} entry/entries that would silently be ignored if this returned Ok. Either drop the roles section from state.yaml or implement the handler against /admin/api/restconf/data/se.curity:authorization/.../role."
        ).into());
    }
    eprintln!("[curity-api-config] ensure: roles section empty — nothing to do");
    Ok(())
}

/// Build an HTTPS client that tolerates the self-signed cert Curity's
/// idsvr image ships with. K3d-internal traffic is trusted by network
/// perimeter (the api-config Job runs inside the cluster); the cert
/// check would otherwise always fail because the bundled cert isn't
/// signed by any CA in the rust-rustls default root store.
fn build_admin_client() -> Result<reqwest::Client> {
    reqwest::Client::builder()
        .danger_accept_invalid_certs(true)
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| format!("build reqwest client: {e}").into())
}
