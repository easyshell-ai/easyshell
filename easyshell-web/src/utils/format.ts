import dayjs from 'dayjs';

/**
 * Format bytes to human-readable GB string
 */
export const formatBytes = (bytes: number): string => {
  if (!bytes) return '-';
  const gb = bytes / (1024 * 1024 * 1024);
  return `${gb.toFixed(1)} GB`;
};

/**
 * Format bytes with more precision (2 decimal places)
 */
export const formatBytesPrecise = (bytes: number): string => {
  if (!bytes) return '-';
  const gb = bytes / (1024 * 1024 * 1024);
  return `${gb.toFixed(2)} GB`;
};

/**
 * Format ISO time string to locale string (zh-CN)
 */
export const formatTime = (time: string | null): string => {
  if (!time) return '-';
  return new Date(time).toLocaleString('zh-CN');
};

/**
 * Format ISO time string to YYYY-MM-DD HH:mm:ss
 */
export const formatDateTime = (time: string | null | undefined): string => {
  if (!time) return '-';
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss');
};

/**
 * Format ISO time string to MM-DD HH:mm:ss (shorter)
 */
export const formatDateTimeShort = (time: string | null | undefined): string => {
  if (!time) return '-';
  return dayjs(time).format('MM-DD HH:mm:ss');
};

/**
 * Format ISO time string to HH:mm:ss (time only)
 */
export const formatTimeOnly = (time: string | null | undefined): string => {
  if (!time) return '-';
  return dayjs(time).format('HH:mm:ss');
};

/**
 * Format ISO time string to YYYY-MM-DD HH:mm (no seconds)
 */
export const formatDateMinute = (time: string | null | undefined): string => {
  if (!time) return '-';
  return dayjs(time).format('YYYY-MM-DD HH:mm');
};
