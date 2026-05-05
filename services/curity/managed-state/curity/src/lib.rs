//! Curity managed-state worked example.
//!
//! Demonstrates the `#[migration]` shape for IdP provisioning. The
//! example below probes Curity's OIDC discovery endpoint to assert the
//! IdP is reachable from the migration Job's network — a useful
//! precondition before any RESTCONF calls.
//!
//! Real systems extend this with handlers that POST RESTCONF
//! configuration to the admin API to declare OAuth profiles, clients,
//! scopes, and roles. The shape stays the same: an async fn that takes
//! a `&MigrationCtx` and returns `Result<()>`.

use bluetext_managed_state::{migration, MigrationCtx, Result};
use std::time::Duration;

/// Wait for Curity's OIDC discovery document to respond. Useful as a
/// barrier before subsequent migrations that talk to the admin API —
/// the OIDC port comes up before the admin port on a fresh boot.
#[migration("await-oidc-ready")]
async fn await_oidc_ready(ctx: &MigrationCtx) -> Result<()> {
    let oidc = ctx.link("oidc")?;
    let host = oidc.host()?;
    let port = oidc.port()?;
    let url = format!("http://{host}:{port}/oauth/v2/oauth-anonymous/.well-known/openid-configuration");

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|e| format!("build http client: {e}"))?;

    for attempt in 1..=30 {
        match client.get(&url).send().await {
            Ok(resp) if resp.status().is_success() => {
                eprintln!("[curity-managed-state] OIDC discovery ready");
                return Ok(());
            }
            Ok(resp) => {
                eprintln!("[curity-managed-state] OIDC not ready yet (status {}); attempt {attempt}/30", resp.status());
            }
            Err(e) => {
                eprintln!("[curity-managed-state] OIDC probe error (attempt {attempt}/30): {e}");
            }
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
    Err(format!("Curity OIDC at {url} did not become ready within 60s").into())
}

// Add additional `#[migration(...)]` handlers below to provision OAuth
// profiles, clients, and scopes via the admin RESTCONF API. They run in
// definition order after `await-oidc-ready` succeeds.
