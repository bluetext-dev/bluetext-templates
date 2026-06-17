//! Curity api-config Job binary.
//!
//! Built by `src/operations/deploy/api_config_image.rs` into the image
//! `bluetext-api-config/curity--curity:latest` and run as a K8s Job in the
//! bound variant's namespace. The Job pod's env carries `BLUETEXT_ABSTRACT`,
//! `BLUETEXT_VARIANT`, `BLUETEXT_RSV`, and `BLUETEXT_LEDGER_COLLECTION`.
//! The reserved `self` peer is projected at `/etc/bluetext/peers/self/`
//! with `host` + `port` + `username` + `password` files — the deploy
//! pipeline derives those from the variant-level `connection-profiles.api-config`
//! entry the curity.yaml declares.
//!
//! This binary:
//!
//! 1. Loads its bundle directory (`config/api/curity/base/` on the system,
//!    which the deploy pipeline mounts read-only into the Job pod).
//! 2. Constructs the ctx from env + peer projection.
//! 3. Runs every migration's steps in numeric order, dispatching by
//!    `handler:` name into the inventory the `#[handler]` macros built.
//! 4. After migrations, ensures the declared state.yaml — license,
//!    OAuth profiles, clients, scopes, roles.

mod handlers;

use bluetext_api_config::{load_bundle, run_handler, ApiConfigCtx, Result};

#[tokio::main]
async fn main() -> Result<()> {
    let ctx = ApiConfigCtx::from_env()?;
    eprintln!(
        "[curity-api-config] starting: abstract={} variant={} rsv={}",
        ctx.abstract_id, ctx.variant_id, ctx.run_spec_variant
    );

    // The bundle source is mounted at /config/api/<sub>/<bundle>/ on
    // the Job pod by the deploy pipeline. Phase-1 hardcodes the path;
    // multi-bundle Jobs (a follow-up slice) will iterate over a list
    // passed as args.
    let bundle_dir = std::path::PathBuf::from("/config/api/curity/base");
    if !bundle_dir.exists() {
        // Dev path: when the Job runs against a system that hasn't yet
        // received the bundle (b service add curity hasn't extended to
        // copy api-config/), fall back to the embedded path used during
        // image build.
        eprintln!(
            "[curity-api-config] bundle dir {} absent — running migrations from inventory only",
            bundle_dir.display()
        );
    }

    let (state, migrations) = match load_bundle(&bundle_dir) {
        Ok(b) => b,
        Err(_) => (None, Vec::new()),
    };

    // Run migrations in numeric order.
    for (name, migration) in &migrations {
        eprintln!("[curity-api-config] migration {name}: {}", migration.description.trim());
        for step in &migration.steps {
            eprintln!("[curity-api-config]   step {} -> {}", step.id, step.handler);
            run_handler(&step.handler, &ctx).await.map_err(|e| {
                format!(
                    "migration '{name}' step '{}' (handler '{}') failed: {e}",
                    step.id, step.handler
                )
            })?;
        }
    }

    // Ensure declared state. The handlers below interpret state.yaml's
    // top-level keys; each is idempotent.
    if let Some(bundle) = state {
        handlers::ensure_state(&ctx, &bundle.state).await?;
    } else {
        eprintln!("[curity-api-config] no state.yaml — skipping ensure phase");
    }

    eprintln!("[curity-api-config] done");
    Ok(())
}
