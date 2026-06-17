use bluetext_model::prelude::*;

// The one entity in this demo. Ordinary serde struct; `ModelType` gives
// the diagram view its metadata + random-value generator for simulation.
// Adapt the FIELDS to your domain here (keep `id` — it is the document
// key); the type name, module, collection, and routes already carry the
// blueprint's `entity` / `collection` variables.
#[derive(Clone, Debug, Serialize, Deserialize, ModelType)]
pub struct {{entity}} {
    pub id: String,
    pub text: String,
}
