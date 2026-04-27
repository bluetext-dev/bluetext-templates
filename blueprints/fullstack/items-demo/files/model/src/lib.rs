use bluetext_model::prelude::*;
use bluetext_model::stores::couchbase::{CouchbaseBucket, CouchbaseCollection};

pub mod items;

pub use items::Item;

// State machine — the whole data model for the fullstack demo.
//
// `items` is a typed handle to a Couchbase collection, not an in-memory
// map. Mutations/getters run against the live bucket, so the same
// `#[mutation] create_item` the simulation exercises is the one the api
// controller calls in production (docs/MODELING.md).
//
// `stores` is what the simulation engine uses to reset / dump bucket
// state between randomized traces.
#[derive(StateMachine)]
#[state_machine(name = "items-demo")]
pub struct AppState {
    items: CouchbaseCollection<Item>,
    stores: Stores,
}

impl AppState {
    // Production bootstrap. Connects to the Couchbase bucket pointed at
    // by the `COUCHBASE_*` env vars injected via
    // `b service wire <api> -u couchbase -c data`.
    pub async fn connect() -> Result<Self, String> {
        let host = std::env::var("COUCHBASE_HOST")
            .map_err(|_| "COUCHBASE_HOST not set".to_string())?;
        let user = std::env::var("COUCHBASE_USERNAME")
            .map_err(|_| "COUCHBASE_USERNAME not set".to_string())?;
        let pass = std::env::var("COUCHBASE_PASSWORD")
            .map_err(|_| "COUCHBASE_PASSWORD not set".to_string())?;
        let bucket = std::env::var("COUCHBASE_BUCKET")
            .unwrap_or_else(|_| "main".to_string());

        let auth = ::couchbase::authenticator::Authenticator::PasswordAuthenticator(
            ::couchbase::authenticator::PasswordAuthenticator {
                username: user,
                password: pass,
            },
        );
        let opts = ::couchbase::options::cluster_options::ClusterOptions::new(auth);
        let cb_bucket = CouchbaseBucket::connect(
            &format!("couchbase://{host}"),
            opts,
            &bucket,
        )
        .await
        .map_err(|e| format!("couchbase connect failed: {e}"))?;

        Ok(Self {
            items: cb_bucket.collection("_default", "_default"),
            stores: Stores::new(vec![Box::new(cb_bucket)]),
        })
    }
}

// Mutations, getters, state invariants, simulation hooks — the state
// machine itself. Commands sit in a separate #[commands] block below so
// controllers can call them via &self (Ops<'_> is pub(crate) and not
// reachable from the api crate).
#[state_machine_impl]
impl AppState {
    // Atomic state transition. Idempotent: a retried call with the same
    // id returns Ok(false) so HTTP retries don't duplicate documents.
    #[mutation]
    pub async fn create_item(
        &self,
        #[new_key(items)] id: String,
        text: String,
    ) -> Result<bool, MutationError> {
        if self.items.exists(&id).await {
            return Ok(false);
        }
        self.items
            .upsert(&id, &Item { id: id.clone(), text })
            .await;
        Ok(true)
    }

    #[getter]
    pub async fn get_item(&self, #[key(items)] id: String) -> Option<Item> {
        self.items.get(&id).await
    }

    #[getter]
    pub async fn list_items(&self) -> Vec<Item> {
        self.items.values().await
    }

    // Enforced after every mutation in both production and simulation.
    #[state_invariant]
    pub async fn items_have_text(&self) -> bool {
        self.items.values().await.iter().all(|it| !it.text.is_empty())
    }

    #[simulation_init]
    pub async fn init(&self) {}

    // Randomized simulation step — the invariant checker replays this to
    // look for traces that violate `items_have_text`.
    #[simulation_step]
    pub async fn step(&self) -> bool {
        let id = format!("sim-{}", self.items.keys().await.len());
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
    state_factory: AppState::connect().await.expect("couchbase connect failed"),
}
