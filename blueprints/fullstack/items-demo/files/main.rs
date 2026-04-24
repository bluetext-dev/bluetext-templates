use axum::{extract::State, http::StatusCode, Json, Router, routing::get};
use clients::couchbase::{CouchbaseClient, CouchbaseConf, Keyspace};
use serde::{Deserialize, Serialize};
use std::{net::SocketAddr, sync::Arc};
use tower_http::cors::{Any, CorsLayer};
use uuid::Uuid;

#[derive(Serialize)]
struct Health {
    status: &'static str,
}

async fn health() -> Json<Health> {
    Json(Health { status: "ok" })
}

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Item {
    id: String,
    text: String,
}

#[derive(Deserialize)]
struct NewItem {
    text: String,
}

#[derive(Clone)]
struct AppState {
    keyspace: Keyspace,
}

async fn list_items(
    State(state): State<Arc<AppState>>,
) -> Result<Json<Vec<Item>>, (StatusCode, String)> {
    let raw = state
        .keyspace
        .list(Some(100))
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e))?;
    // `Keyspace::list` runs `SELECT META().id, * FROM <keyspace>` so each
    // row is shaped like `{"id": "<meta>", "<collection>": {...actual doc}}`.
    // Find the first nested-object value and decode it into an Item.
    let items: Vec<Item> = raw
        .into_iter()
        .filter_map(|row| {
            let obj = row.as_object()?;
            let inner = obj.values().find(|v| v.is_object())?.clone();
            serde_json::from_value::<Item>(inner).ok()
        })
        .collect();
    Ok(Json(items))
}

async fn create_item(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<NewItem>,
) -> Result<Json<Item>, (StatusCode, String)> {
    let text = payload.text.trim();
    if text.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "text must not be empty".into()));
    }
    let item = Item {
        id: Uuid::new_v4().to_string(),
        text: text.to_string(),
    };
    state
        .keyspace
        .insert(&item, Some(&item.id))
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e))?;
    Ok(Json(item))
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(3030);

    // Pull COUCHBASE_* from env (injected via `b service wire <api> -u couchbase -c data`).
    let conf = CouchbaseConf::from_env("COUCHBASE")?;
    let bucket = conf.bucket.clone();
    let client = CouchbaseClient::new(conf).await?;
    // Returns () — it logs success/failure internally and doesn't bubble errors.
    client.ensure_collection_exists("_default", None, None).await;
    let keyspace = Keyspace::from_string(&format!("{}._default._default", bucket), client)?;

    let state = Arc::new(AppState { keyspace });

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
