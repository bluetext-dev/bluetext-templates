// Re-export the unofficial TigerBeetle Rust client so consumers don't
// need to add the dep themselves. The wrapper layer below is the only
// place that translates between bluetext conventions (env-var prefixes,
// hostname-based addresses) and what the C client expects (a literal
// `ip:port` list, no DNS).
pub use tigerbeetle_unofficial as tb;

// Configuration read from environment variables. `b service wire` injects
// these; the defaults match what the templated tigerbeetle service ships
// so a freshly-scaffolded system works without any wiring.
#[derive(Debug, Clone)]
pub struct TigerbeetleConf {
    /// Comma-separated `host:port` list. The connect helper resolves
    /// each entry through DNS before handing literal IPs to the C
    /// client, which does no name resolution itself.
    pub addresses: String,
    pub cluster_id: u128,
}

impl TigerbeetleConf {
    /// Read config from `<PREFIX>_ADDRESSES` and `<PREFIX>_CLUSTER_ID`.
    /// Both have defaults so a system that hasn't run `b client configure`
    /// against tigerbeetle still gets a working client when the
    /// templated tigerbeetle service is in the run-spec under id `tigerbeetle`.
    pub fn from_env(prefix: &str) -> Self {
        let prefix = prefix.to_uppercase().replace('-', "_");
        Self {
            addresses: std::env::var(format!("{prefix}_ADDRESSES"))
                .unwrap_or_else(|_| "tigerbeetle:3000".to_string()),
            cluster_id: std::env::var(format!("{prefix}_CLUSTER_ID"))
                .ok()
                .and_then(|s| s.parse().ok())
                .unwrap_or(0),
        }
    }
}

// Resolve every comma-separated entry through DNS. Picks IPv4 first
// because TigerBeetle's wire protocol expects the v4 form.
async fn resolve(addresses: &str) -> Result<String, String> {
    let mut out = Vec::new();
    for entry in addresses.split(',') {
        let entry = entry.trim();
        if entry.is_empty() {
            continue;
        }
        let candidates: Vec<_> = tokio::net::lookup_host(entry)
            .await
            .map_err(|e| format!("dns lookup `{entry}` failed: {e}"))?
            .collect();
        let chosen = candidates
            .iter()
            .find(|a| a.is_ipv4())
            .or_else(|| candidates.first())
            .ok_or_else(|| format!("no addresses for `{entry}`"))?;
        out.push(format!("{}:{}", chosen.ip(), chosen.port()));
    }
    if out.is_empty() {
        return Err(format!("TIGERBEETLE addresses empty after resolution: `{addresses}`"));
    }
    Ok(out.join(","))
}

/// Connect to TigerBeetle using the given config. Resolves hostnames
/// before constructing the client.
pub async fn connect_client(conf: &TigerbeetleConf) -> Result<tb::Client, String> {
    let resolved = resolve(&conf.addresses).await?;
    tb::Client::new(conf.cluster_id, resolved.as_str())
        .map_err(|e| format!("tigerbeetle connect failed ({resolved}): {e}"))
}

/// Convenience: read config from env (using `prefix`, e.g. `"TIGERBEETLE"`)
/// and connect.
pub async fn connect_from_env(prefix: &str) -> Result<tb::Client, String> {
    let conf = TigerbeetleConf::from_env(prefix);
    connect_client(&conf).await
}
