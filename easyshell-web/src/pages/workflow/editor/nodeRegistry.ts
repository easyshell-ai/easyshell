/**
 * 12 类节点的展示元数据（分组 / 是否固定 / 一句话说明的 i18n key）。
 *
 * 纯数据层：图标在 NodePalette / NodeInspector 里按同一份 NODE_ICON 映射渲染，
 * 避免 .ts 文件导出 JSX 触发 react-refresh 规则。
 *
 * 这份注册表只做「展示」，不复制 graph.ts 的 DEFAULT_NODE_CONFIG——字段契约唯一
 * 权威仍是服务端执行器 + graph.ts。
 */

import type { WorkflowNodeType } from '../types';

export type NodeCategory = 'entry' | 'execute' | 'flow' | 'human';

export interface NodeRegistryEntry {
  type: WorkflowNodeType;
  category: NodeCategory;
  /** 由新建工作流自动插入 / 不可拖入调色板 */
  fixed?: boolean;
}

export const NODE_CATEGORIES: NodeCategory[] = ['entry', 'execute', 'flow', 'human'];

export const NODE_REGISTRY: NodeRegistryEntry[] = [
  { type: 'start', category: 'entry', fixed: true },
  { type: 'end', category: 'entry', fixed: true },
  { type: 'script-exec', category: 'execute' },
  { type: 'transform', category: 'execute' },
  { type: 'ai-analyze', category: 'execute' },
  { type: 'llm', category: 'execute' },
  { type: 'classifier', category: 'execute' },
  { type: 'code', category: 'execute' },
  { type: 'condition', category: 'flow' },
  { type: 'wait', category: 'flow' },
  { type: 'approval', category: 'human' },
  { type: 'notify', category: 'human' },
];

export function nodeRegistryEntry(type: WorkflowNodeType): NodeRegistryEntry {
  return NODE_REGISTRY.find((entry) => entry.type === type) ?? { type, category: 'execute' };
}

export const NODE_CATEGORY_LABEL_KEY: Record<NodeCategory, string> = {
  entry: 'workflow.node.category.entry',
  execute: 'workflow.node.category.execute',
  flow: 'workflow.node.category.flow',
  human: 'workflow.node.category.human',
};
