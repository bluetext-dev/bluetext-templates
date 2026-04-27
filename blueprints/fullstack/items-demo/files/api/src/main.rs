// The state-machine + command macros from `models` expand into deeply-nested
// async futures; the api binary's release-mode monomorphization runs past
// rustc's default trait-solver query depth.
#![recursion_limit = "512"]

use axum::{Json, Router, extract::State, http::StatusCode, routing::get};
use models::{AppState, Item};
use serde::{Deserialize, Serialize};
use std::{net::SocketAddr, sync::Arc};
use tower_http::cors::{Any, CorsLayer};
use uuid::Uuid;

// Controllers — one endpoint = one command call. No business logic, no
// direct database access. Validation, idempotency, and state invariants
// live in the model layer (see `code/models/src/items.rs`).

#[derive(Serialize)]
struct Health {
    status: &'static str,
}

async fn health() -> Json<Health> {
    Json(Health { status: "ok" })
}

#[derive(Deserialize)]
struct NewItem {
    text: String,
}

async fn list_items(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Vec<Item>>, (StatusCode, String)> {
    state
        .all_items()
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn create_item(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<NewItem>,
) -> Result<Json<Item>, (StatusCode, String)> {
    // The controller generates the id so a retried HTTP call is idempotent
    // at the mutation layer (`create_item` short-circuits on duplicate key).
    let id = Uuid::new_v4().to_string();
    state
        .submit_item(id, payload.text)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(3030);

    // Single bootstrap point — `AppState::__connect()` is auto-generated
    // by `#[state_machine]`; it reads the COUCHBASE_* env vars injected by
    // `b service wire` and binds each state-var. Every controller holds a
    // shared reference.
    let state = Arc::new(
        AppState::__connect()
            .await
            .map_err(|e| format!("connect failed: {e}"))?,
    );

    // Permissive CORS — web-app serves on a different subdomain ingress.
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    let app = Router::new()
        .route("/", get(|| async { "bluetext api" }))
        .route("/health", get(health))
        .route("/items", get(list_items).post(create_item))
        .with_state(state)
        .layer(cors);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    println!("api listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}
