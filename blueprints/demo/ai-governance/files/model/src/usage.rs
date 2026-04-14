use bluetext_model::prelude::*;
use crate::types::UsageRecord;

#[state_machine_actions]
impl crate::AppState {
    #[mutation]
    pub async fn record_usage(&mut self, id: String, model_id: String, user_id: String, tokens_in: u64, tokens_out: u64, cost: f64, timestamp: u64) -> bool {
        self.usage.insert(id.clone(), UsageRecord {
            id, model_id, user_id, tokens_in, tokens_out, cost, timestamp,
        });
        true
    }

    #[getter]
    pub async fn usage_by_user(&self, #[key(users)] user_id: &str) -> Vec<UsageRecord> {
        self.usage.values().filter(|r| r.user_id == user_id).cloned().collect()
    }

    #[getter]
    pub async fn usage_by_model(&self, #[key(models)] model_id: &str) -> Vec<UsageRecord> {
        self.usage.values().filter(|r| r.model_id == model_id).cloned().collect()
    }

    #[getter]
    pub async fn daily_cost(&self, #[key(users)] user_id: &str) -> f64 {
        self.usage.values()
            .filter(|r| r.user_id == user_id)
            .map(|r| r.cost)
            .sum()
    }

    #[state_invariant]
    pub async fn usage_within_quota(&self) -> bool {
        for user in self.users.values() {
            let total_cost: f64 = self.usage.values()
                .filter(|r| r.user_id == user.id)
                .map(|r| r.cost)
                .sum();
            if total_cost > user.daily_quota {
                return false;
            }
        }
        true
    }
}

#[commands]
impl crate::AppState {
    #[command]
    pub async fn process_request(&mut self, user_id: String, model_id: String, tokens_in: u64, tokens_out: u64) -> Result<bool, CommandError> {
        let user = self.get_user(&user_id).await.ok_or(CommandError::from("user not found"))?;
        let model = self.get_model(&model_id).await.ok_or(CommandError::from("model not found"))?;

        label!("Model inactive");
        if !model.is_active {
            return Err(CommandError::from("model is not active"));
        }

        let cost = (tokens_in + tokens_out) as f64 * model.cost_per_token;
        let current_cost = self.daily_cost(&user_id).await;

        label!("Quota exceeded");
        if current_cost + cost > user.daily_quota {
            return Err(CommandError::from("daily quota exceeded"));
        }

        let id = format!("{}-{}-{}", user_id, model_id, self.usage.len());
        Ok(self.record_usage(id, model_id, user_id, tokens_in, tokens_out, cost, 0).await)
    }

    #[command]
    pub async fn enforce_policy(&mut self, policy_id: String, model_id: String, tokens_requested: u64) -> Result<bool, CommandError> {
        let policy = self.get_policy(&policy_id).await.ok_or(CommandError::from("policy not found"))?;

        label!("Model not allowed");
        if !policy.allowed_models.contains(&model_id) {
            return Err(CommandError::from("model not allowed by policy"));
        }

        label!("Tokens exceed limit");
        if tokens_requested > policy.max_tokens_per_request {
            return Err(CommandError::from("token limit exceeded"));
        }

        Ok(true)
    }
}
