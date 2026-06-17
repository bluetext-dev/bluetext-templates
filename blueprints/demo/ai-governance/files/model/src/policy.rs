use bluetext_model::prelude::*;
use crate::types::Policy;

#[state_machine_actions]
impl crate::AppState {
    #[mutation]
    pub async fn create_policy(&mut self, id: String, name: String, max_tokens_per_request: u64, max_daily_cost: f64, allowed_models: Vec<String>) -> bool {
        if self.policies.contains_key(&id) {
            return false;
        }
        self.policies.insert(id.clone(), Policy {
            id, name, max_tokens_per_request, max_daily_cost, allowed_models,
        });
        true
    }

    #[mutation]
    pub async fn update_policy(&mut self, #[key(policies)] id: String, max_tokens_per_request: u64, max_daily_cost: f64, allowed_models: Vec<String>) -> bool {
        if let Some(policy) = self.policies.get_mut(&id) {
            policy.max_tokens_per_request = max_tokens_per_request;
            policy.max_daily_cost = max_daily_cost;
            policy.allowed_models = allowed_models;
            true
        } else {
            false
        }
    }

    #[getter]
    pub async fn get_policy(&self, #[key(policies)] id: &str) -> Option<Policy> {
        self.policies.get(id).cloned()
    }

    #[getter]
    pub async fn list_policies(&self) -> Vec<Policy> {
        self.policies.values().cloned().collect()
    }
}
