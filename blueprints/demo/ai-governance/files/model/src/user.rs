use bluetext_model::prelude::*;
use crate::types::{User, Role};

#[state_machine_actions]
impl crate::AppState {
    #[mutation]
    pub async fn create_user(&mut self, id: String, name: String, email: String, role: Role, daily_quota: f64) -> bool {
        if self.users.contains_key(&id) {
            return false;
        }
        self.users.insert(id.clone(), User { id, name, email, role, daily_quota });
        true
    }

    #[mutation]
    pub async fn update_quota(&mut self, #[key(users)] id: String, daily_quota: f64) -> bool {
        if let Some(user) = self.users.get_mut(&id) {
            user.daily_quota = daily_quota;
            true
        } else {
            false
        }
    }

    #[getter]
    pub async fn get_user(&self, #[key(users)] id: &str) -> Option<User> {
        self.users.get(id).cloned()
    }

    #[getter]
    pub async fn list_users(&self) -> Vec<User> {
        self.users.values().cloned().collect()
    }
}
