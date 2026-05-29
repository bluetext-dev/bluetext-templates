use couchbase::authenticator::PasswordAuthenticator;
use couchbase::cluster::Cluster;
use couchbase::collection::Collection;
use couchbase::error::ErrorKind;
use couchbase::options::cluster_options::ClusterOptions;
use futures_util::TryStreamExt;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::OnceLock;
use tokio::sync::Mutex;
use uuid::Uuid;

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct CouchbaseConf {
    /// Full connection URL including protocol (e.g. `couchbase://host`,
    /// `couchbases://host:18091`). The macro-generated `__connect()` and
    /// this client library both read this as a single opaque string.
    pub url: String,
    pub username: String,
    pub password: String,
    pub bucket: String,
}

impl CouchbaseConf {
    /// Build config from the link mount the deploy pipeline projects at
    /// `/etc/bluetext/links/<link>/`.
    ///
    /// `b service link <consumer> couchbase/<profile>` places `host`,
    /// `port`, `protocol`, and the credential files (`username`,
    /// `password`) there; this reads them. `host` / `username` /
    /// `password` are required — a missing file fails loud (no silent
    /// fallback), because it means this service isn't linked to couchbase.
    /// `bucket` defaults to "default" (the bucket
    /// `api-config/couchbase/base/state.yaml` provisions on first deploy),
    /// overridable with an optional `bucket` file in the mount.
    pub fn from_link(link_name: &str) -> Result<Self, String> {
        let dir = std::path::Path::new("/etc/bluetext/links").join(link_name);
        let read = |file: &str| -> Result<String, String> {
            std::fs::read_to_string(dir.join(file))
                .map(|s| s.trim().to_string())
                .map_err(|_| {
                    format!(
                        "Couchbase link mount file {}/{file} is missing — is this service linked to \
                         couchbase? Run `b service link <this-service> couchbase/<profile>`.",
                        dir.display()
                    )
                })
        };

        let host = read("host")?;
        let protocol = read("protocol").unwrap_or_else(|_| "couchbase".to_string());
        let username = read("username")?;
        let password = read("password")?;
        let bucket = read("bucket").unwrap_or_else(|_| "default".to_string());

        Ok(Self {
            url: format!("{protocol}://{host}"),
            username,
            password,
            bucket,
        })
    }
}

// ---------------------------------------------------------------------------
// Client registry
// ---------------------------------------------------------------------------

static CLIENTS: OnceLock<Mutex<HashMap<String, CouchbaseClient>>> = OnceLock::new();

fn registry() -> &'static Mutex<HashMap<String, CouchbaseClient>> {
    CLIENTS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Register a client under a given name with explicit config.
pub async fn register_client(name: &str, conf: CouchbaseConf) -> Result<(), String> {
    let client = CouchbaseClient::new(conf).await?;
    registry().lock().await.insert(name.to_string(), client);
    Ok(())
}

/// Get a client for a link. Auto-connects by reading the link mount at
/// `/etc/bluetext/links/<link_name>/` if not already registered.
pub async fn get_client(link_name: &str) -> Result<CouchbaseClient, String> {
    let mut clients = registry().lock().await;
    if let Some(client) = clients.get(link_name) {
        return Ok(client.clone());
    }
    let conf = CouchbaseConf::from_link(link_name)?;
    let client = CouchbaseClient::new(conf).await?;
    clients.insert(link_name.to_string(), client.clone());
    Ok(client)
}

// ---------------------------------------------------------------------------
// CouchbaseClient
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct CouchbaseClient {
    pub conf: CouchbaseConf,
    cluster: Cluster,
}

impl std::fmt::Debug for CouchbaseClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CouchbaseClient")
            .field("conf", &self.conf)
            .finish()
    }
}

impl CouchbaseClient {
    pub async fn new(conf: CouchbaseConf) -> Result<Self, String> {
        let auth = PasswordAuthenticator::new(&conf.username, &conf.password);
        let opts = ClusterOptions::new(auth.into());
        let cluster = Cluster::connect(&conf.url, opts)
            .await
            .map_err(|e| format!("Failed to connect to Couchbase cluster at {}: {e}", conf.url))?;
        Ok(Self { conf, cluster })
    }

    /// Ensure a collection exists, creating it if necessary.
    pub async fn ensure_collection_exists(
        &self,
        collection_name: &str,
        scope_name: Option<&str>,
        bucket_name: Option<&str>,
    ) {
        let bucket_name = bucket_name.unwrap_or(&self.conf.bucket);
        let scope_name = scope_name.unwrap_or("_default");
        let bucket = self.cluster.bucket(bucket_name);
        let mgr = bucket.collections();
        match mgr
            .create_collection(scope_name, collection_name, None, None)
            .await
        {
            Ok(_) => println!(
                "Created collection {collection_name} in scope {scope_name} of bucket {bucket_name}"
            ),
            Err(e) if matches!(e.kind(), ErrorKind::CollectionExists) => {}
            Err(e) => eprintln!(
                "Warning: Could not create collection '{collection_name}': {e}"
            ),
        }
    }

    /// Get a Keyspace bound to this client.
    pub async fn get_keyspace(
        &self,
        collection_name: &str,
        scope_name: Option<&str>,
        bucket_name: Option<&str>,
    ) -> Keyspace {
        let bucket_name = bucket_name.unwrap_or(&self.conf.bucket);
        let scope_name = scope_name.unwrap_or("_default");
        self.ensure_collection_exists(collection_name, Some(scope_name), Some(bucket_name))
            .await;
        Keyspace {
            bucket_name: bucket_name.to_string(),
            scope_name: scope_name.to_string(),
            collection_name: collection_name.to_string(),
            client: self.clone(),
        }
    }

    /// Health check — ping the cluster.
    pub async fn health_check(&self) -> Result<(), String> {
        let bucket = self.cluster.bucket(&self.conf.bucket);
        bucket
            .ping(None)
            .await
            .map(|_| ())
            .map_err(|e| format!("Couchbase health check failed: {e}"))
    }

    pub fn cluster(&self) -> &Cluster {
        &self.cluster
    }
}

// ---------------------------------------------------------------------------
// Keyspace
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct Keyspace {
    pub bucket_name: String,
    pub scope_name: String,
    pub collection_name: String,
    pub client: CouchbaseClient,
}

impl std::fmt::Display for Keyspace {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "`{}`.`{}`.`{}`",
            self.bucket_name, self.scope_name, self.collection_name
        )
    }
}

impl Keyspace {
    /// Parse a "bucket.scope.collection" string into a Keyspace.
    pub fn from_string(keyspace: &str, client: CouchbaseClient) -> Result<Self, String> {
        let parts: Vec<&str> = keyspace.split('.').collect();
        if parts.len() != 3 {
            return Err(format!(
                "Invalid keyspace format. Expected 'bucket.scope.collection', got '{keyspace}'"
            ));
        }
        Ok(Self {
            bucket_name: parts[0].to_string(),
            scope_name: parts[1].to_string(),
            collection_name: parts[2].to_string(),
            client,
        })
    }

    /// Execute a N1QL query with `${keyspace}` substitution.
    pub async fn query(&self, query: &str) -> Result<Vec<serde_json::Value>, String> {
        let query = query.replace("${keyspace}", &self.to_string());
        let cluster = self.client.cluster();
        let mut result = cluster
            .query(&query, None)
            .await
            .map_err(|e| format!("Query failed: {e}"))?;
        let rows: Vec<serde_json::Value> = result
            .rows::<serde_json::Value>()
            .try_collect()
            .await
            .map_err(|e| format!("Failed to collect query rows: {e}"))?;
        Ok(rows)
    }

    /// Get the Couchbase Collection object.
    pub fn get_collection(&self) -> Collection {
        let bucket = self.client.cluster().bucket(&self.bucket_name);
        let scope = bucket.scope(&self.scope_name);
        scope.collection(&self.collection_name)
    }

    /// Insert a document. Auto-generates a UUID key if none provided.
    pub async fn insert<V: Serialize>(
        &self,
        value: &V,
        key: Option<&str>,
    ) -> Result<String, String> {
        let id = match key {
            Some(k) => k.to_string(),
            None => Uuid::new_v4().to_string(),
        };
        let collection = self.get_collection();
        collection
            .insert(&id, value, None)
            .await
            .map_err(|e| format!("Insert failed: {e}"))?;
        Ok(id)
    }

    /// Remove a document by key.
    pub async fn remove(&self, key: &str) -> Result<(), String> {
        let collection = self.get_collection();
        collection
            .remove(key, None)
            .await
            .map_err(|e| format!("Remove failed: {e}"))?;
        Ok(())
    }

    /// List all documents with an optional limit.
    pub async fn list(&self, limit: Option<u32>) -> Result<Vec<serde_json::Value>, String> {
        let limit_clause = match limit {
            Some(n) => format!(" LIMIT {n}"),
            None => String::new(),
        };
        let query = format!("SELECT META().id, * FROM {self}{limit_clause}");
        self.query(&query).await
    }
}

// ---------------------------------------------------------------------------
// Document wrapper
// ---------------------------------------------------------------------------

/// Stored document: wraps user data with an id field.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Document<T> {
    pub id: String,
    pub data: T,
}

// ---------------------------------------------------------------------------
// Entity trait — the Rust equivalent of BaseModelCouchbase
// ---------------------------------------------------------------------------

/// Implement this trait on your entity types to get CRUD operations.
///
/// # Example
///
/// Implement `Entity` on a struct **local to your crate**, with
/// `type Data = Self`. Do *not* write `impl Entity for Document<YourData>`
/// (e.g. via `pub type Task = Document<TaskData>`): both `Entity` and
/// `Document` live in this crate, so that violates Rust's orphan rule and
/// will not compile in a consumer crate.
///
/// ```ignore
/// use serde::{Deserialize, Serialize};
/// use clients::couchbase::{Document, Entity};
///
/// #[derive(Debug, Clone, Serialize, Deserialize)]
/// pub struct Task {
///     pub title: String,
///     pub done: bool,
/// }
///
/// impl Entity for Task {
///     type Data = Self;
///     fn collection_name() -> &'static str { "tasks" }
///     // link_name() defaults to "couchbase" — the connection is read from
///     // the link mount at /etc/bluetext/links/couchbase/. Override only if
///     // you linked couchbase under a different alias (`b service link -l`).
/// }
///
/// // CRUD then yields `Document<Task>`:
/// //   let created: Document<Task>      = Task::create(task).await?;
/// //   let all:     Vec<Document<Task>> = Task::list(None).await?;
/// ```
pub trait Entity: Sized {
    type Data: Serialize + DeserializeOwned + Clone;

    /// The Couchbase collection name for this entity.
    fn collection_name() -> &'static str;

    /// The link this entity reads its connection from — the alias on the
    /// consumer's `links:`, mounted at `/etc/bluetext/links/<link>/`.
    /// Defaults to "couchbase" (the link name `b service link` uses when
    /// no `-l <name>` is given). Override only if you linked couchbase
    /// under a different alias.
    fn link_name() -> &'static str {
        "couchbase"
    }

    /// Get the Keyspace for this entity.
    async fn get_keyspace() -> Result<Keyspace, String> {
        let client = get_client(Self::link_name()).await?;
        Ok(client
            .get_keyspace(Self::collection_name(), None, None)
            .await)
    }

    /// Create a new document with an auto-generated UUID.
    async fn create(data: Self::Data) -> Result<Document<Self::Data>, String> {
        let id = Uuid::new_v4().to_string();
        let keyspace = Self::get_keyspace().await?;
        let collection = keyspace.get_collection();
        collection
            .upsert(&id, &data, None)
            .await
            .map_err(|e| format!("Create failed: {e}"))?;
        Ok(Document { id, data })
    }

    /// Get a document by id. Returns None if not found.
    async fn get(id: &str) -> Result<Option<Document<Self::Data>>, String> {
        let keyspace = Self::get_keyspace().await?;
        let collection = keyspace.get_collection();
        match collection.get(id, None).await {
            Ok(result) => {
                let data: Self::Data = result
                    .content_as()
                    .map_err(|e| format!("Deserialization failed: {e}"))?;
                Ok(Some(Document {
                    id: id.to_string(),
                    data,
                }))
            }
            Err(e) if matches!(e.kind(), ErrorKind::DocumentNotFound) => Ok(None),
            Err(e) => Err(format!("Get failed: {e}")),
        }
    }

    /// List all documents, with an optional limit.
    async fn list(limit: Option<u32>) -> Result<Vec<Document<Self::Data>>, String> {
        let keyspace = Self::get_keyspace().await?;
        let rows = keyspace.list(limit).await?;
        let mut items = Vec::new();
        for row in rows {
            let id = row
                .get("id")
                .and_then(|v| v.as_str())
                .ok_or("Missing id in query row")?
                .to_string();
            let data_value = row
                .get(Self::collection_name())
                .ok_or("Missing collection data in query row")?;
            let data: Self::Data = serde_json::from_value(data_value.clone())
                .map_err(|e| format!("Deserialization failed: {e}"))?;
            items.push(Document { id, data });
        }
        Ok(items)
    }

    /// Update (replace) an existing document.
    async fn update(doc: &Document<Self::Data>) -> Result<Document<Self::Data>, String> {
        let keyspace = Self::get_keyspace().await?;
        let collection = keyspace.get_collection();
        collection
            .replace(&doc.id, &doc.data, None)
            .await
            .map_err(|e| format!("Update failed: {e}"))?;
        Ok(doc.clone())
    }

    /// Delete a document by id. Returns true if successful.
    async fn delete(id: &str) -> Result<bool, String> {
        let keyspace = Self::get_keyspace().await?;
        let collection = keyspace.get_collection();
        match collection.remove(id, None).await {
            Ok(_) => Ok(true),
            Err(e) if matches!(e.kind(), ErrorKind::DocumentNotFound) => Ok(false),
            Err(e) => Err(format!("Delete failed: {e}")),
        }
    }

    /// Get multiple documents by their IDs.
    async fn get_many(ids: &[String]) -> Result<Vec<Document<Self::Data>>, String> {
        let keyspace = Self::get_keyspace().await?;
        let keys_str = ids
            .iter()
            .map(|k| format!("\"{k}\""))
            .collect::<Vec<_>>()
            .join(", ");
        let query = format!(
            "SELECT META().id, * FROM {} USE KEYS [{keys_str}]",
            keyspace
        );
        let rows = keyspace.query(&query).await?;
        let mut items = Vec::new();
        for row in rows {
            let id = row
                .get("id")
                .and_then(|v| v.as_str())
                .ok_or("Missing id in query row")?
                .to_string();
            let data_value = row
                .get(Self::collection_name())
                .ok_or("Missing collection data in query row")?;
            let data: Self::Data = serde_json::from_value(data_value.clone())
                .map_err(|e| format!("Deserialization failed: {e}"))?;
            items.push(Document { id, data });
        }
        Ok(items)
    }

    /// Create multiple documents at once.
    async fn create_many(items: &[Self::Data]) -> Result<Vec<Document<Self::Data>>, String> {
        let keyspace = Self::get_keyspace().await?;
        let collection = keyspace.get_collection();
        let mut results = Vec::new();
        for data in items {
            let id = Uuid::new_v4().to_string();
            collection
                .upsert(&id, data, None)
                .await
                .map_err(|e| format!("Create failed: {e}"))?;
            results.push(Document {
                id,
                data: data.clone(),
            });
        }
        Ok(results)
    }

    /// Update multiple documents at once.
    async fn update_many(
        docs: &[Document<Self::Data>],
    ) -> Result<Vec<Document<Self::Data>>, String> {
        let keyspace = Self::get_keyspace().await?;
        let collection = keyspace.get_collection();
        let mut results = Vec::new();
        for doc in docs {
            collection
                .replace(&doc.id, &doc.data, None)
                .await
                .map_err(|e| format!("Update failed: {e}"))?;
            results.push(doc.clone());
        }
        Ok(results)
    }

    /// Delete multiple documents by their IDs. Returns the IDs that were successfully deleted.
    async fn delete_many(ids: &[String]) -> Result<Vec<String>, String> {
        let keyspace = Self::get_keyspace().await?;
        let collection = keyspace.get_collection();
        let mut deleted = Vec::new();
        for id in ids {
            match collection.remove(id.as_str(), None).await {
                Ok(_) => deleted.push(id.clone()),
                Err(e) if matches!(e.kind(), ErrorKind::DocumentNotFound) => {}
                Err(e) => return Err(format!("Delete failed for '{id}': {e}")),
            }
        }
        Ok(deleted)
    }
}
