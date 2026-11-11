/** Legacy workflow draft keys have no trustworthy user ownership.
 * Do not read, write, migrate or delete them until safe user scoping and
 * cross-tab coordination are available.
 */
import type { WorkflowDefinition } from '../types';
import type { DraftToken } from '../api';

export const DRAFT_KEY_PREFIX = 'wf-draft-';

export interface StoredDraft {
  definition: WorkflowDefinition;
  baseVersion: number;
  savedAt: number;
  token?: DraftToken;
  expectedAbsent?: true;
}

export function draftKey(workflowId: number): string {
  return `${DRAFT_KEY_PREFIX}${workflowId}`;
}
