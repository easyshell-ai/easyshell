/**
 * 自定义边的纯逻辑（设计 §3.5 / §2.2）。
 *
 * condition 节点的出边按 sourceHandle 显示 IF(true) / ELSE(false)；
 * 旧定义没有 sourceHandle、但保留了 condition 文本时，回落到紧凑的 IF 胶囊。
 * 普通边不常驻标签；有 condition 的只在 hover / 选中时显示「条件」徽标。
 */

import { HANDLE_FALSE, HANDLE_TRUE } from '../../graph';
import type { WorkflowNodeType } from '../../types';

export type EdgePillKind = 'if' | 'else' | 'legacy-if' | 'none';

export interface EdgePillInfo {
  kind: EdgePillKind;
  /** 常驻标签文案；none 时不渲染 */
  text: 'IF' | 'ELSE' | '';
  /** 悬停 tooltip（condition 全文） */
  title?: string;
  /** CSS 修饰类 */
  className: string;
}

const NONE: EdgePillInfo = { kind: 'none', text: '', className: '' };

function withCondition(base: string, condition?: string): string {
  return condition ? `${base} · ${condition}` : base;
}

/**
 * @param sourceType   连线源节点类型（store 里查到的优先）
 * @param sourceHandle React Flow 的 sourceHandleId
 * @param condition    edge.data.condition（旧语义表达式）
 */
export function edgePillOf(
  sourceType: WorkflowNodeType | undefined,
  sourceHandle: string | null | undefined,
  condition?: string
): EdgePillInfo {
  if (sourceType !== 'condition') return NONE;

  if (sourceHandle === HANDLE_TRUE) {
    return {
      kind: 'if',
      text: 'IF',
      title: withCondition('IF (true)', condition),
      className: 'wf-edge-pill--if',
    };
  }
  if (sourceHandle === HANDLE_FALSE) {
    return {
      kind: 'else',
      text: 'ELSE',
      title: 'ELSE (false)',
      className: 'wf-edge-pill--else',
    };
  }
  // 旧定义/手工粘贴的边：没有 handle，但 condition 表达式仍在
  if (condition) {
    return {
      kind: 'legacy-if',
      text: 'IF',
      title: withCondition('IF', condition),
      className: 'wf-edge-pill--if wf-edge-pill--legacy',
    };
  }
  return NONE;
}

/** 普通边上的「条件」徽标 tooltip */
export function conditionBadgeTitle(condition?: string): string {
  return condition ? `条件 · ${condition}` : '条件';
}
