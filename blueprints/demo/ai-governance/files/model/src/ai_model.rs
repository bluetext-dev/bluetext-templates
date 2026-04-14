use bluetext_model::prelude::*;
use crate::types::AIModel;

#[state_machine_actions]
impl crate::AppState {
    #[mutation]
    pub async fn register_model(&mut self, id: String, name: String, provider: String, endpoint: String, cost_per_token: f64) -> bool {
        if self.models.contains_key(&id) {
            return false;
        }
        self.models.insert(id.clone(), AIModel {
            id, name, provider, endpoint, cost_per_token, is_active: true,
        });
        true
    }

    #[mutation]
    pub async fn deactivate_model(&mut self, #[key(models)] id: String) -> bool {
        if let Some(model) = self.models.get_mut(&id) {
            model.is_active = false;
            true
        } else {
            false
        }
    }

    #[getter]
    pub async fn get_model(&self, #[key(models)] id: &str) -> Option<AIModel> {
        self.models.get(id).cloned()
    }

    #[getter]
    pub async fn list_models(&self) -> Vec<AIModel> {
        self.models.values().cloned().collect()
    }

    #[getter]
    pub async fn active_models(&self) -> Vec<AIModel> {
        self.models.values().filter(|m| m.is_active).cloned().collect()
    }

    #[state_invariant]
    pub async fn model_has_valid_endpoint(&self) -> bool {
        self.models.values().all(|m| !m.endpoint.is_empty())
    }
}
