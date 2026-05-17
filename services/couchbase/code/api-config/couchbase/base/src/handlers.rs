//! Couchbase admin-REST handlers.
//!
//! Each `#[handler]` fn gets registered into the global inventory via
//! the bluetext-api-config-macros crate. The Job binary's main loop
//! looks up handlers by name (matching the `handler:` field in the
//! bundle's migration YAML).
//!
//! ## Layout
//!
//! - [`provision`] — `#[handler]` invoked by the 001 migration. Reads
//!   `state.yaml` from the mounted bundle dir and converges
//!   Couchbase's bucket / scope / collection layout via the admin
//!   REST API. Idempotent: every step swallows "already exists"
//!   responses so re-running on an unchanged config is a no-op.

use bluetext_api_config::{handler, ApiConfigCtx, Result};
use serde::Deserialize;
use std::time::Duration;

/// Bundle dir mounted by the deploy pipeline.
const BUNDLE_DIR: &str = "/config/api/couchbase/base";

/// Parsed state.yaml shape. Only the `buckets:` section is interpreted
/// for now; future sections (indexes, RBAC users) can be added with
/// new ensure_* fns and routed by name.
#[derive(Debug, Deserialize)]
struct StateFile {
    #[serde(default)]
    buckets: Vec<BucketDecl>,
}

#[derive(Debug, Deserialize)]
struct BucketDecl {
    name: String,
    #[serde(rename = "ram-quota-mb", default = "default_ram_quota_mb")]
    ram_quota_mb: u32,
    #[serde(rename = "bucket-type", default = "default_bucket_type")]
    bucket_type: String,
    #[serde(rename = "durability-min-level", default = "default_durability")]
    durability_min_level: String,
    #[serde(default)]
    scopes: Vec<ScopeDecl>,
}

#[derive(Debug, Deserialize)]
struct ScopeDecl {
    name: String,
    #[serde(default)]
    collections: Vec<String>,
}

fn default_ram_quota_mb() -> u32 {
    100
}
fn default_bucket_type() -> String {
    "couchbase".into()
}
fn default_durability() -> String {
    "none".into()
}

/// Provision buckets, scopes, and collections per state.yaml.
///
/// Reads admin credentials from /etc/bluetext/peers/self/ — the
/// abstract's `ops` connection-profile mounts the bound variant's
/// `auth` secret there. Iterates state.yaml's `buckets:` list and
/// converges each entry; the `_default` scope is implicit and only
/// the collection step runs against it.
#[handler]
pub async fn provision(ctx: &ApiConfigCtx) -> Result<()> {
    let peer = ctx.peer("self")?;
    let host = peer.host()?;
    let port = peer.port()?;
    let user = peer.read("username")?.trim().to_string();
    let pw = peer.read("password")?.trim().to_string();

    let state_path = std::path::Path::new(BUNDLE_DIR).join("state.yaml");
    let text = std::fs::read_to_string(&state_path).map_err(|e| {
        format!(
            "read {} (the bundle's declared end-state): {e}. \
             The deploy pipeline mounts config/api/couchbase/base/ \
             read-only into the Job pod — a missing state.yaml here \
             means the bundle source didn't make it into the image.",
            state_path.display(),
        )
    })?;
    let state: StateFile = serde_yml::from_str(&text)
        .map_err(|e| format!("parse {}: {e}", state_path.display()))?;

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| format!("build http client: {e}"))?;
    // Couchbase Server's in-cluster admin REST listens on 8091 over
    // HTTP. The 18091/HTTPS variant is the external Capella admin;
    // running the api-config Job against Capella will need a separate
    // bundle that builds an HTTPS client + projected CA.
    let base = format!("http://{host}:{port}");

    for bucket in &state.buckets {
        ensure_bucket(&client, &base, &user, &pw, bucket).await?;
        for scope in &bucket.scopes {
            if scope.name != "_default" {
                ensure_scope(&client, &base, &user, &pw, &bucket.name, &scope.name).await?;
            }
            for collection in &scope.collections {
                ensure_collection(
                    &client,
                    &base,
                    &user,
                    &pw,
                    &bucket.name,
                    &scope.name,
                    collection,
                )
                .await?;
            }
        }
    }
    Ok(())
}

async fn ensure_bucket(
    client: &reqwest::Client,
    base: &str,
    user: &str,
    pw: &str,
    bucket: &BucketDecl,
) -> Result<()> {
    let url = format!("{base}/pools/default/buckets");
    let ram = bucket.ram_quota_mb.to_string();
    let resp = client
        .post(&url)
        .basic_auth(user, Some(pw))
        .form(&[
            ("name", bucket.name.as_str()),
            ("ramQuotaMB", ram.as_str()),
            ("bucketType", bucket.bucket_type.as_str()),
            ("durabilityMinLevel", bucket.durability_min_level.as_str()),
        ])
        .send()
        .await
        .map_err(|e| format!("POST {url}: {e}"))?;
    let status = resp.status();
    if status.is_success() {
        eprintln!("[couchbase-api-config] created bucket '{}'", bucket.name);
        return Ok(());
    }
    let body = resp.text().await.unwrap_or_default();
    if body.contains("already exists")
        || body.contains("Bucket with given name already exists")
    {
        eprintln!("[couchbase-api-config] bucket '{}' already exists", bucket.name);
        return Ok(());
    }
    Err(format!(
        "create bucket '{}' failed (status {status}): {body}",
        bucket.name
    )
    .into())
}

async fn ensure_scope(
    client: &reqwest::Client,
    base: &str,
    user: &str,
    pw: &str,
    bucket: &str,
    scope: &str,
) -> Result<()> {
    let url = format!("{base}/pools/default/buckets/{bucket}/scopes");
    let resp = client
        .post(&url)
        .basic_auth(user, Some(pw))
        .form(&[("name", scope)])
        .send()
        .await
        .map_err(|e| format!("POST {url}: {e}"))?;
    let status = resp.status();
    if status.is_success() {
        eprintln!("[couchbase-api-config] created scope '{bucket}.{scope}'");
        return Ok(());
    }
    let body = resp.text().await.unwrap_or_default();
    if body.contains("already exists") {
        return Ok(());
    }
    Err(format!("create scope '{bucket}.{scope}' failed (status {status}): {body}").into())
}

async fn ensure_collection(
    client: &reqwest::Client,
    base: &str,
    user: &str,
    pw: &str,
    bucket: &str,
    scope: &str,
    collection: &str,
) -> Result<()> {
    let url = format!("{base}/pools/default/buckets/{bucket}/scopes/{scope}/collections");
    let resp = client
        .post(&url)
        .basic_auth(user, Some(pw))
        .form(&[("name", collection)])
        .send()
        .await
        .map_err(|e| format!("POST {url}: {e}"))?;
    let status = resp.status();
    if status.is_success() {
        eprintln!(
            "[couchbase-api-config] created collection '{bucket}.{scope}.{collection}'"
        );
        return Ok(());
    }
    let body = resp.text().await.unwrap_or_default();
    if body.contains("already exists") {
        return Ok(());
    }
    Err(format!(
        "create collection '{bucket}.{scope}.{collection}' failed (status {status}): {body}"
    )
    .into())
}
