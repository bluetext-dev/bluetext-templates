use bluetext_model::prelude::*;

// The one entity in this demo. Ordinary serde struct; `ModelType` gives
// the diagram view its metadata + random-value generator for simulation.
#[derive(Clone, Debug, Serialize, Deserialize, ModelType)]
pub struct Item {
    pub id: String,
    pub text: String,
}
