/** 工作流运行态状态 → 标签色与 i18n 键（与 src/utils/status.ts 同风格） */
export const runStatusMap: Record<string, { color: string; text: string }> = {
  pending: { color: 'default', text: 'workflow.nodeStatus.pending' },
  queued: { color: 'default', text: 'workflow.nodeStatus.pending' },
  running: { color: 'processing', text: 'workflow.nodeStatus.running' },
  waiting: { color: 'purple', text: 'workflow.nodeStatus.suspended' },
  succeeded: { color: 'success', text: 'workflow.nodeStatus.succeeded' },
  failed: { color: 'error', text: 'workflow.nodeStatus.failed' },
  cancelled: { color: 'default', text: 'workflow.nodeStatus.cancelled' },
  timeout: { color: 'warning', text: 'workflow.nodeStatus.timeout' },
  interrupted: { color: 'warning', text: 'workflow.nodeStatus.interrupted' },
};

export const nodeRunStatusMap: Record<string, { color: string; text: string }> = {
  pending: { color: 'default', text: 'workflow.nodeStatus.pending' },
  running: { color: 'processing', text: 'workflow.nodeStatus.running' },
  waiting: { color: 'purple', text: 'workflow.nodeStatus.suspended' },
  succeeded: { color: 'success', text: 'workflow.nodeStatus.succeeded' },
  failed: { color: 'error', text: 'workflow.nodeStatus.failed' },
  skipped: { color: 'default', text: 'workflow.nodeStatus.skipped' },
  cancelled: { color: 'default', text: 'workflow.nodeStatus.cancelled' },
  timeout: { color: 'warning', text: 'workflow.nodeStatus.timeout' },
};
