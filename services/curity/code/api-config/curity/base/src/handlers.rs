//! Curity admin-RESTCONF handlers.
//!
//! Each `#[handler]` function gets registered into the global inventory
//! via the bluetext-api-config-macros crate. The Job binary's main loop
//! looks up handlers by name (matching the `handler:` field in the
//! bundle's migration YAML).
//!
//! ## Layout
//!
//! - [`verify_license`] — `#[handler]` invoked by the 001 migration.
//!   Probes RESTCONF to confirm the file-init license install took
//!   effect. Tries operator credentials first, falls back to the
//!   `admin/admin` bootstrap when ConfD state is ephemeral
//!   (k3d pod-recreate wipes the persisted password rotation).
//!
//! - [`ensure_state`] — called from `main.rs` after every migration.
//!   Walks `state.yaml` top-level keys and routes to the matching
//!   `ensure_*` per-section function. Validates the section names
//!   first so a typo like `scope:` (vs `scopes:`) fails loudly
//!   instead of silently being skipped.
//!
//! - `ensure_oauth_profiles` / `ensure_clients` / `ensure_scopes` /
//!   `ensure_roles` — PUT each entry in the section to its RESTCONF
//!   endpoint via [`CurityClient`]. Idempotent (PUT = create-or-replace);
//!   re-runs converge what's missing.
//!
//! - [`CurityClient`] — shared HTTPS-to-admin helper. Builds the
//!   reqwest client once, holds the credentials, and wraps every PUT
//!   with an actionable error containing both the request URL and the
//!   Curity response body so live-smoke iteration sees both sides of
//!   the wire.

use bluetext_api_config::{handler, ApiConfigCtx, PeerData, Result};
use serde_yml::Value;

/// Curity's RESTCONF root mounted at the bundled admin port (default 6749).
const RESTCONF_BASE: &str = "/admin/api/restconf/data";

/// Hardcoded bootstrap admin password Curity's idsvr image seeds via
/// the `PASSWORD=admin` env on its deployment. The X3 plan §3b
/// chose the bootstrap-then-rotate flow over pre-baking a credential
/// into init XML. When ConfD state is ephemeral (k3d pod-recreate
/// destroys the /opt/idsvr/var emptyDir tree), every fresh pod resets
/// to admin/admin — so handlers must fall back to bootstrap creds on
/// 401 from the operator-set credential.
const BOOTSTRAP_USER: &str = "admin";
const BOOTSTRAP_PASS: &str = "admin";

/// State.yaml top-level keys this bundle's handlers understand.
/// `ensure_state` rejects any other top-level key — a typo like
/// `scope:` instead of `scopes:` would silently be skipped otherwise.
const KNOWN_STATE_KEYS: &[&str] = &[
    "license",        // file-init; handled by 001 migration via verify_license
    "oauth-profiles",
    "clients",
    "scopes",
    "roles",
];

/// Verify Curity's runtime is licensed by probing admin RESTCONF.
///
/// **License install is NOT API-installable on Curity** — every RESTCONF
/// endpoint returns 503 FeatureViolationException when the runtime is
/// unlicensed. The license therefore flows through the file-based
/// channel (`/opt/idsvr/etc/init/license/default`, populated by the
/// curity deployment's `config-templater` init container from the
/// mounted `curity--license` Secret). See `services/curity/README.md`
/// for the hybrid bootstrap architecture.
///
/// Probe sequence:
///
/// 1. GET RESTCONF root with the operator-set credentials.
/// 2. On 200 → license is in place + credentials work → Ok.
/// 3. On 401 → operator creds wrong; try bootstrap admin/admin (the
///    Curity image's seeded credential when ConfD has never been
///    initialised, also what every fresh k3d pod boots with).
///    - 200 → license is in place, ConfD is ephemeral / never
///      rotated. Ok (the user's deploy is operating against fresh
///      ConfD; this is normal for k3d).
///    - 401 → both credentials rejected. Most likely cause: the
///      operator's curity-admin-password secret + Curity's persisted
///      ConfD admin password drifted, OR Curity's admin user
///      hasn't been bootstrapped at all. Fail loud.
///    - 503 → license missing.
/// 4. On 503 → license is unambiguously missing (Curity processed the
///    request enough to evaluate the license but rejected it before
///    auth — fail loud with the operator-action hint).
/// 5. Other status → unexpected; fail with the raw status.
#[handler]
pub async fn verify_license(ctx: &ApiConfigCtx) -> Result<()> {
    let peer = ctx.peer("self")?;
    let host = peer.host()?;
    let port = peer.port()?;
    let operator_user = peer.read("username")?.trim().to_string();
    let operator_pass = peer.read("password")?.trim().to_string();

    if operator_user.is_empty() {
        return Err(
            "/etc/bluetext/peers/self/username is empty. Set it with: \
             b secret set fixed/curity-admin-username --from-env CURITY_ADMIN_USERNAME \
             (typical value: `admin`)."
                .into(),
        );
    }
    if operator_pass.is_empty() {
        return Err(
            "/etc/bluetext/peers/self/password is empty. Set it with: \
             b secret set fixed/curity-admin-password --from-env CURITY_ADMIN_PASSWORD"
                .into(),
        );
    }

    let client = CurityClient::build(host, port)?;
    let url = format!("{}{}", client.base_url, RESTCONF_BASE);
    eprintln!("[curity-api-config] verify_license: probing {url}");

    let res = client
        .http
        .get(&url)
        .basic_auth(&operator_user, Some(&operator_pass))
        .send()
        .await
        .map_err(|e| format!("GET {url} with operator creds: {e}"))?;
    let status = res.status();

    if status.is_success() {
        eprintln!(
            "[curity-api-config] verify_license: RESTCONF reachable ({status}) with operator creds — license in place"
        );
        return Ok(());
    }
    if status.as_u16() == 503 {
        return Err(license_missing_error().into());
    }
    if status.as_u16() == 401 {
        eprintln!(
            "[curity-api-config] verify_license: operator creds rejected (401); retrying with bootstrap admin/admin"
        );
        let res = client
            .http
            .get(&url)
            .basic_auth(BOOTSTRAP_USER, Some(BOOTSTRAP_PASS))
            .send()
            .await
            .map_err(|e| format!("GET {url} with bootstrap creds: {e}"))?;
        let bs_status = res.status();
        if bs_status.is_success() {
            eprintln!(
                "[curity-api-config] verify_license: RESTCONF reachable ({bs_status}) with bootstrap creds — license in place. NOTE: Curity is using bootstrap admin/admin (ConfD ephemeral on this pod). Operator-set credentials in fixed/curity-admin-password are unused unless ConfD persistence lands. ensure_* will use bootstrap creds for the rest of this Job run."
            );
            return Ok(());
        }
        if bs_status.as_u16() == 503 {
            return Err(license_missing_error().into());
        }
        if bs_status.as_u16() == 401 {
            return Err(format!(
                "Curity admin RESTCONF rejected both operator creds (user `{operator_user}`) AND bootstrap admin/admin. Either Curity's admin user wasn't seeded (check pod logs for ConfD-startup errors) or the operator-set password disagrees with Curity's persisted state. Run `kubectl exec deploy/curity--curity -- ls /opt/idsvr/etc/init/` to confirm init configs landed; check pod logs for `Could not authenticate ********** with given password`."
            )
            .into());
        }
        return Err(format!(
            "Curity admin RESTCONF returned unexpected {bs_status} on bootstrap creds retry."
        )
        .into());
    }
    Err(format!("Curity admin RESTCONF returned unexpected {status} on operator creds.").into())
}

fn license_missing_error() -> String {
    "Curity admin RESTCONF returned 503 FeatureViolationException — license isn't in place. \
     Curity reads the license file at /opt/idsvr/etc/init/license/default; \
     the deploy pipeline populates it via the curity--license Secret \
     mounted by the curity deployment's config-templater init container. \
     Likely causes: \
     (1) `b secret set fixed/curity-license-key --from-env CURITY_LICENSE_KEY` wasn't run before deploy; \
     (2) CURITY_LICENSE_KEY contains only the JWT's payload section, not the complete signed JWT (check `echo -n \"$CURITY_LICENSE_KEY\" | tr -cd '.' | wc -c` — must print 2). The portal hands you a JSON envelope `{\"License\":\"<base64>.<base64>.<base64>\"}` — the env var must contain the value of the License field verbatim, including the two `.` separators. Curity rejects payload-only content with `LicenseKeyValidationCallback - License was the wrong issuer or had not subject` because structural JWT validation fails before claim validation runs; \
     (3) the curity pod hasn't restarted since the Secret was populated (its init container reads the Secret at boot)."
        .to_string()
}

/// Ensure declared state from state.yaml against Curity's admin
/// RESTCONF. Walks each top-level key, validates it's known, then
/// routes to the matching ensure function. Unknown keys are an error
/// (rejecting typos like `scope:` vs `scopes:` instead of silently
/// skipping them).
pub async fn ensure_state(ctx: &ApiConfigCtx, state: &Value) -> Result<()> {
    let Some(map) = state.as_mapping() else {
        eprintln!("[curity-api-config] ensure_state: state.yaml is not a mapping — skipping");
        return Ok(());
    };
    let peer = ctx.peer("self")?;
    let client = CurityClient::from_peer(peer)?;

    // Schema check first: every top-level key must be in KNOWN_STATE_KEYS.
    // Catch typos before we start writing — partial application of a
    // valid section followed by a typo-section failure would leave
    // Curity in an inconsistent state.
    for (key, _) in map.iter() {
        let key_str = key.as_str().unwrap_or("<non-string>");
        if !KNOWN_STATE_KEYS.contains(&key_str) {
            return Err(format!(
                "state.yaml has unknown top-level key `{key_str}`. Known keys: {:?}. \
                 (Typo? `scope` vs `scopes`; `client` vs `clients`; `role` vs `roles`.)",
                KNOWN_STATE_KEYS
            )
            .into());
        }
    }

    // Walk in a deterministic order so log output is comparable across
    // re-runs + so the inter-handler ordering (scopes before clients;
    // oauth-profiles before clients) is enforced regardless of YAML
    // key order in the source file.
    for key in KNOWN_STATE_KEYS {
        let Some(value) = map.get(Value::from(*key)) else {
            continue;
        };
        match *key {
            "license" => eprintln!(
                "[curity-api-config] ensure: license — handled by 001 migration's verify_license; no-op here"
            ),
            "oauth-profiles" => ensure_oauth_profiles(&client, value).await?,
            "scopes" => ensure_scopes(&client, value).await?,
            "clients" => ensure_clients(&client, value).await?,
            "roles" => ensure_roles(&client, value).await?,
            other => unreachable!("KNOWN_STATE_KEYS contains unmatched key `{other}`"),
        }
    }
    Ok(())
}

/// PUT each OAuth profile to its RESTCONF resource path.
///
/// Per Curity's YANG-modeled admin API: each entry is a profile of
/// some type (authentication / oauth / scim / etc.). Per profile we
/// PUT to `/admin/api/restconf/data/se.curity:profiles/profile=<id>`
/// with a body that wraps the profile JSON under the profile-type
/// namespace key (e.g. `se.curity.profile.oauth:profile`).
///
/// The wrapping key is **profile-type-specific**: Curity uses
/// `se.curity.profile.authentication:profile` for `type: authentication`,
/// `se.curity.profile.oauth:profile` for `type: oauth`, etc. This handler
/// picks the wrapper key from the entry's `type:` field — falling back
/// to the generic `se.curity:profile` for unknown types so the user
/// sees Curity's error response rather than a hardcoded refusal.
///
/// The endpoint path + body shape mirror the YANG schema; if Curity
/// rejects the call the error message includes both the URL and the
/// response body so iteration is fast against a live cluster.
async fn ensure_oauth_profiles(client: &CurityClient, value: &Value) -> Result<()> {
    let Some(seq) = value.as_sequence() else {
        return Err("state.yaml::oauth-profiles must be a list".into());
    };
    if seq.is_empty() {
        eprintln!("[curity-api-config] ensure: oauth-profiles section empty — nothing to do");
        return Ok(());
    }
    eprintln!("[curity-api-config] ensure_oauth_profiles: {} profile(s)", seq.len());
    for entry in seq {
        let id = require_string(entry, "id", "oauth-profile entry")?;
        let kind = require_string(entry, "type", "oauth-profile entry")?;
        let wrapper_key = match kind.as_str() {
            "authentication" => "se.curity.profile.authentication:profile",
            "oauth" => "se.curity.profile.oauth:profile",
            "scim" => "se.curity.profile.scim:profile",
            other => {
                eprintln!(
                    "[curity-api-config] ensure_oauth_profiles: unknown profile type `{other}` for id `{id}` — sending under generic key `se.curity:profile`. If Curity rejects, add an explicit mapping for this profile type to handlers.rs."
                );
                "se.curity:profile"
            }
        };
        let body = serde_json::json!({ wrapper_key: yaml_to_json(entry)? });
        let path = format!("/se.curity:profiles/profile={id}");
        client.put(&path, &body).await?;
    }
    Ok(())
}

/// PUT scopes under the OAuth profile's settings.
///
/// **Endpoint shape (UNVERIFIED — needs licensed-Curity smoke):**
///
/// ```text
/// PUT {RESTCONF_BASE}/se.curity:profiles/profile=<profile-id>/se.curity.profile.oauth:settings/scopes/scope=<scope-id>
///   Body: { "se.curity.profile.oauth:scope": { "id": ..., "claim": [...] } }
/// ```
///
/// `state.yaml::scopes[]` entries don't carry an explicit profile id
/// — they're assumed to belong to the token-service profile (matching
/// the X3 plan's state.yaml example). If your bundle has multiple
/// OAuth profiles, scopes need a `profile:` field; surface that as an
/// error rather than silently writing to a default.
async fn ensure_scopes(client: &CurityClient, value: &Value) -> Result<()> {
    let Some(seq) = value.as_sequence() else {
        return Err("state.yaml::scopes must be a list".into());
    };
    if seq.is_empty() {
        eprintln!("[curity-api-config] ensure: scopes section empty — nothing to do");
        return Ok(());
    }
    eprintln!("[curity-api-config] ensure_scopes: {} scope(s)", seq.len());
    let profile_id = "token-service";
    for entry in seq {
        let id = require_string(entry, "id", "scope entry")?;
        let body = serde_json::json!({
            "se.curity.profile.oauth:scope": yaml_to_json(entry)?
        });
        let path = format!(
            "/se.curity:profiles/profile={profile_id}/se.curity.profile.oauth:settings/scopes/scope={id}"
        );
        client.put(&path, &body).await?;
    }
    Ok(())
}

/// PUT clients under the OAuth profile's client-store/config-backed.
///
/// **Endpoint shape (UNVERIFIED — needs licensed-Curity smoke):**
///
/// ```text
/// PUT {RESTCONF_BASE}/se.curity:profiles/profile=<profile-id>/se.curity.profile.oauth:settings/client-store/config-backed/client=<client-id>
///   Body: { "se.curity.profile.oauth:client": { "id": ..., ... } }
/// ```
///
/// Like scopes, entries default to the token-service profile.
async fn ensure_clients(client: &CurityClient, value: &Value) -> Result<()> {
    let Some(seq) = value.as_sequence() else {
        return Err("state.yaml::clients must be a list".into());
    };
    if seq.is_empty() {
        eprintln!("[curity-api-config] ensure: clients section empty — nothing to do");
        return Ok(());
    }
    eprintln!("[curity-api-config] ensure_clients: {} client(s)", seq.len());
    let profile_id = "token-service";
    for entry in seq {
        let id = require_string(entry, "id", "client entry")?;
        let body = serde_json::json!({
            "se.curity.profile.oauth:client": yaml_to_json(entry)?
        });
        let path = format!(
            "/se.curity:profiles/profile={profile_id}/se.curity.profile.oauth:settings/client-store/config-backed/client={id}"
        );
        client.put(&path, &body).await?;
    }
    Ok(())
}

/// PUT roles under the authorization-manager's configuration-based
/// store.
///
/// **Endpoint shape (UNVERIFIED — needs licensed-Curity smoke):**
///
/// ```text
/// PUT {RESTCONF_BASE}/se.curity:authorization-manager/se.curity.authorization-manager.configuration-based:configuration-based/role=<role-id>
///   Body: { "se.curity.authorization-manager.configuration-based:role": { "id": ... } }
/// ```
async fn ensure_roles(client: &CurityClient, value: &Value) -> Result<()> {
    let Some(seq) = value.as_sequence() else {
        return Err("state.yaml::roles must be a list".into());
    };
    if seq.is_empty() {
        eprintln!("[curity-api-config] ensure: roles section empty — nothing to do");
        return Ok(());
    }
    eprintln!("[curity-api-config] ensure_roles: {} role(s)", seq.len());
    for entry in seq {
        let id = require_string(entry, "id", "role entry")?;
        let body = serde_json::json!({
            "se.curity.authorization-manager.configuration-based:role": yaml_to_json(entry)?
        });
        let path = format!(
            "/se.curity:authorization-manager/se.curity.authorization-manager.configuration-based:configuration-based/role={id}"
        );
        client.put(&path, &body).await?;
    }
    Ok(())
}

/// Convert a `serde_yml::Value` to a `serde_json::Value`. The two
/// libraries don't share a Value type, but their tag shapes align
/// 1-to-1 for everything except YAML-specific kinds (tagged values,
/// merge keys) — those are an error here because they'd serialize to
/// JSON that Curity wouldn't understand.
fn yaml_to_json(value: &Value) -> Result<serde_json::Value> {
    match value {
        Value::Null => Ok(serde_json::Value::Null),
        Value::Bool(b) => Ok(serde_json::Value::Bool(*b)),
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Ok(serde_json::Value::Number(i.into()))
            } else if let Some(u) = n.as_u64() {
                Ok(serde_json::Value::Number(u.into()))
            } else if let Some(f) = n.as_f64() {
                serde_json::Number::from_f64(f)
                    .map(serde_json::Value::Number)
                    .ok_or_else(|| {
                        format!("YAML number `{f}` doesn't fit in a JSON number").into()
                    })
            } else {
                Err(format!("YAML number `{n:?}` has no usable representation").into())
            }
        }
        Value::String(s) => Ok(serde_json::Value::String(s.clone())),
        Value::Sequence(seq) => {
            let items: std::result::Result<Vec<_>, _> = seq.iter().map(yaml_to_json).collect();
            Ok(serde_json::Value::Array(items?))
        }
        Value::Mapping(map) => {
            let mut out = serde_json::Map::new();
            for (k, v) in map {
                let key = k.as_str().ok_or_else(|| {
                    "YAML mapping key isn't a string — JSON only supports string keys".to_string()
                })?;
                out.insert(key.to_string(), yaml_to_json(v)?);
            }
            Ok(serde_json::Value::Object(out))
        }
        Value::Tagged(_) => Err(
            "YAML tagged values (`!Tag value`) aren't supported in state.yaml — Curity's RESTCONF accepts plain JSON only.".into(),
        ),
    }
}

/// Extract a required string field from a YAML map entry, with an
/// actionable error if it's missing or the wrong shape.
fn require_string(entry: &Value, field: &str, context: &str) -> Result<String> {
    let s = entry
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("{context} is missing required `{field}` (string)"))?;
    if s.is_empty() {
        return Err(format!("{context}'s `{field}` is empty").into());
    }
    Ok(s.to_string())
}

/// Shared HTTPS-to-Curity-admin client. Carries the resolved base URL
/// (`https://<host>:<port>`) and credentials so per-call sites stay
/// focused on the URL + body.
struct CurityClient {
    http: reqwest::Client,
    base_url: String,
    /// Operator-set username (peer.username). Always tried first.
    operator_user: String,
    /// Operator-set password (peer.password). Always tried first.
    operator_pass: String,
}

impl CurityClient {
    fn build(host: String, port: u16) -> Result<Self> {
        let http = reqwest::Client::builder()
            .danger_accept_invalid_certs(true)
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .map_err(|e| format!("build reqwest client: {e}"))?;
        Ok(Self {
            http,
            base_url: format!("https://{host}:{port}"),
            operator_user: String::new(),
            operator_pass: String::new(),
        })
    }

    fn from_peer(peer: &PeerData) -> Result<Self> {
        let host = peer.host()?;
        let port = peer.port()?;
        let operator_user = peer.read("username")?.trim().to_string();
        let operator_pass = peer.read("password")?.trim().to_string();
        let mut client = Self::build(host, port)?;
        client.operator_user = operator_user;
        client.operator_pass = operator_pass;
        Ok(client)
    }

    /// PUT to a path under the RESTCONF base. Tries operator creds
    /// first; on 401 retries with bootstrap admin/admin. On 4xx/5xx
    /// surfaces both the URL and the Curity response body so live
    /// iteration can see both sides of the wire.
    async fn put(&self, path: &str, body: &serde_json::Value) -> Result<()> {
        let url = format!("{}{}{}", self.base_url, RESTCONF_BASE, path);
        eprintln!("[curity-api-config] PUT {url}");

        let res = self
            .http
            .put(&url)
            .basic_auth(&self.operator_user, Some(&self.operator_pass))
            .header(
                reqwest::header::CONTENT_TYPE,
                "application/yang-data+json",
            )
            .json(body)
            .send()
            .await
            .map_err(|e| format!("PUT {url} (operator creds): {e}"))?;
        let status = res.status();
        if status.is_success() {
            return Ok(());
        }
        if status.as_u16() == 401 {
            eprintln!("[curity-api-config] PUT {url}: operator creds rejected (401); retrying with bootstrap admin/admin");
            let res = self
                .http
                .put(&url)
                .basic_auth(BOOTSTRAP_USER, Some(BOOTSTRAP_PASS))
                .header(
                    reqwest::header::CONTENT_TYPE,
                    "application/yang-data+json",
                )
                .json(body)
                .send()
                .await
                .map_err(|e| format!("PUT {url} (bootstrap creds): {e}"))?;
            let bs_status = res.status();
            if bs_status.is_success() {
                return Ok(());
            }
            let bs_body = res.text().await.unwrap_or_default();
            return Err(format!(
                "PUT {url} returned {bs_status} on bootstrap creds retry.\n  Request body: {}\n  Response body: {bs_body}",
                serde_json::to_string(body).unwrap_or_default()
            )
            .into());
        }
        let body_text = res.text().await.unwrap_or_default();
        Err(format!(
            "PUT {url} returned {status}.\n  Request body: {}\n  Response body: {body_text}",
            serde_json::to_string(body).unwrap_or_default()
        )
        .into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn yaml_to_json_roundtrips_state_yaml_shape() {
        let yaml: Value = serde_yml::from_str(
            r#"id: flutter-app
type: public
grant-types: [authorization-code]
pkce:
  required: true
redirect-uris: ["bluetext://callback"]
scope: [openid, profile, email]
"#,
        )
        .unwrap();
        let json = yaml_to_json(&yaml).unwrap();
        assert_eq!(json["id"], "flutter-app");
        assert_eq!(json["type"], "public");
        assert_eq!(json["grant-types"][0], "authorization-code");
        assert_eq!(json["pkce"]["required"], true);
        assert_eq!(json["redirect-uris"][0], "bluetext://callback");
        assert_eq!(json["scope"][2], "email");
    }

    #[test]
    fn yaml_to_json_preserves_integer_kind() {
        let yaml: Value = serde_yml::from_str("ttl: 3600").unwrap();
        let json = yaml_to_json(&yaml).unwrap();
        assert_eq!(json["ttl"], 3600);
        assert!(json["ttl"].is_i64() || json["ttl"].is_u64());
    }

    #[test]
    fn yaml_to_json_rejects_non_string_map_keys() {
        let yaml: Value = serde_yml::from_str("1: value").unwrap();
        let err = yaml_to_json(&yaml).unwrap_err().to_string();
        assert!(err.contains("string keys"), "got: {err}");
    }

    #[test]
    fn require_string_errors_when_field_missing() {
        let yaml: Value = serde_yml::from_str("type: public").unwrap();
        let err = require_string(&yaml, "id", "test").unwrap_err().to_string();
        assert!(err.contains("missing required `id`"), "got: {err}");
    }

    #[test]
    fn require_string_errors_when_field_empty() {
        let yaml: Value = serde_yml::from_str("id: ''").unwrap();
        let err = require_string(&yaml, "id", "test").unwrap_err().to_string();
        assert!(err.contains("is empty"), "got: {err}");
    }

    #[test]
    fn known_state_keys_match_handler_dispatch() {
        // Hard invariant: every key in KNOWN_STATE_KEYS must have a
        // dispatch arm in ensure_state. If you add a key here without
        // a matching match arm in ensure_state, the unreachable!() in
        // the dispatch will fire at runtime. This test forces the
        // pair to be edited together.
        for key in KNOWN_STATE_KEYS {
            assert!(
                matches!(*key, "license" | "oauth-profiles" | "scopes" | "clients" | "roles"),
                "KNOWN_STATE_KEYS has `{key}` with no matching dispatch arm in ensure_state"
            );
        }
    }
}
