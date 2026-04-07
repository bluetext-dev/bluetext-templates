//! JWT authentication middleware for the phantom-token pattern.
//!
//! Decodes JWT payload from the Authorization header. No signature verification —
//! upstream (Kong phantom-token plugin) already validated the token via introspection
//! and replaced the opaque token with a signed JWT.
//!
//! # Usage
//!
//! ```rust
//! use axum::{Router, routing::get, middleware};
//! use clients::jwt_auth::{self, Claims, require_role};
//!
//! let app = Router::new()
//!     .route("/protected", get(handler))
//!     .layer(middleware::from_fn(jwt_auth::middleware));
//!
//! async fn handler(req: axum::extract::Request) -> impl axum::response::IntoResponse {
//!     let claims = req.extensions().get::<Claims>().unwrap();
//!     format!("hello {}, roles: {:?}", claims.sub, claims.roles)
//! }
//! ```

use axum::{
    extract::Request,
    http::{header, StatusCode},
    middleware::Next,
    response::{IntoResponse, Response},
    Json,
};
use serde::{Deserialize, Serialize};

/// JWT claims extracted from the token payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    #[serde(default)]
    pub scope: String,
    #[serde(default)]
    pub roles: Vec<String>,
    #[serde(default)]
    pub email: String,
    #[serde(default)]
    pub name: String,
}

impl Claims {
    pub fn has_role(&self, role: &str) -> bool {
        self.roles.iter().any(|r| r == role)
    }
}

/// Returns 403 if the user does not have the required role.
pub fn require_role(claims: &Claims, role: &str) -> Result<(), Response> {
    if claims.has_role(role) {
        Ok(())
    } else {
        Err(forbidden(&format!("{role} role required")))
    }
}

/// Decode the JWT payload without signature verification.
///
/// The phantom-token pattern means Kong already validated the token.
/// We only need to extract the claims from the payload.
pub fn decode_payload(token: &str) -> Result<Claims, String> {
    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() != 3 {
        return Err("invalid JWT format".into());
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

/// Axum middleware that extracts JWT claims from the Authorization header.
///
/// On success, claims are stored in request extensions and accessible via
/// `req.extensions().get::<Claims>()` in downstream handlers.
///
/// Returns 401 if the token is missing, malformed, or cannot be decoded.
pub async fn middleware(mut req: Request, next: Next) -> Response {
    let auth_header = match req.headers().get(header::AUTHORIZATION) {
        Some(h) => match h.to_str() {
            Ok(s) => s,
            Err(_) => return unauthorized("Missing, invalid or expired access token"),
        },
        None => return unauthorized("Missing, invalid or expired access token"),
    };
    let token = match auth_header.strip_prefix("Bearer ") {
        Some(t) => t,
        None => return unauthorized("Missing, invalid or expired access token"),
    };
    match decode_payload(token) {
        Ok(claims) => {
            req.extensions_mut().insert(claims);
            next.run(req).await
        }
        Err(_) => unauthorized("Missing, invalid or expired access token"),
    }
}

fn unauthorized(msg: &str) -> Response {
    (
        StatusCode::UNAUTHORIZED,
        Json(serde_json::json!({
            "code": "unauthorized",
            "message": msg,
        })),
    )
        .into_response()
}

fn forbidden(msg: &str) -> Response {
    (
        StatusCode::FORBIDDEN,
        Json(serde_json::json!({
            "code": "forbidden",
            "message": msg,
        })),
    )
        .into_response()
}
