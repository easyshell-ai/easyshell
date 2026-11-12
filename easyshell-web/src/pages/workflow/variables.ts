/**
 * 变量引用目录（设计 §2.3）。
 *
 * 纯函数、无 React 依赖：把 definition 投影成「工作流入参 / 开始节点输出 / 祖先节点输出 /
 * outputVar 别名」四类可插入引用，供变量选择器搜索、分组、点击插入。
 *
 * 路径口径：
 * - 工作流入参：`run.input.<name>`
 * - 开始节点输出：`nodes.<startKey>.output.<name>`
 * - 普通节点输出：`nodes.<key>.output[.<field>]`
 * - 输出别名：`<alias>[.<field>]`（与 nodes.<key>.output 等价）
 */

import type {
  VariableSpec,
  VariableValueType,
  WorkflowDefinition,
  WorkflowNode,
  WorkflowNodeType,
} from './types';

export type VariableGroupKind = 'input' | 'start' | 'node' | 'alias';

export interface WorkflowVariableRef {
  /** 插入模板/表达式的完整路径 */
  path: string;
  /** 选择器里的短标签（字段名或节点名，不含 i18n） */
  label: string;
  /** 值类型徽章；脚本输出等未知 schema 时缺省 */
  type?: VariableValueType;
  required?: boolean;
  description?: string;
  /** 所属节点 key（工作流入参为空） */
  nodeKey?: string;
  /** 只有整体输出可用、内部字段未知（如 script-exec） */
  opaque?: boolean;
}

export interface WorkflowVariableGroup {
  id: string;
  kind: VariableGroupKind;
  /** 分组标题（节点名 / 固定 schema 名，UI 层可再本地化） */
  label: string;
  /** 节点分组对应的 key */
  nodeKey?: string;
  refs: WorkflowVariableRef[];
}

export const VARIABLE_GROUP_INPUT = 'run-input';
export const VARIABLE_GROUP_ALIAS = 'output-var';

export function variableGroupIdForNode(key: string): string {
  return `node:${key}`;
}

interface KnownField {
  name: string;
  type: VariableValueType;
  description?: string;
}

/** 已知输出 schema（§2.3）；start 由 definition.variables 动态生成。 */
const KNOWN_OUTPUT_FIELDS: Partial<Record<WorkflowNodeType, KnownField[]>> = {
  condition: [{ name: 'result', type: 'boolean', description: 'workflow.variable.output.condition.result' }],
  notify: [
    { name: 'delivered', type: 'json', description: 'workflow.variable.output.notify.delivered' },
    { name: 'failed', type: 'json', description: 'workflow.variable.output.notify.failed' },
    { name: 'skipped', type: 'json', description: 'workflow.variable.output.notify.skipped' },
  ],
  approval: [
    { name: 'decision', type: 'string', description: 'workflow.variable.output.approval.decision' },
    { name: 'comment', type: 'string', description: 'workflow.variable.output.approval.comment' },
    { name: 'decidedBy', type: 'number', description: 'workflow.variable.output.approval.decidedBy' },
  ],
  wait: [{ name: 'waitedMs', type: 'number', description: 'workflow.variable.output.wait.waitedMs' }],
  'ai-analyze': [
    { name: 'text', type: 'string', description: 'workflow.variable.output.ai-analyze.text' },
    { name: 'json', type: 'json', description: 'workflow.variable.output.ai-analyze.json' },
    { name: 'structured', type: 'boolean', description: 'workflow.variable.output.ai-analyze.structured' },
    { name: 'model', type: 'string', description: 'workflow.variable.output.ai-analyze.model' },
    { name: 'truncated', type: 'boolean', description: 'workflow.variable.output.ai-analyze.truncated' },
  ],
  llm: [
    { name: 'text', type: 'string', description: 'workflow.variable.output.llm.text' },
    { name: 'json', type: 'json', description: 'workflow.variable.output.llm.json' },
    { name: 'structured', type: 'boolean', description: 'workflow.variable.output.llm.structured' },
    { name: 'model', type: 'string', description: 'workflow.variable.output.llm.model' },
  ],
  classifier: [
    { name: 'class', type: 'string', description: 'workflow.variable.output.classifier.class' },
    { name: 'reason', type: 'string', description: 'workflow.variable.output.classifier.reason' },
    { name: 'raw', type: 'string', description: 'workflow.variable.output.classifier.raw' },
  ],
};

function configString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

/** 从 [{name, value}] 结构里取合法名字（transform.mappings / end.outputs） */
function namedConfigEntries(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const names: string[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object') continue;
    const name = configString((item as { name?: unknown }).name)?.trim();
    if (name && !names.includes(name)) names.push(name);
  }
  return names;
}

/**
 * 单节点已知输出字段。transform / end 从 config 的 name 列动态推导；
 * script-exec 无 schema，返回空数组（调用方只暴露整体 output）。
 */
export function knownOutputFieldsOf(node: WorkflowNode): KnownField[] {
  const fields = KNOWN_OUTPUT_FIELDS[node.type];
  if (fields) {
    if ((node.type === 'llm' || node.type === 'ai-analyze') &&
        node.config?.outputMode === 'json_schema_v1' &&
        typeof node.config?.jsonSchema === 'string') {
      try {
        const schema = JSON.parse(node.config.jsonSchema);
        if (schema && schema.type === 'object' && schema.properties && typeof schema.properties === 'object' && !Array.isArray(schema.properties)) {
          const discovered: KnownField[] = Object.entries(schema.properties)
            .filter(([name, value]) => /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name) && value != null && typeof value === 'object' && !Array.isArray(value))
            .map(([name, value]) => {
              const type = (value as { type?: unknown }).type;
              return { name: `json.${name}`, type: type === 'string' || type === 'boolean' ? type : type === 'number' || type === 'integer' ? 'number' : 'json' };
            });
          return [...fields, ...discovered];
        }
      } catch { /* 原文不可解析时不推测字段 */ }
    }
    return fields;
  }
  if (node.type === 'transform') {
    return namedConfigEntries(node.config?.mappings).map((name) => ({ name, type: 'json' as const }));
  }
  if (node.type === 'end') {
    return namedConfigEntries(node.config?.outputs).map((name) => ({ name, type: 'json' as const }));
  }
  return [];
}

function nodeRefs(node: WorkflowNode): WorkflowVariableRef[] {
  const basePath = `nodes.${node.key}.output`;
  const refs: WorkflowVariableRef[] = [
    {
      path: basePath,
      label: 'output',
      type: 'json',
      nodeKey: node.key,
    },
  ];
  for (const field of knownOutputFieldsOf(node)) {
    refs.push({
      path: `${basePath}.${field.name}`,
      label: field.name,
      type: field.type,
      description: field.description,
      nodeKey: node.key,
    });
  }
  return refs;
}

function startRefs(node: WorkflowNode, variables: Record<string, VariableSpec>): WorkflowVariableRef[] {
  return Object.entries(variables || {}).map(([name, spec]) => ({
    path: `nodes.${node.key}.output.${name}`,
    label: name,
    type: spec?.type,
    required: spec?.required,
    description: spec?.description,
    nodeKey: node.key,
  }));
}

function inputGroup(variables: Record<string, VariableSpec>): WorkflowVariableGroup {
  return {
    id: VARIABLE_GROUP_INPUT,
    kind: 'input',
    label: 'run.input',
    refs: Object.entries(variables || {}).map(([name, spec]) => ({
      path: `run.input.${name}`,
      label: name,
      type: spec?.type,
      required: spec?.required,
      description: spec?.description,
    })),
  };
}

function aliasRefs(node: WorkflowNode): WorkflowVariableRef[] {
  const alias = configString(node.outputVar)?.trim();
  if (!alias) return [];
  const refs: WorkflowVariableRef[] = [
    { path: alias, label: alias, type: 'json', nodeKey: node.key },
  ];
  for (const field of knownOutputFieldsOf(node)) {
    refs.push({
      path: `${alias}.${field.name}`,
      label: `${alias}.${field.name}`,
      type: field.type,
      description: field.description,
      nodeKey: node.key,
    });
  }
  return refs;
}

/** 拓扑祖先（含间接祖先，不含自身）。旧定义允许环，visited 集合保证终止。 */
export function ancestorKeysOf(definition: WorkflowDefinition, nodeKey: string): Set<string> {
  const incoming = new Map<string, string[]>();
  for (const edge of definition.edges || []) {
    if (!incoming.has(edge.to)) incoming.set(edge.to, []);
    incoming.get(edge.to)!.push(edge.from);
  }
  const ancestors = new Set<string>();
  const stack = [...(incoming.get(nodeKey) || [])];
  while (stack.length > 0) {
    const key = stack.pop()!;
    if (key === nodeKey || ancestors.has(key)) continue;
    ancestors.add(key);
    for (const parent of incoming.get(key) || []) stack.push(parent);
  }
  return ancestors;
}

function buildGroups(
  definition: WorkflowDefinition,
  ancestorFilter?: Set<string>
): WorkflowVariableGroup[] {
  const variables = definition.variables || {};
  const groups: WorkflowVariableGroup[] = [inputGroup(variables)];

  const allowed = (node: WorkflowNode): boolean =>
    !ancestorFilter || ancestorFilter.has(node.key);

  for (const node of definition.nodes || []) {
    // start 的输出就是工作流入参：即使过滤的是 start 自身，也保留该分组
    if (node.type !== 'start' || !allowed(node)) continue;
    const refs = startRefs(node, variables);
    if (refs.length > 0) {
      groups.push({
        id: variableGroupIdForNode(node.key),
        kind: 'start',
        label: node.name || node.key,
        nodeKey: node.key,
        refs,
      });
    }
  }

  for (const node of definition.nodes || []) {
    if (node.type === 'start' || !allowed(node)) continue;
    groups.push({
      id: variableGroupIdForNode(node.key),
      kind: 'node',
      label: node.name || node.key,
      nodeKey: node.key,
      refs: nodeRefs(node),
    });
  }

  const aliases: WorkflowVariableRef[] = [];
  for (const node of definition.nodes || []) {
    if (!allowed(node)) continue;
    aliases.push(...aliasRefs(node));
  }
  if (aliases.length > 0) {
    groups.push({
      id: VARIABLE_GROUP_ALIAS,
      kind: 'alias',
      label: 'outputVar',
      refs: aliases,
    });
  }

  return groups;
}

/** 整份定义里的全部可用引用（不做拓扑过滤，模板/帮助文档可用）。 */
export function workflowVariablesOf(definition: WorkflowDefinition): WorkflowVariableGroup[] {
  return buildGroups(definition);
}

/** 某个节点可安全引用的祖先变量；只列拓扑祖先，避免运行期引用不存在。 */
export function availableVariablesForNode(
  definition: WorkflowDefinition,
  nodeKey: string
): WorkflowVariableGroup[] {
  return buildGroups(definition, ancestorKeysOf(definition, nodeKey));
}
