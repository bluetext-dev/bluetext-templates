use bluetext_model::prelude::*;
use std::collections::HashMap;

pub mod types;
mod user;
mod ai_model;
mod policy;
mod usage;

use types::*;

#[state_machine(name = "AI Governance")]
#[derive(Debug, Serialize, Deserialize, Default)]
pub struct AppState {
    users: HashMap<String, User>,
    models: HashMap<String, AIModel>,
    policies: HashMap<String, Policy>,
    usage: HashMap<String, UsageRecord>,
}

#[state_machine_impl(extra_meta = [
    user::__sm_actions_meta,
    ai_model::__sm_actions_meta,
    policy::__sm_actions_meta,
    usage::__sm_actions_meta,
])]
impl AppState {
    #[simulation_init]
    pub async fn init(&mut self) {
        self.create_user("admin".into(), "Admin".into(), "admin@example.com".into(), Role::Admin, 100.0).await;
        self.create_user("dev-1".into(), "Alice".into(), "alice@example.com".into(), Role::Developer, 10.0).await;
        self.create_user("dev-2".into(), "Bob".into(), "bob@example.com".into(), Role::Developer, 10.0).await;

        self.register_model("gpt-4".into(), "GPT-4".into(), "openai".into(), "https://api.openai.com/v1".into(), 0.03).await;
        self.register_model("claude".into(), "Claude".into(), "anthropic".into(), "https://api.anthropic.com/v1".into(), 0.015).await;
        self.register_model("llama".into(), "Llama 3".into(), "meta".into(), "http://localhost:8080".into(), 0.001).await;

        self.create_policy("default".into(), "Default Policy".into(), 4096, 50.0, vec!["gpt-4".into(), "claude".into(), "llama".into()]).await;
        self.create_policy("restricted".into(), "Cost-Restricted".into(), 2048, 5.0, vec!["llama".into()]).await;
    }

    #[simulation_step]
    pub async fn step(&mut self) -> bool {
        let user_ids: Vec<String> = self.users.keys().cloned().collect();
        let model_ids: Vec<String> = self.models.keys().filter(|id| {
            self.models.get(*id).map_or(false, |m| m.is_active)
        }).cloned().collect();

        if user_ids.is_empty() || model_ids.is_empty() {
            return false;
        }

        let user_id = &user_ids[self.usage.len() % user_ids.len()];
        let model_id = &model_ids[self.usage.len() % model_ids.len()];
        let tokens_in = 100 + (self.usage.len() as u64 * 37) % 500;
        let tokens_out = 50 + (self.usage.len() as u64 * 23) % 300;

        let _ = self.process_request(user_id.clone(), model_id.clone(), tokens_in, tokens_out).await;
        true
    }
}

bluetext_model::model! {
    state_machine: AppState,
    source_dir: "model/src",
    modules: [user, ai_model, policy, usage],
    types: [types::User, types::AIModel, types::Policy, types::UsageRecord],
    commands: [usage::__commands_block_meta],
}
