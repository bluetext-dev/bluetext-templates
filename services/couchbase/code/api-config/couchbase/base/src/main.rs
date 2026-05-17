//! Couchbase api-config Job binary.
//!
//! Built by `src/operations/deploy/api_config_image.rs` into the image
//! `bluetext-api-config/couchbase--server:latest` and run as a K8s Job
//! in the bound variant's namespace. The Job pod's env carries
//! `BLUETEXT_ABSTRACT`, `BLUETEXT_VARIANT`, `BLUETEXT_RSV`, and
//! `BLUETEXT_LEDGER_COLLECTION`. The reserved `self` peer is projected
//! at `/etc/bluetext/peers/self/` with `host` + `port` + `username` +
//! `password` files — the deploy pipeline derives those from the
//! abstract-level `connection-profiles.ops` entry the couchbase
//! service.yaml declares.
//!
//! This binary:
//!
//! 1. Loads its bundle directory (`config/api/couchbase/base/` on the
//!    system, which the deploy pipeline mounts read-only into the Job
//!    pod).
//! 2. Constructs the ctx from env + peer projection.
//! 3. Runs every migration's steps in numeric order, dispatching by
//!    `handler:` name into the inventory the `#[handler]` macros built.
//! 4. Migrations read `state.yaml` directly when they need the
//!    declarative end-state (the `provision` handler does this).

mod handlers;

use bluetext_api_config::{load_bundle, run_handler, ApiConfigCtx, Result};

#[tokio::main]
async fn main() -> Result<()> {
    let ctx = ApiConfigCtx::from_env()?;
    eprintln!(
        "[couchbase-api-config] starting: abstract={} variant={} rsv={}",
        ctx.abstract_id, ctx.variant_id, ctx.run_spec_variant
    );

    // The bundle source is mounted at /config/api/<sub>/<bundle>/ on
    // the Job pod by the deploy pipeline.
    let bundle_dir = std::path::PathBuf::from("/config/api/couchbase/base");
    if !bundle_dir.exists() {
        eprintln!(
            "[couchbase-api-config] bundle dir {} absent — running migrations from inventory only",
            bundle_dir.display()
        );
    }

    let (_state, migrations) = match load_bundle(&bundle_dir) {
        Ok(b) => b,
        Err(_) => (None, Vec::new()),
    };

    // Migrations in numeric order. The provision handler reads
    // state.yaml directly from the bundle dir to convergence the
    // declared buckets/scopes/collections.
    for (name, migration) in &migrations {
        eprintln!("[couchbase-api-config] migration {name}: {}", migration.description.trim());
        for step in &migration.steps {
            eprintln!("[couchbase-api-config]   step {} -> {}", step.id, step.handler);
            run_handler(&step.handler, &ctx).await.map_err(|e| {
                format!(
                    "migration '{name}' step '{}' (handler '{}') failed: {e}",
                    step.id, step.handler
                )
            })?;
        }
    }

    eprintln!("[couchbase-api-config] done");
    Ok(())
}
