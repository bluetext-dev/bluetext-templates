# Couchbase — substrate guidance

How to persist with the Couchbase substrate from a service's state machine. This
card is injected into the agent's context whenever a system has Couchbase in use,
so the persistence path is known before the model is written. Full API:
`docs/MODELING.md` and the `fullstack/items-demo` blueprint.

## Persistence pattern

A Couchbase-backed state variable is a typed handle, **not** an in-memory map:
`items: CouchbaseCollection<Item>` (keys are always `String`; `Item` is the value
type). Declare the store + link at the struct level:

```rust
#[state_machine("app")]
#[store(couchbase, link = "database")]
pub struct AppState {
    #[store(collection = "_default")]
    items: CouchbaseCollection<Item>,
}

#[state_machine_impl]
impl AppState {
    #[mutation]
    pub async fn create_item(&self, #[new_key(items)] id: String, text: String)
        -> Result<bool, MutationError> {
        if self.items.exists(&id).await.expect("items.exists failed") { return Ok(false); }
        self.items.upsert(&id, &Item { id: id.clone(), text }).await.expect("items.upsert failed");
        Ok(true)
    }
}
```

- `link = "database"` **must match** the service's declared link
  (`links: { database: couchbase/<profile> }`). The macro reads the connection —
  host plus Vault/ESO-delivered credentials — from the link mount
  `/etc/bluetext/links/database/`, never from env vars. There is no hand-written
  `connect()`; the macro injects a hidden `__stores` field and `__connect()`.
- Value types are plain records: `#[derive(ModelType)]` (plus `serde`).
- Mutations / getters / state-invariants that touch a `CouchbaseCollection` must be
  `async`. Store errors **panic** (PRINCIPLES §16 — "Couchbase is reachable" is an
  internal invariant, not a caller-fixable precondition; Axum turns the panic into a 5xx).

## Anti-pattern — the one that bites

Do **not** reach for an in-memory `HashMap` "to get it working", and do **not** put
`#[state_machine_impl]` on a plain data type — that attribute is only for the
state-machine *impl* block; data types use `#[derive(ModelType)]`. Falling back to a
`HashMap` leaves the declared `database` link **dead**: the store is provisioned but
never read. `b health`'s wiring-gap signal (`proven-unused`) and `b overview`'s
`0 types bound` both flag exactly that. Bind the collection instead.

## Defaults block

- **connection:** the declared link mount (default link name is the store id
  `couchbase`; the items-demo blueprint names it `database`).
- **collection:** the field name, kebab-cased (`account_history` → `account-history`),
  or `_default` to use the bucket's always-present `_default._default` keyspace
  without provisioning a collection.
- **local credentials:** generated through the secret mechanism (`set_secret` tool /
  `b secret`), delivered to the mount — never placed on a command line.
