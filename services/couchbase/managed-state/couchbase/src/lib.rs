//! Couchbase managed-state worked example.
//!
//! Demonstrates the `#[migration]` shape: an async fn that takes a
//! `MigrationCtx`, reads the admin link's host/port from
//! `/etc/bluetext/links/admin/`, and talks HTTP to the upstream.
//!
//! ## What this example actually does
//!
//! The handler probes Couchbase's `/pools` endpoint to assert the admin
//! API is reachable from the Job's pod. That's the minimum useful
//! migration: it proves the wave-1 → managed-state Job → wave-2 split
//! works for your system's network topology.
//!
//! ## Going further: real schema provisioning
//!
//! Provisioning buckets/scopes/collections needs the cluster to be
//! initialized first (Couchbase requires a `cluster-init` POST setting
//! quota + admin credentials before any other admin endpoint accepts
//! auth). Either:
//!
//! 1. Add an initContainer to `manifests/services/couchbase/server/
//!    base/deployment.yaml` that runs `couchbase-cli cluster-init`
//!    against the pod on first boot, then this handler can use
//!    `Administrator`/`password` to POST to `/pools/default/buckets`.
//!
//! 2. Extend this handler to do the cluster-init dance inline:
//!    POST `/pools/default` (quota), POST `/node/controller/
//!    setupServices` (kv/index/n1ql), POST `/settings/web` (admin
//!    credentials), then POST `/pools/default/buckets`.
//!
//! Both keep idempotence: Couchbase returns "already initialized" /
//! "already exists" on re-runs.

use bluetext_managed_state::{migration, MigrationCtx, Result};
use std::time::Duration;

/// Probe the Couchbase admin API on `/pools`. Returns 200 (with cluster
/// info) when the cluster is reachable, regardless of init state — so
/// this works as a barrier in any system layout. Replace with a real
/// provisioning handler once your cluster is initialized.
#[migration("probe-couchbase-admin")]
async fn probe_couchbase_admin(ctx: &MigrationCtx) -> Result<()> {
    let admin = ctx.link("admin")?;
    let host = admin.host()?;
    let port = admin.port()?;
    let url = format!("http://{host}:{port}/pools");

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| format!("build http client: {e}"))?;

    let resp = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("GET {url}: {e}"))?;

    let status = resp.status();
    if status.is_success() {
        eprintln!("[couchbase-managed-state] couchbase admin API reachable at {url}");
        return Ok(());
    }
    Err(format!("GET {url} returned {status}").into())
}
