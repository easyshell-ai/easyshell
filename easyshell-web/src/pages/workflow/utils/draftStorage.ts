/**
 * 草稿本地落盘（设计 §4.3：断网离线变更存 localStorage，键 wf-draft-{id}）。
 *
 * B2 #16：M1 没有 secret 类型变量，但用户可能把凭据写进 scriptContent——所以写入前
 * 必须跑 SensitiveDataFilter 同规则正则（JS 镜像见 utils/sensitiveFilter.ts）。
 */

import { sanitizeDefinitionForStorage } from './sensitiveFilter';
import type { WorkflowDefinition } from '../types';

export const DRAFT_KEY_PREFIX = 'wf-draft-';

export interface StoredDraft {
  definition: WorkflowDefinition;
  baseVersion: number;
  savedAt: number;
}

export function draftKey(workflowId: number): string {
  return `${DRAFT_KEY_PREFIX}${workflowId}`;
}

export function writeDraft(
  storage: Storage,
  workflowId: number,
  definition: unknown,
  baseVersion: number,
  now: number = Date.now()
): StoredDraft {
  const draft: StoredDraft = { definition: definition as WorkflowDefinition, baseVersion, savedAt: now };
  // 脱敏在序列化之后、落盘之前：凭据不会以任何形态（含转义形态）进入 localStorage
  storage.setItem(draftKey(workflowId), sanitizeDefinitionForStorage(draft));
  return draft;
}

export function readDraft(storage: Storage, workflowId: number): StoredDraft | null {
  const raw = storage.getItem(draftKey(workflowId));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<StoredDraft>;
    if (!parsed || typeof parsed !== 'object' || !parsed.definition) return null;
    return {
      definition: parsed.definition as WorkflowDefinition,
      baseVersion: typeof parsed.baseVersion === 'number' ? parsed.baseVersion : 0,
      savedAt: typeof parsed.savedAt === 'number' ? parsed.savedAt : 0,
    };
  } catch {
    return null;
  }
}

export function clearDraft(storage: Storage, workflowId: number): void {
  storage.removeItem(draftKey(workflowId));
}
