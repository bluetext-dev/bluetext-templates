// The state-machine + command macros from `models` expand into deeply-nested
// async futures; the api binary's release-mode monomorphization runs past
// rustc's default trait-solver query depth.
#![recursion_limit = "512"]

use axum::{
    Json, Router,
    extract::{Path, State},
    http::StatusCode,
    routing::get,
};
use models::{AppState, {{entity}}};
use serde::{Deserialize, Serialize};
use std::{net::SocketAddr, sync::Arc};
use tower_http::cors::{AllowHeaders, AllowMethods, AllowOrigin, CorsLayer};
use uuid::Uuid;

// Controllers — one endpoint = one command call. No business logic, no
// direct database access. Validation, idempotency, and state invariants
// live in the model layer (see `model/src/{{collection}}.rs`).

#[derive(Serialize)]
struct Health {
    status: &'static str,
}

async fn health() -> Json<Health> {
    Json(Health { status: "ok" })
}

#[derive(Deserialize)]
struct New{{entity}} {
    text: String,
}

async fn list_{{collection}}(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Vec<{{entity}}>>, (StatusCode, String)> {
    state
        .all_{{collection}}()
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

// Path captures use axum 0.8 syntax: `{id}` in the route, `Path(id)` in the
// handler. (`:id` is the pre-0.8 syntax — it panics at startup on 0.8.)
async fn get_{{entity_snake}}(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<{{entity}}>, (StatusCode, String)> {
    match state.one_{{entity_snake}}(id).await {
        Ok(Some(item)) => Ok(Json(item)),
        Ok(None) => Err((StatusCode::NOT_FOUND, "not found".to_string())),
        Err(e) => Err((StatusCode::INTERNAL_SERVER_ERROR, e.to_string())),
    }
}

async fn create_{{entity_snake}}(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<New{{entity}}>,
) -> Result<Json<{{entity}}>, (StatusCode, String)> {
    // The controller generates the id so a retried HTTP call is idempotent
    // at the mutation layer (`create_{{entity_snake}}` short-circuits on
    // duplicate key).
    let id = Uuid::new_v4().to_string();
    state
        .submit_{{entity_snake}}(id, payload.text)
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
    // by `#[state_machine]`; it reads the `database` link mount and binds
    // each state-var. Every controller holds a shared reference.
    let state = Arc::new(
        AppState::__connect()
            .await
            .map_err(|e| format!("connect failed: {e}"))?,
    );

    // Permissive but credential-safe CORS — the web-app serves on a different
    // subdomain ingress, and behind the lab gateway the browser must send its
    // auth cookie cross-origin (`credentials: "include"`). The CORS spec
    // forbids combining credentials with wildcard `*` for origin, methods, or
    // headers, so we mirror the request dynamically instead of using `Any`.
    // This stays just as permissive in dev while remaining spec-compliant.
    let cors = CorsLayer::new()
        .allow_origin(AllowOrigin::mirror_request())
        .allow_methods(AllowMethods::mirror_request())
        .allow_headers(AllowHeaders::mirror_request())
        .allow_credentials(true);

    let app = Router::new()
        .route("/", get(|| async { "bluetext api" }))
        .route("/health", get(health))
        .route("/{{collection}}", get(list_{{collection}}).post(create_{{entity_snake}}))
        .route("/{{collection}}/{id}", get(get_{{entity_snake}}))
        .with_state(state)
        .layer(cors);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    println!("api listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}
