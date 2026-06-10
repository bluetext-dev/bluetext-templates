use bluetext_model::prelude::*;
use bluetext_model::stores::couchbase::CouchbaseCollection;

pub mod {{collection}};

pub use {{collection}}::{{entity}};

// State machine — the whole data model for the fullstack demo.
//
// `{{collection}}` is a typed handle to a Couchbase collection, not an
// in-memory map. Mutations/getters run against the live bucket, so the same
// `#[mutation] create_{{entity_snake}}` the simulation exercises is the one
// the api controller calls in production (docs/MODELING.md).
//
// The `#[state_machine]` attribute injects a hidden `__stores` field
// and synthesizes `__connect()`, which reads the connection — host plus
// Vault/ESO-delivered credentials — from the `database` link mount at
// `/etc/bluetext/links/database/`, never from env vars. No manual
// `connect()` to write here.
//
// `#[store(couchbase, link = "database")]` names the link this model reads,
// matching the `links: database: couchbase/data-writer` entry the blueprint adds
// to the api service. Each entity gets its OWN named collection (here
// `{{collection}}`), provisioned by the api-config `state.yaml` this blueprint
// writes.
//
// IMPORTANT when you adapt this: use a DISTINCT collection per type. `values::<T>()`
// reads EVERY document in a collection, so two types sharing one collection make
// the read fail to deserialize. To add a second entity (e.g. `Order`), give it its
// own `#[store(collection = "orders")]` AND add an `orders` collection to
// `config/api/couchbase/base/state.yaml`.
#[state_machine("{{collection}}-demo")]
#[store(couchbase, link = "database")]
pub struct AppState {
    #[store(collection = "{{collection}}")]
    {{collection}}: CouchbaseCollection<{{entity}}>,
}

// Mutations, getters, state invariants, simulation hooks — the state
// machine itself. Commands sit in a separate #[commands] block below so
// controllers can call them via &self (Ops<'_> is pub(crate) and not
// reachable from the api crate).
#[state_machine_impl]
impl AppState {
    // Atomic state transition. Idempotent: a retried call with the same
    // id returns Ok(false) so HTTP retries don't duplicate documents.
    //
    // Store errors panic per PRINCIPLES §16 — "Couchbase is reachable" is
    // an internal invariant, not a precondition the caller can fix. Axum's
    // panic handler converts to a 5xx, which the controller surfaces.
    #[mutation]
    pub async fn create_{{entity_snake}}(
        &self,
        #[new_key({{collection}})] id: String,
        text: String,
    ) -> Result<bool, MutationError> {
        if self.{{collection}}.exists(&id).await.expect("{{collection}}.exists failed") {
            return Ok(false);
        }
        self.{{collection}}
            .upsert(&id, &{{entity}} { id: id.clone(), text })
            .await
            .expect("{{collection}}.upsert failed");
        Ok(true)
    }

    #[getter]
    pub async fn get_{{entity_snake}}(&self, #[key({{collection}})] id: String) -> Option<{{entity}}> {
        self.{{collection}}.get(&id).await.expect("{{collection}}.get failed")
    }

    #[getter]
    pub async fn list_{{collection}}(&self) -> Vec<{{entity}}> {
        self.{{collection}}.values().await.expect("{{collection}}.values failed")
    }

    // Enforced after every mutation in both production and simulation.
    #[state_invariant]
    pub async fn {{collection}}_have_text(&self) -> bool {
        self.{{collection}}
            .values()
            .await
            .expect("{{collection}}.values failed")
            .iter()
            .all(|entry| !entry.text.is_empty())
    }

    #[simulation_init]
    pub async fn init(&self) {}

    // Randomized simulation step — the invariant checker replays this to
    // look for traces that violate `{{collection}}_have_text`.
    #[simulation_step]
    pub async fn step(&self) -> bool {
        let id = format!(
            "sim-{}",
            self.{{collection}}.keys().await.expect("{{collection}}.keys failed").len()
        );
        let text = format!("simulated {{entity_snake}} {id}");
        self.submit_{{entity_snake}}(id, text).await.is_ok()
    }
}

// Commands — the public API of the data model. Each command orchestrates
// mutations + getters and guarantees eventual consistency (docs/MODELING.md).
// Controllers call exactly one command; no business logic in the HTTP layer.
#[commands]
impl AppState {
    // Validate input and persist. Caller passes the id so retries are
    // idempotent at the mutation layer.
    #[command]
    pub async fn submit_{{entity_snake}}(&self, id: String, text: String) -> Result<{{entity}}, CommandError> {
        let text = text.trim().to_string();
        label!("Empty text");
        if text.is_empty() {
            return Err(CommandError::from("text must not be empty"));
        }
        self.create_{{entity_snake}}(id.clone(), text.clone())
            .await
            .map_err(|e| CommandError::from(format!("create_{{entity_snake}} failed: {e:?}")))?;
        Ok({{entity}} { id, text })
    }

    #[command]
    pub async fn all_{{collection}}(&self) -> Result<Vec<{{entity}}>, CommandError> {
        Ok(self.list_{{collection}}().await)
    }
}

bluetext_model::model! {
    state_machine: AppState,
    source_dir: "model/src",
    modules: [],
    types: [{{collection}}::{{entity}}],
    commands: [__commands_block_meta],
}
