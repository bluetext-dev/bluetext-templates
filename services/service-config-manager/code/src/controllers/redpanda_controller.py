from utils.logger import get_logger


class RedpandaController:
    """Controls Redpanda topic and configuration operations."""

    def __init__(self, environment, config, service_name, config_dir):
        self.environment = environment
        self.config = config
        self.service_name = service_name
        self.config_dir = config_dir
        self.logger = get_logger(f'redpanda-{service_name}')

    def run_ops(self):
        self.logger.warning("Redpanda controller not yet implemented")
