import type { ExecutionPlan, AiChatMessage } from '../../../types';

export interface ApprovalRequest {
  taskId: string;
  description: string;
  status: 'pending' | 'approved' | 'rejected';
}

export interface ProcessData {
  plan: ExecutionPlan | null;
  thinkingLog: string[];
  toolCalls: Array<{ toolName: string; toolArgs: string; toolResult?: string }>;
  reviewResult?: string;
}

export interface ActiveStreamState {
  sessionId: string;
  controller: AbortController;
  content: string;
  plan: ExecutionPlan | null;
  thinkingLog: string[];
  toolCalls: Array<{ toolName: string; toolArgs: string; toolResult?: string }>;
  agent: string;
  stepDescription: string;
  stepIndex: number;
  isStreaming: boolean;
  pendingApprovals: ApprovalRequest[];
  messages: AiChatMessage[];
  onStateChange?: () => void;
  planConfirmationStatus?: 'awaiting' | 'confirmed' | 'rejected';
  reviewResult?: string;
  parallelProgress?: Array<{ group: number; completed: number; total: number }>;
}

export interface CompletedStreamResult {
  sessionId: string;
  messages: AiChatMessage[];
  processDataMap: Record<number, ProcessData>;
}

export interface TreeNode {
  title: string;
  value: string;
  key: string;
  selectable?: boolean;
  children?: TreeNode[];
}
