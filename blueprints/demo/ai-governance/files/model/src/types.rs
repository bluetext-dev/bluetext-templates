use bluetext_model::prelude::*;

#[derive(Clone, Debug, Serialize, Deserialize, ModelType)]
pub enum Role {
    Admin,
    Developer,
}

#[derive(Clone, Debug, Serialize, Deserialize, ModelType)]
pub struct User {
    pub id: String,
    pub name: String,
    pub email: String,
    pub role: Role,
    pub daily_quota: f64,
}

#[derive(Clone, Debug, Serialize, Deserialize, ModelType)]
pub struct AIModel {
    pub id: String,
    pub name: String,
    pub provider: String,
    pub endpoint: String,
    pub cost_per_token: f64,
    pub is_active: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, ModelType)]
pub struct Policy {
    pub id: String,
    pub name: String,
    pub max_tokens_per_request: u64,
    pub max_daily_cost: f64,
    pub allowed_models: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, ModelType)]
pub struct UsageRecord {
    pub id: String,
    pub model_id: String,
    pub user_id: String,
    pub tokens_in: u64,
    pub tokens_out: u64,
    pub cost: f64,
    pub timestamp: u64,
}
