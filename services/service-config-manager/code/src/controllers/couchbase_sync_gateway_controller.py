import os
import time
import json
import urllib.request
import urllib.error
from typing import Dict, Any, Optional
from config import Config
from utils.logger import get_logger


class CouchbaseSyncGatewayController:
    """Controls Couchbase Sync Gateway database configuration via the Admin API."""

    def __init__(self, environment: str, config: Config, service_name: str, config_dir: str):
        self.environment = environment
        self.config = config
        self.service_name = service_name
        self.config_dir = config_dir
        self.logger = get_logger(f'couchbase-sync-gateway-{service_name}')

        # Construct env var prefix from service name
        self.prefix = service_name.upper().replace('-', '_')

        self.logger.info(f"Initializing Sync Gateway controller for {service_name} (Env Prefix: {self.prefix})")

        self.host = self._get_env_var(f'{self.prefix}_HOST')
        self.admin_port = self._get_env_var(f'{self.prefix}_ADMIN_PORT', '4985')

    def _get_env_var(self, name: str, default: str = None) -> str:
        try:
            if default is not None:
                return os.environ.get(name, default)
            else:
                return os.environ[name]
        except KeyError:
            self.logger.error(f"Missing required environment variable: {name}")
            raise KeyError(f"Environment variable '{name}' is not set")

    @property
    def admin_url(self) -> str:
        return f"http://{self.host}:{self.admin_port}"

    def _request(self, method: str, path: str, body: Optional[Dict] = None) -> Optional[Dict]:
        """Make an HTTP request to the Sync Gateway Admin API."""
        url = f"{self.admin_url}{path}"
        data = json.dumps(body).encode() if body else None

        request = urllib.request.Request(url, data=data, method=method)
        request.add_header('Content-Type', 'application/json')

        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response_body = response.read().decode()
                if response_body:
                    return json.loads(response_body)
                return None
        except urllib.error.HTTPError as e:
            error_body = e.read().decode() if hasattr(e, 'read') else str(e)
            raise Exception(f"HTTP {e.code} on {method} {path}: {error_body}")

    def _test_connection(self) -> bool:
        """Test connectivity to Sync Gateway admin API."""
        try:
            url = f"{self.admin_url}/"
            request = urllib.request.Request(url, method='GET')
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.code == 200
        except Exception as e:
            self.logger.debug(f"Connection test failed: {e}")
            return False

    def _get_existing_databases(self) -> list:
        """Get list of existing databases."""
        try:
            result = self._request('GET', '/_all_dbs')
            return result if result else []
        except Exception as e:
            self.logger.warning(f"Failed to list databases: {e}")
            return []

    def _database_exists(self, db_name: str) -> bool:
        """Check if a database already exists."""
        existing = self._get_existing_databases()
        return db_name in existing

    def ensure_database(self, db_name: str, db_config: Dict[str, Any]) -> None:
        """Ensure a Sync Gateway database exists with the given configuration."""
        if self._database_exists(db_name):
            self.logger.info(f"Database '{db_name}' already exists")
            return

        self.logger.info(f"Creating database '{db_name}'...")

        # Build the database config payload
        payload = {
            'bucket': db_config.get('bucket', db_name),
            'num_index_replicas': db_config.get('num_index_replicas', 0),
        }

        # Pass through optional database settings
        if 'import_docs' in db_config:
            payload['import_docs'] = db_config['import_docs']
        if 'enable_shared_bucket_access' in db_config:
            payload['enable_shared_bucket_access'] = db_config['enable_shared_bucket_access']

        # Configure guest access
        guest_config = db_config.get('guest', {})
        if guest_config:
            payload['guest'] = {
                'disabled': guest_config.get('disabled', True),
            }
            admin_channels = guest_config.get('admin_channels')
            if admin_channels:
                payload['guest']['admin_channels'] = admin_channels

        # Configure users
        users_config = db_config.get('users', {})
        if users_config:
            payload['users'] = {}
            for username, user_settings in users_config.items():
                payload['users'][username] = {
                    'password': user_settings.get('password', ''),
                    'admin_channels': user_settings.get('admin_channels', []),
                    'disabled': user_settings.get('disabled', False),
                }

        self._request('PUT', f'/{db_name}/', payload)
        self.logger.info(f"Database '{db_name}' created successfully")

    def _load_sync_gateway_config(self) -> Dict[str, Any]:
        """Load Sync Gateway configuration from YAML file."""
        if self.config is None:
            raise ValueError("Config object is required but not provided")
        return self.config.load_service_config(self.config_dir, 'couchbase-sync-gateway')

    def run_ops(self) -> None:
        """Run all Sync Gateway configuration operations."""
        self.logger.info("Processing Sync Gateway resources...")

        # Wait for admin API to be reachable
        self.logger.info(f"Testing connection to Sync Gateway admin API at {self.admin_url}...")
        max_retries = 60
        for attempt in range(max_retries):
            if self._test_connection():
                break
            if attempt == max_retries - 1:
                raise Exception(
                    f"Failed to connect to Sync Gateway admin API at {self.admin_url} "
                    f"after {max_retries} attempts"
                )
            self.logger.debug(f"Connection test failed, retrying... (attempt {attempt + 1}/{max_retries})")
            time.sleep(2)

        self.logger.info("Connected to Sync Gateway admin API")

        # Load configuration
        sg_config = self._load_sync_gateway_config()
        if not sg_config:
            self.logger.warning("No Sync Gateway configuration found to process")
            return

        # Create databases
        databases = sg_config.get('databases', {})
        for db_name, db_config in databases.items():
            self.logger.info(f"Processing database: {db_name}")
            self.ensure_database(db_name, db_config)

        self.logger.info("Sync Gateway resources processed successfully")
