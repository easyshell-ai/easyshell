import { useEffect, useRef, useCallback } from 'react';

/**
 * Reusable polling hook with configurable interval and auto-cleanup.
 *
 * @param callback - Function to call on each interval tick
 * @param intervalMs - Polling interval in milliseconds
 * @param enabled - Whether polling is active (default: true)
 */
export function usePolling(
  callback: () => void,
  intervalMs: number,
  enabled = true,
): void {
  const savedCallback = useRef(callback);

  useEffect(() => {
    savedCallback.current = callback;
  }, [callback]);

  useEffect(() => {
    if (!enabled || intervalMs <= 0) return;

    const tick = () => savedCallback.current();
    const timer = setInterval(tick, intervalMs);

    return () => clearInterval(timer);
  }, [intervalMs, enabled]);
}

/**
 * Hook for managing multiple concurrent pollers (e.g. per-record status polling).
 * Returns start/stop/cleanup functions.
 */
export function usePollingMap() {
  const timers = useRef<Record<string | number, ReturnType<typeof setInterval>>>({});

  const start = useCallback(
    (
      id: string | number,
      callback: () => void,
      intervalMs: number,
      maxDurationMs?: number,
    ) => {
      // Clear existing timer for this id
      if (timers.current[id]) {
        clearInterval(timers.current[id]);
      }

      const startTime = Date.now();

      const timer = setInterval(() => {
        if (maxDurationMs && Date.now() - startTime > maxDurationMs) {
          clearInterval(timer);
          delete timers.current[id];
          return;
        }
        callback();
      }, intervalMs);

      timers.current[id] = timer;
    },
    [],
  );

  const stop = useCallback((id: string | number) => {
    if (timers.current[id]) {
      clearInterval(timers.current[id]);
      delete timers.current[id];
    }
  }, []);

  const stopAll = useCallback(() => {
    Object.values(timers.current).forEach(clearInterval);
    timers.current = {};
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      Object.values(timers.current).forEach(clearInterval);
    };
  }, []);

  return { start, stop, stopAll };
}
