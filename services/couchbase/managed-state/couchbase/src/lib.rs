//! Couchbase managed-state worked example.
//!
//! Creates the buckets, scopes, and collections the system's services
//! consume. Idempotent — every step swallows the "already exists"
//! response, so re-running the deploy (or the host-agent watcher
//! re-firing on source change) is a no-op against an unchanged config.
//!
//! ## Lifecycle position
//!
//! Runs in the per-deploy managed-state Job (between wave 1 and wave 2).
//! By the time this handler executes, Couchbase's `postStart` lifecycle
//! hook has finished `cluster-init` and the admin credentials in
//! `/etc/bluetext/links/admin/{username,password}` work — see
//! `manifests/services/couchbase/server/base/deployment.yaml`.
//!
//! ## Customising
//!
//! Edit `BUCKETS_AND_COLLECTIONS` below to declare what your system's
//! services need. The example ships the layout `auth/curity-couchbase-jdbc`
//! expects — a `curity` bucket with the collections the JDBC wrapper
//! references — so the Curity datasource works end-to-end on first
//! deploy.

use bluetext_managed_state::{migration, MigrationCtx, Result};
use std::time::Duration;

/// Each tuple is `(bucket, scope, collections)`. `_default` is the
/// implicit default scope; passing it skips scope creation but still
/// runs the per-collection step.
const BUCKETS_AND_COLLECTIONS: &[(&str, &str, &[&str])] = &[(
    "curity",
    "_default",
    &[
        "accounts",
        "account_resource_relations",
        "audit",
        "buckets",
        "credentials",
        "database_clients",
        "database_client_resource_relations",
        "database_service_providers",
        "delegations",
        "devices",
        "dynamically_registered_clients",
        "entities",
        "entity_relations",
        "linked_accounts",
        "nonces",
        "sessions",
        "tokens",
    ],
)];

#[migration("provision-buckets-and-collections")]
async fn provision(ctx: &MigrationCtx) -> Result<()> {
    let admin = ctx.link("admin")?;
    let host = admin.host()?;
    let port = admin.port()?;
    let user = admin.read("username")?;
    let pw = admin.read("password")?;
    let user = user.trim();
    let pw = pw.trim();

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| format!("build http client: {e}"))?;
    let base = format!("http://{host}:{port}");

    for (bucket, scope, collections) in BUCKETS_AND_COLLECTIONS {
        ensure_bucket(&client, &base, user, pw, bucket).await?;
        if *scope != "_default" {
            ensure_scope(&client, &base, user, pw, bucket, scope).await?;
        }
        for collection in *collections {
            ensure_collection(&client, &base, user, pw, bucket, scope, collection).await?;
        }
    }
    Ok(())
}

async fn ensure_bucket(
    client: &reqwest::Client,
    base: &str,
    user: &str,
    pw: &str,
    bucket: &str,
) -> Result<()> {
    let url = format!("{base}/pools/default/buckets");
    let resp = client
        .post(&url)
        .basic_auth(user, Some(pw))
        .form(&[
            ("name", bucket),
            ("ramQuotaMB", "100"),
            ("bucketType", "couchbase"),
            ("durabilityMinLevel", "none"),
        ])
        .send()
        .await
        .map_err(|e| format!("POST {url}: {e}"))?;
    let status = resp.status();
    if status.is_success() {
        eprintln!("[couchbase-managed-state] created bucket '{bucket}'");
        return Ok(());
    }
    let body = resp.text().await.unwrap_or_default();
    if body.contains("already exists") || body.contains("Bucket with given name already exists") {
        eprintln!("[couchbase-managed-state] bucket '{bucket}' already exists");
        return Ok(());
    }
    Err(format!("create bucket '{bucket}' failed (status {status}): {body}").into())
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
        eprintln!("[couchbase-managed-state] created scope '{bucket}.{scope}'");
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
        eprintln!("[couchbase-managed-state] created collection '{bucket}.{scope}.{collection}'");
        return Ok(());
    }
    let body = resp.text().await.unwrap_or_default();
    if body.contains("already exists") {
        return Ok(());
    }
    Err(
        format!("create collection '{bucket}.{scope}.{collection}' failed (status {status}): {body}")
            .into(),
    )
}
