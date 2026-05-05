//! Curity managed-state worked example.
//!
//! ## When does Curity have managed-state?
//!
//! The deploy pipeline applies the managed-state Job between wave 1
//! (`kind: store`) and wave 2 (everything else). A managed-state crate
//! can only meaningfully talk to abstracts that are **already running**
//! when the Job fires — i.e. wave-1 stores. Curity itself is
//! `kind: service` and lands in wave 2, so the Job cannot reach Curity's
//! own admin API.
//!
//! Practical consequence: Curity's "managed state" usually lives one
//! abstract upstream. For the JDBC blueprint, curity uses Couchbase as
//! its data store; the bucket + collections that store must hold are
//! provisioned by **couchbase**'s managed-state crate (which runs
//! against an already-deployed Couchbase). Curity then boots and
//! discovers those collections through its JDBC datasource.
//!
//! ## What this file is, then
//!
//! Just the migration-handler shape. Use it as a starting point if
//! Curity gains a managed wave-1 upstream that needs schema (e.g.
//! HSQLDB-as-a-store, or a Postgres backing store) — duplicate the
//! handler, change `link("…")` to that upstream, and POST to its admin
//! endpoint as needed.

use bluetext_managed_state::{migration, MigrationCtx, Result};

/// Placeholder migration. Logs that the curity managed-state Job ran;
/// does no network work because curity isn't reachable from this Job
/// (it's wave 2).
#[migration("curity-noop")]
async fn curity_noop(_ctx: &MigrationCtx) -> Result<()> {
    eprintln!(
        "[curity-managed-state] noop migration ran. To provision real \
         Curity OAuth profiles, add a `#[migration(...)]` here that \
         POSTs to a wave-1 upstream's admin API (e.g. couchbase if \
         Curity uses it as a JDBC datasource)."
    );
    Ok(())
}
