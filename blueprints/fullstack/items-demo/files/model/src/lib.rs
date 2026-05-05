use bluetext_model::prelude::*;
use bluetext_model::stores::couchbase::CouchbaseCollection;

pub mod items;

pub use items::Item;

// State machine — the whole data model for the fullstack demo.
//
// `items` is a typed handle to a Couchbase collection, not an in-memory
// map. Mutations/getters run against the live bucket, so the same
// `#[mutation] create_item` the simulation exercises is the one the api
// controller calls in production (docs/MODELING.md).
//
// The `#[state_machine]` attribute injects a hidden `__stores` field
// and synthesizes `__connect()` that reads the COUCHBASE_* env vars and
// binds each state-var. No manual `connect()` to write here.
//
// The `#[store(collection = "_default")]` override routes documents
// into the bucket's always-existing `_default._default` keyspace, so the
// blueprint doesn't have to provision a dedicated collection.
#[state_machine("items-demo")]
pub struct AppState {
    #[store(collection = "_default")]
    items: CouchbaseCollection<Item>,
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
    pub async fn create_item(
        &self,
        #[new_key(items)] id: String,
        text: String,
    ) -> Result<bool, MutationError> {
        if self.items.exists(&id).await.expect("items.exists failed") {
            return Ok(false);
        }
        self.items
            .upsert(&id, &Item { id: id.clone(), text })
            .await
            .expect("items.upsert failed");
        Ok(true)
    }

    #[getter]
    pub async fn get_item(&self, #[key(items)] id: String) -> Option<Item> {
        self.items.get(&id).await.expect("items.get failed")
    }

    #[getter]
    pub async fn list_items(&self) -> Vec<Item> {
        self.items.values().await.expect("items.values failed")
    }

    // Enforced after every mutation in both production and simulation.
    #[state_invariant]
    pub async fn items_have_text(&self) -> bool {
        self.items
            .values()
            .await
            .expect("items.values failed")
            .iter()
            .all(|it| !it.text.is_empty())
    }

    #[simulation_init]
    pub async fn init(&self) {}

    // Randomized simulation step — the invariant checker replays this to
    // look for traces that violate `items_have_text`.
    #[simulation_step]
    pub async fn step(&self) -> bool {
        let id = format!(
            "sim-{}",
            self.items.keys().await.expect("items.keys failed").len()
        );
        let text = format!("simulated item {id}");
        self.submit_item(id, text).await.is_ok()
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
    pub async fn submit_item(&self, id: String, text: String) -> Result<Item, CommandError> {
        let text = text.trim().to_string();
        label!("Empty text");
        if text.is_empty() {
            return Err(CommandError::from("text must not be empty"));
        }
        self.create_item(id.clone(), text.clone())
            .await
            .map_err(|e| CommandError::from(format!("create_item failed: {e:?}")))?;
        Ok(Item { id, text })
    }

    #[command]
    pub async fn all_items(&self) -> Result<Vec<Item>, CommandError> {
        Ok(self.list_items().await)
    }
}

bluetext_model::model! {
    state_machine: AppState,
    source_dir: "model/src",
    modules: [],
    types: [items::Item],
    commands: [__commands_block_meta],
}
