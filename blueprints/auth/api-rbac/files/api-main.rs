use axum::{
    Json, Router, routing::get,
    extract::Request,
    http::StatusCode,
    middleware::{self, Next},
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Claims {
    sub: String,
    #[serde(default)]
    scope: String,
    #[serde(default)]
    roles: Vec<String>,
    #[serde(default)]
    email: String,
    #[serde(default)]
    name: String,
}

impl Claims {
    fn has_role(&self, role: &str) -> bool {
        self.roles.iter().any(|r| r == role)
    }
}

fn decode_jwt_payload(token: &str) -> Result<Claims, String> {
    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() != 3 {
        return Err("invalid JWT format".to_string());
    }
    let payload = base64::Engine::decode(
        &base64::engine::general_purpose::URL_SAFE_NO_PAD,
        parts[1],
    )
    .or_else(|_| {
        base64::Engine::decode(&base64::engine::general_purpose::STANDARD, parts[1])
    })
    .map_err(|e| format!("base64 decode error: {e}"))?;
    serde_json::from_slice(&payload).map_err(|e| format!("JSON parse error: {e}"))
}

async fn auth_middleware(mut req: Request, next: Next) -> Response {
    let auth_header = req
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    let token = if let Some(t) = auth_header.strip_prefix("Bearer ") {
        t
    } else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "missing bearer token"}))).into_response();
    };

    match decode_jwt_payload(token) {
        Ok(claims) => {
            req.extensions_mut().insert(claims);
            next.run(req).await
        }
        Err(e) => {
            (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": e}))).into_response()
        }
    }
}

fn require_role(claims: &Claims, role: &str) -> Result<(), Response> {
    if claims.has_role(role) {
        Ok(())
    } else {
        Err((
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": format!("{role} role required")})),
        )
            .into_response())
    }
}

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
        "users": ["admin", "moderator", "user"],
        "requested_by": claims.sub,
    }))
    .into_response()
}

async fn moderate(req: Request) -> Response {
    let claims = req.extensions().get::<Claims>().unwrap();
    if let Err(resp) = require_role(claims, "moderator") {
        // Allow admin to access moderator routes too
        if let Err(resp2) = require_role(claims, "admin") {
            let _ = resp2;
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
        .layer(middleware::from_fn(auth_middleware));

    let app = Router::new()
        .route("/", get(|| async { "bluetext api" }))
        .route("/health", get(health))
        .merge(protected);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    println!("api listening on {addr}");

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
