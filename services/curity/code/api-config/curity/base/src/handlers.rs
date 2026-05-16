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

/// Path under RESTCONF data where the license JSON wrapper lands.
/// Confd-modelled schema; matches the file Curity's startup scanner
/// previously read at `/opt/idsvr/etc/init/license/default`.
const LICENSE_PATH: &str = "se.curity:base:facilities/license";

/// Hardcoded bootstrap admin password Curity's idsvr image seeds via
/// the `PASSWORD=admin` env on its deployment. Per the X3 plan §3b
/// the first ever Job run authenticates with this; subsequent runs use
/// the operator-set fixed/curity-admin-password projected through the
/// peer. When ConfD state is ephemeral (pod recreate destroys the
/// /opt/idsvr/var tree on the emptyDir mount), every fresh pod resets
/// to admin/admin and the Job effectively bootstraps on every run.
const BOOTSTRAP_PASSWORD: &str = "admin";

/// Install the Curity license via admin RESTCONF.
///
/// Reads `host`, `port`, `username`, `password`, `license-key` from the
/// reserved `self` peer projection. Tries the operator-set password
/// first; falls back to the bootstrap admin/admin on 401 (matches the
/// "ConfD state ephemeral" pod-restart shape — every fresh pod boots
/// at admin/admin and the Job must re-license).
///
/// The license body is `{"License":"<raw-jwt>"}` per Curity's
/// admin-API contract — same shape the retired file-based path used
/// to write to `/opt/idsvr/etc/init/license/default`.
#[handler]
pub async fn post_license(ctx: &ApiConfigCtx) -> Result<()> {
    let peer = ctx.peer("self")?;
    let host = peer.host()?;
    let port = peer.port()?;
    let username = peer.read("username")?.trim().to_string();
    let operator_password = peer.read("password")?.trim().to_string();
    let license_jwt = peer.read("license-key")?.trim().to_string();

    if license_jwt.is_empty() {
        return Err(
            "license-key on /etc/bluetext/peers/self/ is empty. Set the JWT via: \
             b secret set fixed/curity-license-key --from-env CURITY_LICENSE_KEY"
                .into(),
        );
    }

    let client = build_admin_client()?;
    let url = format!("https://{host}:{port}{RESTCONF_BASE}/{LICENSE_PATH}");
    let body = serde_json::json!({ "License": license_jwt });

    // Try the operator-set password first. On 401 fall back to the
    // bootstrap admin/admin (Curity's seeded credentials on a fresh
    // ConfD store). Either path that returns 2xx counts as success.
    eprintln!("[curity-api-config] POST {url} (user={username}, operator creds)");
    let res = client
        .post(&url)
        .basic_auth(&username, Some(&operator_password))
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("POST {url} with operator creds: {e}"))?;
    let status = res.status();
    if status.is_success() {
        eprintln!("[curity-api-config] license installed ({status})");
        return Ok(());
    }
    if status.as_u16() != 401 {
        let text = res.text().await.unwrap_or_default();
        return Err(format!(
            "POST {url} with operator creds returned {status}: {text}"
        )
        .into());
    }

    eprintln!(
        "[curity-api-config] operator creds rejected (401) — retrying with bootstrap admin/admin"
    );
    let res = client
        .post(&url)
        .basic_auth(&username, Some(BOOTSTRAP_PASSWORD))
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("POST {url} with bootstrap creds: {e}"))?;
    let status = res.status();
    if !status.is_success() {
        let text = res.text().await.unwrap_or_default();
        return Err(format!(
            "POST {url} with bootstrap creds returned {status}: {text}"
        )
        .into());
    }
    eprintln!("[curity-api-config] license installed via bootstrap creds ({status})");
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

/// Ensure OAuth profile declarations. STUB — the X3c full pass writes
/// the read-modify-write loop against Curity's
/// `<RESTCONF_BASE>/profiles/profile`. Each entry in state.yaml's
/// `oauth-profiles[]` maps to a JSON body shape mirroring Curity's
/// XML schema (id, type, endpoints, authenticators).
async fn ensure_oauth_profiles(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    eprintln!("[curity-api-config] ensure: {count} oauth-profile(s) declared — STUB (X3c follow-up)");
    Ok(())
}

/// Ensure OAuth client declarations. STUB — full pass writes
/// `<RESTCONF_BASE>/profiles/profile/token-service/settings/.../client-store/config-backed/client`
/// per entry.
async fn ensure_clients(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    eprintln!("[curity-api-config] ensure: {count} client(s) declared — STUB (X3c follow-up)");
    Ok(())
}

/// Ensure OAuth scope declarations. STUB — full pass writes
/// `<RESTCONF_BASE>/profiles/profile/token-service/settings/.../scopes` per entry.
async fn ensure_scopes(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    eprintln!("[curity-api-config] ensure: {count} scope(s) declared — STUB (X3c follow-up)");
    Ok(())
}

/// Ensure RBAC role declarations. STUB — full pass writes
/// `<RESTCONF_BASE>/authorization-manager/configuration-based/role` per entry.
async fn ensure_roles(_peer: &PeerData, value: &Value) -> Result<()> {
    let count = value.as_sequence().map(|s| s.len()).unwrap_or(0);
    eprintln!("[curity-api-config] ensure: {count} role(s) declared — STUB (X3c follow-up)");
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
