//! Couchbase managed-state worked example.
//!
//! Demonstrates the `#[migration]` shape: an async fn that takes a
//! `MigrationCtx`, reads the admin link's host/port/credentials, and
//! talks to the Couchbase REST API to provision a bucket. Idempotent —
//! re-running on an existing bucket returns the bucket-exists 202 without
//! erroring.
//!
//! Customise this file in your system: add your own `#[migration]` fns
//! to create the buckets / scopes / collections your services consume.
//! The deploy pipeline picks them up via `inventory` at deploy time.

use bluetext_managed_state::{migration, MigrationCtx, Result};

/// Create the system's primary bucket. Replace `default` with whatever
/// bucket your application expects; add scopes + collections via
/// additional `#[migration(...)]` handlers below.
#[migration("ensure-default-bucket")]
async fn ensure_default_bucket(ctx: &MigrationCtx) -> Result<()> {
    let admin = ctx.link("admin")?;
    let host = admin.host()?;
    // Couchbase's management API runs on port 8091 (HTTP) regardless of
    // the SDK port (11210). The link mount projects the variant's
    // declared port; we use port 8091 explicitly here for the admin API.
    let url = format!("http://{host}:8091/pools/default/buckets");
    let username = admin.read("username")?;
    let password = admin.read("password")?;

    let client = reqwest::Client::builder()
        .build()
        .map_err(|e| format!("build http client: {e}"))?;

    let resp = client
        .post(&url)
        .basic_auth(username.trim(), Some(password.trim()))
        .form(&[
            ("name", "default"),
            ("ramQuotaMB", "256"),
            ("bucketType", "couchbase"),
            ("durabilityMinLevel", "none"),
        ])
        .send()
        .await
        .map_err(|e| format!("POST {url}: {e}"))?;

    let status = resp.status();
    if status.is_success() {
        eprintln!("[couchbase-managed-state] created bucket 'default'");
        return Ok(());
    }
    // Couchbase returns 400 + body containing "already exists" when the
    // bucket is present. Idempotent: treat as success.
    let body = resp.text().await.unwrap_or_default();
    if body.contains("already exists") {
        eprintln!("[couchbase-managed-state] bucket 'default' already exists");
        return Ok(());
    }
    Err(format!("create bucket failed (status {status}): {body}").into())
}

/// Example: create a scope under the default bucket. Uncomment + adapt
/// to provision scopes for your application's data layout.
// #[migration("ensure-app-scope")]
// async fn ensure_app_scope(ctx: &MigrationCtx) -> Result<()> {
//     let admin = ctx.link("admin")?;
//     let host = admin.host()?;
//     let url = format!("http://{host}:8091/pools/default/buckets/default/scopes");
//     let client = reqwest::Client::new();
//     let resp = client
//         .post(&url)
//         .basic_auth(admin.read("username")?.trim(), Some(admin.read("password")?.trim()))
//         .form(&[("name", "app")])
//         .send()
//         .await
//         .map_err(|e| format!("POST {url}: {e}"))?;
//     let status = resp.status();
//     if status.is_success() {
//         return Ok(());
//     }
//     let body = resp.text().await.unwrap_or_default();
//     if body.contains("already exists") {
//         return Ok(());
//     }
//     Err(format!("create scope failed (status {status}): {body}").into())
// }
