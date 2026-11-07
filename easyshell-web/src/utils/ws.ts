/**
 * Browsers cannot set headers on the WebSocket API, so the access token travels in the
 * query string; the server validates it during the handshake. Kept as a pure function
 * (no window/document access) so the URL contract stays unit-testable.
 */
export function buildTaskLogWsUrl(
  taskId: string,
  token: string | null,
  location: { protocol: string; host: string }
): string {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const query = token ? `?token=${encodeURIComponent(token)}` : '';
  return `${protocol}//${location.host}/ws/task/log/${taskId}${query}`;
}
