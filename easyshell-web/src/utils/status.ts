import React from 'react';
import {
  SyncOutlined,
  LoadingOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';

export interface StatusEntry {
  color: string;
  text: string;
  icon?: React.ReactNode;
}

export interface StatusEntryWithLabel {
  color: string;
  label: string;
}

/**
 * Task status: 0-待执行, 1-执行中, 2-成功, 3-部分失败, 4-失败, 5-超时
 */
export const taskStatusMap: Record<number, StatusEntry> = {
  0: { color: 'default', text: 'status.task.pending' },
  1: { color: 'processing', text: 'status.task.running' },
  2: { color: 'success', text: 'status.task.success' },
  3: { color: 'warning', text: 'status.task.partialFailed' },
  4: { color: 'error', text: 'status.task.failed' },
  5: { color: 'orange', text: 'status.task.timeout' },
};

/**
 * Job status (same values as task status)
 */
export const jobStatusMap: Record<number, StatusEntry> = taskStatusMap;

/**
 * Host/Agent status: 0-离线, 1-在线, 2-不稳定
 */
export const hostStatusMap: Record<number, StatusEntry> = {
  0: { color: 'red', text: 'status.host.offline' },
  1: { color: 'green', text: 'status.host.online' },
  2: { color: 'orange', text: 'status.host.unstable' },
};

/**
 * Provision status for host deployment
 */
export const provisionStatusMap: Record<string, StatusEntry> = {
  PENDING: { color: 'default', text: 'status.provision.pending', icon: React.createElement(SyncOutlined, { spin: true }) },
  CONNECTING: { color: 'processing', text: 'status.provision.connecting', icon: React.createElement(LoadingOutlined) },
  UPLOADING: { color: 'processing', text: 'status.provision.uploading', icon: React.createElement(LoadingOutlined) },
  INSTALLING: { color: 'processing', text: 'status.provision.installing', icon: React.createElement(LoadingOutlined) },
  STARTING: { color: 'processing', text: 'status.provision.starting', icon: React.createElement(LoadingOutlined) },
  SUCCESS: { color: 'success', text: 'status.provision.success', icon: React.createElement(CheckCircleOutlined) },
  FAILED: { color: 'error', text: 'status.provision.failed', icon: React.createElement(CloseCircleOutlined) },
  UNINSTALLING: { color: 'processing', text: 'status.provision.uninstalling', icon: React.createElement(LoadingOutlined) },
  UNINSTALL_FAILED: { color: 'error', text: 'status.provision.uninstallFailed', icon: React.createElement(CloseCircleOutlined) },
};

/**
 * Risk level mappings
 */
export const riskLevelMap: Record<string, StatusEntry> = {
  LOW: { color: 'green', text: 'status.risk.low' },
  MEDIUM: { color: 'orange', text: 'status.risk.medium' },
  HIGH: { color: 'red', text: 'status.risk.high' },
  BANNED: { color: 'magenta', text: 'status.risk.banned' },
};

/**
 * Approval status mappings
 */
export const approvalStatusMap: Record<string, StatusEntry> = {
  pending: { color: 'processing', text: 'status.approval.pending' },
  approved: { color: 'success', text: 'status.approval.approved' },
  rejected: { color: 'error', text: 'status.approval.rejected' },
};

/**
 * AI task type mappings
 */
export const taskTypeMap: Record<string, StatusEntryWithLabel> = {
  inspect: { color: 'blue', label: 'status.taskType.inspect' },
  detect: { color: 'cyan', label: 'status.taskType.detect' },
  security: { color: 'orange', label: 'status.taskType.security' },
  disk: { color: 'purple', label: 'status.taskType.disk' },
  docker_health: { color: 'geekblue', label: 'status.taskType.dockerHealth' },
  custom: { color: 'default', label: 'status.taskType.custom' },
};

/**
 * Software type mappings for host detail
 */
export const softwareTypeMap: Record<string, StatusEntry> = {
  database: { color: 'blue', text: 'status.software.database' },
  service: { color: 'green', text: 'status.software.service' },
  runtime: { color: 'purple', text: 'status.software.runtime' },
  container_engine: { color: 'cyan', text: 'status.software.containerEngine' },
  container: { color: 'orange', text: 'status.software.container' },
  other: { color: 'default', text: 'status.software.other' },
};

/**
 * Report status mappings
 */
export const reportStatusMap: Record<string, StatusEntryWithLabel> = {
  running: { color: 'processing', label: 'status.report.running' },
  success: { color: 'green', label: 'status.report.success' },
  failed: { color: 'red', label: 'status.report.failed' },
  partial: { color: 'orange', label: 'status.report.partial' },
};

/**
 * Get resource usage color based on threshold
 * > 80% → red, > 60% → yellow, else → green
 */
export const getResourceColor = (value: number): string => {
  if (value > 80) return '#ff4d4f';
  if (value > 60) return '#faad14';
  return '#52c41a';
};

export const provisionStepIndex: Record<string, number> = {
  PENDING: -1,
  CONNECTING: 0,
  UPLOADING: 1,
  INSTALLING: 2,
  STARTING: 3,
  SUCCESS: 4,
  FAILED: -1,
};

export function getProvisionStep(status: string): { current: number; status: 'process' | 'finish' | 'error' | 'wait' } {
  if (status === 'SUCCESS') return { current: 4, status: 'finish' };
  if (status === 'FAILED') return { current: 0, status: 'error' };
  const idx = provisionStepIndex[status] ?? -1;
  if (idx < 0) return { current: 0, status: 'wait' };
  return { current: idx, status: 'process' };
}

export const provisionStepItems = [
  { title: 'status.provisionStep.connect' },
  { title: 'status.provisionStep.upload' },
  { title: 'status.provisionStep.install' },
  { title: 'status.provisionStep.start' },
  { title: 'status.provisionStep.complete' },
];
