export interface Node {
  name: string;
  status: "Ready" | "NotReady";
  roles: string;
}

export interface Pod {
  name: string;
  namespace: string;
  status: string;
  ready: string;
  restarts: number;
  age: string;
}

export interface Service {
  name: string;
  namespace: string;
  type: string;
  clusterIP: string;
  ports: string;
}

export interface Ingress {
  name: string;
  namespace: string;
  hosts: string[];
}

export interface Deployment {
  name: string;
  namespace: string;
  ready: number;
  desired: number;
}

export interface ClusterStatus {
  timestamp: string;
  cluster: { reachable: boolean; nodes: Node[] };
  namespaces: string[];
  pods: Pod[];
  services: Service[];
  ingresses: Ingress[];
  deployments: Deployment[];
}

export interface ServiceConfig {
  id: string;
  targetPort: number;
  requiresCli: boolean;
  hasImage: boolean;
}
