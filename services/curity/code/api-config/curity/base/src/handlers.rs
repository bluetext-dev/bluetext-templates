//! Curity admin-RESTCONF handlers.
//!
//! Each `#[handler]` function gets registered into the global inventory
//! via the bluetext-api-config-macros crate. The Job binary's main loop
//! looks up handlers by name (matching the `handler:` field in the
//! bundle's migration YAML).
//!
//! ## Implementation status
//!
//! - `post_license` — STUB (logs only, no admin-RESTCONF call). The
//!   bootstrap-and-rotate flow against the seeded `admin/admin` credentials
//!   needs HTTPS-with-self-signed-cert handling + first-fail-rotate-retry
//!   logic; tracked under Slice X3c follow-up.
//! - `ensure_state` — STUB (logs which sections of state.yaml exist).
//!   Each ensure section (oauth-profiles, clients, scopes, roles) maps to
//!   a per-resource RESTCONF read-modify-write call against Curity's
//!   profile / token-service / client-store / authorization-manager
//!   endpoints respectively.
//!
//! With these stubs the api-config Job runs to completion (Job.succeeded=1)
//! — proves the image build + harness + inventory wiring works. Real
//! handler logic lands in X3c's implementation pass.

use bluetext_api_config::{handler, ApiConfigCtx, Result};
use serde_yml::Value;

/// Install the Curity license via admin RESTCONF.
///
/// **Stub.** Real flow:
/// 1. Read /etc/bluetext/peers/self/{host,port,username,password}.
/// 2. POST {"License":"<jwt>"} to
///    https://<host>:<port>/admin/api/restconf/data/se.curity:base:facilities/license
///    with HTTP Basic auth (admin/admin on first run; rotated
///    fixed/curity-admin-password on subsequent runs).
/// 3. On 401 + admin/admin, rotate to the operator-set password
///    (PATCH user/admin) and retry once.
/// 4. The license bytes are read from /etc/bluetext/secrets/license-key
///    (X3 spec: peer projection of variant.secrets.license).
#[handler]
pub async fn post_license(ctx: &ApiConfigCtx) -> Result<()> {
    let peer = ctx.peer("self")?;
    eprintln!(
        "[curity-api-config] post_license stub: would POST license to https://{}:{} as user '{}'",
        peer.host()?,
        peer.port()?,
        peer.read("username")?.trim(),
    );
    Ok(())
}

/// Ensure declared state from state.yaml against Curity's admin
/// RESTCONF. Walks each top-level key (`oauth-profiles`, `clients`,
/// `scopes`, `roles`) and routes to the matching ensure function.
///
/// Called from main.rs after every migration has run. Idempotent:
/// each ensure is a read-modify-write against Curity's current
/// config; re-runs converge what's missing without disturbing what
/// already matches.
pub async fn ensure_state(_ctx: &ApiConfigCtx, state: &Value) -> Result<()> {
    let Some(map) = state.as_mapping() else {
        eprintln!("[curity-api-config] ensure_state: state.yaml is not a mapping — skipping");
        return Ok(());
    };
    for (key, _value) in map.iter() {
        let key_str = key.as_str().unwrap_or("<non-string>");
        match key_str {
            "license" => eprintln!("[curity-api-config] ensure: license (declared in 001 migration, no-op here)"),
            "oauth-profiles" => eprintln!("[curity-api-config] ensure: oauth-profiles (STUB — Slice X3c)"),
            "clients" => eprintln!("[curity-api-config] ensure: clients (STUB — Slice X3c)"),
            "scopes" => eprintln!("[curity-api-config] ensure: scopes (STUB — Slice X3c)"),
            "roles" => eprintln!("[curity-api-config] ensure: roles (STUB — Slice X3c)"),
            other => eprintln!("[curity-api-config] ensure: unknown key '{other}' (ignored)"),
        }
    }
    Ok(())
}
