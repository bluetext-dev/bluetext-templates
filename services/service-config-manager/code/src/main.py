#!/usr/bin/env python3

import os
import sys
import yaml
import time
import socket
from pathlib import Path
from config import Config
from controllers.couchbase_controller import CouchbaseController
from controllers.redpanda_controller import RedpandaController
from controllers.postgres_controller import PostgresController
from controllers.couchbase_sync_gateway_controller import CouchbaseSyncGatewayController
from utils.logger import get_logger

def get_env_var(name, default=None):
    """Get environment variable with optional default."""
    try:
        if default is not None:
            return os.environ.get(name, default)
        else:
            return os.environ[name]
    except KeyError:
        raise KeyError(f"Environment variable '{name}' is not set")

def is_service_reachable(service_name):
    """Check if a service's host resolves via DNS. Returns True if the host
    is found, False if the env var is missing or the hostname doesn't resolve."""
    prefix = service_name.upper().replace('-', '_')
    host = os.environ.get(f'{prefix}_HOST')
    if not host:
        return False
    try:
        socket.getaddrinfo(host, None)
        return True
    except socket.gaierror:
        return False

def main():
    """Main entry point for the init module."""
    logger = get_logger('config-manager')

    logger.info("🚀 Starting config-manager...")

    try:
        # Get environment variables
        environment = get_env_var('ENVIRONMENT')
        logger.info(f"📊 Environment: {environment}")

        # Initialize config manager with config directory
        config_dir = Path('/config')
        logger.info(f"📁 Config directory: {config_dir}")

        config = Config(config_dir, environment)

        # Load managed services from manifest
        services = config.load_managed_services()

        if not services:
            logger.warning("⚠️  No managed services found in manifest (config/service-config-manager/managed-services.yaml)")

        if services:
            logger.info(f"🎯 Found {len(services)} managed service(s)")

        # Process each managed service
        processed_count = 0
        failed_count = 0
        skipped_count = 0

        for service in services:
            name = service.get('name')
            service_type = service.get('type')
            config_dir = service.get('config_dir')

            if not name or not service_type or not config_dir:
                logger.error(f"❌ Invalid service definition: {service}")
                failed_count += 1
                continue

            if not is_service_reachable(name):
                logger.info(f"⏭️  Skipping {name} — host not found in cluster")
                skipped_count += 1
                continue

            logger.info(f"🔄 Processing {name} ({service_type})...")

            try:
                if service_type == 'couchbase':
                    controller = CouchbaseController(environment, config, name, config_dir)
                    controller.run_ops()
                elif service_type == 'redpanda':
                    controller = RedpandaController(environment, config, name, config_dir)
                    controller.run_ops()
                elif service_type == 'postgres':
                    controller = PostgresController(environment, config, name, config_dir)
                    controller.run_ops()
                elif service_type == 'couchbase-sync-gateway':
                    controller = CouchbaseSyncGatewayController(environment, config, name, config_dir)
                    controller.run_ops()
                else:
                    logger.warning(f"⚠️ Unknown service type: {service_type}")
                    failed_count += 1
                    continue

                processed_count += 1
                logger.info(f"✅ {name} processing completed")

            except Exception as e:
                logger.error(f"❌ Failed to process {name}: {e}")
                logger.exception("Stack trace:")
                failed_count += 1

        if services:
            logger.info(f"🎉 Configuration processing completed! Success: {processed_count}, Skipped: {skipped_count}, Failed: {failed_count}")
            if failed_count > 0:
                sys.exit(1)
        else:
            logger.info("ℹ️  No services to process.")

    except Exception as e:
        logger.error(f"💥 Fatal error in config-manager: {e}")
        logger.exception("Stack trace:")
        sys.exit(1)

if __name__ == "__main__":
    main()
