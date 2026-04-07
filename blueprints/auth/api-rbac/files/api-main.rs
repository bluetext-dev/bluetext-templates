use axum::{
    Json, Router, routing::get,
    extract::Request,
    middleware,
    response::{IntoResponse, Response},
};
use clients::jwt_auth::{self, Claims, require_role};
use serde::Serialize;
use std::net::SocketAddr;

// --- Handlers ---

#[derive(Serialize)]
struct Health {
    status: &'static str,
}

async fn health() -> Json<Health> {
    Json(Health { status: "ok" })
}

async fn hello(req: Request) -> impl IntoResponse {
    let claims = req.extensions().get::<Claims>().unwrap();
    Json(serde_json::json!({
        "message": format!("hello, {}", claims.sub),
    }))
}

async fn me(req: Request) -> impl IntoResponse {
    let claims = req.extensions().get::<Claims>().unwrap();
    Json(serde_json::json!({
        "sub": claims.sub,
        "roles": claims.roles,
        "scope": claims.scope,
        "email": claims.email,
        "name": claims.name,
    }))
}

async fn admin_users(req: Request) -> Response {
    let claims = req.extensions().get::<Claims>().unwrap();
    if let Err(resp) = require_role(claims, "admin") {
        return resp;
    }
    Json(serde_json::json!({
        "requested_by": claims.sub,
        "users": [
            {"username": "alice", "email": "alice@example.com", "roles": ["admin"]},
            {"username": "bob", "email": "bob@example.com", "roles": ["moderator"]},
            {"username": "carol", "email": "carol@example.com", "roles": []},
        ],
    }))
    .into_response()
}

async fn moderate(req: Request) -> Response {
    let claims = req.extensions().get::<Claims>().unwrap();
    if let Err(resp) = require_role(claims, "moderator") {
        if let Err(_) = require_role(claims, "admin") {
            return resp;
        }
    }
    Json(serde_json::json!({
        "message": "moderator access granted",
        "requested_by": claims.sub,
    }))
    .into_response()
}

#[tokio::main]
async fn main() {
    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(3030);

    let protected = Router::new()
        .route("/hello", get(hello))
        .route("/me", get(me))
        .route("/admin/users", get(admin_users))
        .route("/moderate", get(moderate))
        .layer(middleware::from_fn(jwt_auth::middleware));

    let app = Router::new()
        .route("/", get(|| async { "bluetext api" }))
        .route("/health", get(health))
        .merge(protected);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    println!("api listening on {addr}");

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
