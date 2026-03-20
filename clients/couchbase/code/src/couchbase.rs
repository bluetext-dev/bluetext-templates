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
    pub host: String,
    pub username: String,
    pub password: String,
    pub bucket: String,
    pub protocol: String,
}

impl CouchbaseConf {
    /// Build config from environment variables using a prefix.
    /// e.g. prefix "COUCHBASE" reads COUCHBASE_HOST, COUCHBASE_USERNAME, etc.
    ///
    /// HOST, USERNAME, and PASSWORD are required. PROTOCOL and BUCKET have
    /// sensible defaults ("couchbase" and "main" respectively).
    pub fn from_env(prefix: &str) -> Result<Self, String> {
        let prefix = prefix.to_uppercase().replace('-', "_");

        let host = std::env::var(format!("{prefix}_HOST")).map_err(|_| {
            format!("Required env var {prefix}_HOST is not set. Run 'b client configure' to inject it.")
        })?;
        let username = std::env::var(format!("{prefix}_USERNAME")).map_err(|_| {
            format!("Required env var {prefix}_USERNAME is not set. Run 'b client configure' to inject it.")
        })?;
        let password = std::env::var(format!("{prefix}_PASSWORD")).map_err(|_| {
            format!("Required env var {prefix}_PASSWORD is not set. Run 'b client configure' to inject it.")
        })?;
        let bucket =
            std::env::var(format!("{prefix}_BUCKET")).unwrap_or_else(|_| "main".into());
        let protocol =
            std::env::var(format!("{prefix}_PROTOCOL")).unwrap_or_else(|_| "couchbase".into());

        Ok(Self {
            host,
            username,
            password,
            bucket,
            protocol,
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

/// Get a client by name. Auto-registers from env vars using `prefix` if not found.
pub async fn get_client(name: &str, prefix: &str) -> Result<CouchbaseClient, String> {
    let mut clients = registry().lock().await;
    if let Some(client) = clients.get(name) {
        return Ok(client.clone());
    }
    let conf = CouchbaseConf::from_env(prefix)?;
    let client = CouchbaseClient::new(conf).await?;
    clients.insert(name.to_string(), client.clone());
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
        let url = format!("{}://{}", conf.protocol, conf.host);
        let auth = PasswordAuthenticator::new(&conf.username, &conf.password);
        let opts = ClusterOptions::new(auth.into());
        let cluster = Cluster::connect(&url, opts)
            .await
            .map_err(|e| format!("Failed to connect to Couchbase cluster at {url}: {e}"))?;
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
/// ```ignore
/// use serde::{Deserialize, Serialize};
/// use clients::couchbase::{Document, Entity};
///
/// #[derive(Debug, Clone, Serialize, Deserialize)]
/// pub struct TaskData {
///     pub title: String,
///     pub done: bool,
/// }
///
/// pub type Task = Document<TaskData>;
///
/// impl Entity for Task {
///     type Data = TaskData;
///     fn collection_name() -> &'static str { "tasks" }
///     fn service_instance() -> &'static str { "couchbase" }
///     fn env_prefix() -> &'static str { "COUCHBASE" }
/// }
/// ```
pub trait Entity: Sized {
    type Data: Serialize + DeserializeOwned + Clone;

    /// The Couchbase collection name for this entity.
    fn collection_name() -> &'static str;

    /// The service instance name used to look up the client in the registry.
    fn service_instance() -> &'static str {
        "couchbase"
    }

    /// The env var prefix used to configure this client instance.
    fn env_prefix() -> &'static str {
        "COUCHBASE"
    }

    /// Get the Keyspace for this entity.
    async fn get_keyspace() -> Result<Keyspace, String> {
        let client = get_client(Self::service_instance(), Self::env_prefix()).await?;
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
