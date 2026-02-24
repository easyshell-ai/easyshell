import { useEffect, useRef, useState, useCallback } from 'react';

export type WebSocketStatus = 'idle' | 'connecting' | 'connected' | 'disconnected';

interface UseWebSocketOptions {
  /** WebSocket path (e.g. '/ws/terminal/abc123') */
  path: string;
  /** Whether to auto-connect on mount (default: true) */
  autoConnect?: boolean;
  /** Callback for incoming messages */
  onMessage?: (data: MessageEvent) => void;
  /** Callback on connection open */
  onOpen?: () => void;
  /** Callback on connection close */
  onClose?: () => void;
  /** Callback on connection error */
  onError?: () => void;
}

interface UseWebSocketReturn {
  status: WebSocketStatus;
  send: (data: string) => void;
  connect: () => void;
  disconnect: () => void;
  wsRef: React.RefObject<WebSocket | null>;
}

/**
 * Reusable WebSocket hook with auto-protocol detection and lifecycle management.
 */
export function useWebSocket({
  path,
  autoConnect = true,
  onMessage,
  onOpen,
  onClose,
  onError,
}: UseWebSocketOptions): UseWebSocketReturn {
  const wsRef = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<WebSocketStatus>('idle');

  // Store latest callbacks in refs to avoid stale closures
  const onMessageRef = useRef(onMessage);
  const onOpenRef = useRef(onOpen);
  const onCloseRef = useRef(onClose);
  const onErrorRef = useRef(onError);

  useEffect(() => { onMessageRef.current = onMessage; }, [onMessage]);
  useEffect(() => { onOpenRef.current = onOpen; }, [onOpen]);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);
  useEffect(() => { onErrorRef.current = onError; }, [onError]);

  const connect = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close();
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}${path}`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;
    setStatus('connecting');

    ws.onopen = () => {
      setStatus('connected');
      onOpenRef.current?.();
    };

    ws.onmessage = (event) => {
      onMessageRef.current?.(event);
    };

    ws.onclose = () => {
      setStatus('disconnected');
      onCloseRef.current?.();
    };

    ws.onerror = () => {
      setStatus('disconnected');
      onErrorRef.current?.();
    };
  }, [path]);

  const disconnect = useCallback(() => {
    if (wsRef.current) {
      if (wsRef.current.readyState === WebSocket.OPEN || wsRef.current.readyState === WebSocket.CONNECTING) {
        wsRef.current.close();
      }
      wsRef.current = null;
    }
  }, []);

  const send = useCallback((data: string) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(data);
    }
  }, []);

  useEffect(() => {
    if (autoConnect) {
      connect();
    }

    return () => {
      disconnect();
    };
  }, [autoConnect, connect, disconnect]);

  return { status, send, connect, disconnect, wsRef };
}
