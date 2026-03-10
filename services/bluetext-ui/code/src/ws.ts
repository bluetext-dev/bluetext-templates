type Listener = (msg: Record<string, unknown>) => void;

let socket: WebSocket | null = null;
let listeners: Listener[] = [];
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let reqCounter = 0;

const HOST_AGENT_PORT = 16981;

function getUrl() {
  return `ws://localhost:${HOST_AGENT_PORT}/ws`;
}

function connect() {
  if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return;

  socket = new WebSocket(getUrl());

  socket.onopen = () => {
    console.log("[ws] connected to host agent");
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  };

  socket.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);
      for (const fn of listeners) fn(msg);
    } catch {}
  };

  socket.onclose = () => {
    console.log("[ws] disconnected, reconnecting...");
    socket = null;
    reconnectTimer = setTimeout(connect, 1000);
  };

  socket.onerror = () => {
    socket?.close();
  };
}

export function subscribe(fn: Listener): () => void {
  listeners.push(fn);
  connect();
  return () => {
    listeners = listeners.filter((l) => l !== fn);
  };
}

/**
 * Send a command over WebSocket and wait for the result.
 * Resolves with the host agent response once `type:<cmd>:result` arrives.
 */
export function send(
  type: string,
  payload: Record<string, unknown>,
): Promise<Record<string, any> & { success: boolean; message?: string; error?: string; errors?: string[] }> {
  return new Promise((resolve, reject) => {
    connect();

    const reqId = String(++reqCounter);

    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error("WebSocket request timed out"));
    }, 300_000);

    const handler: Listener = (msg) => {
      if (msg.reqId !== reqId) return;
      const msgType = msg.type as string;
      if (msgType === `${type}:result`) {
        cleanup();
        resolve(msg as { success: boolean; message?: string; error?: string; errors?: string[] });
      } else if (msgType === `${type}:error`) {
        cleanup();
        resolve({ success: false, message: (msg.error as string) ?? "Unknown error" });
      }
    };

    function cleanup() {
      clearTimeout(timeout);
      listeners = listeners.filter((l) => l !== handler);
    }

    listeners.push(handler);

    const trySend = () => {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type, payload, reqId }));
      } else {
        setTimeout(trySend, 50);
      }
    };
    trySend();
  });
}
