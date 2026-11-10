/** Start 变量编辑器的纯数据层（与组件分开，满足 react-refresh 单组件导出规则）。 */

import { NODE_KEY_PATTERN } from '../../graph';
import type { VariableSpec, VariableValueType } from '../../types';

export interface StartVariableRow {
  name: string;
  /** 展示名（触发弹窗优先显示 label，缺省显示 name） */
  label: string;
  type: VariableValueType;
  required: boolean;
  /** 仅 type=string 的枚举值 */
  options: string[];
  default?: unknown;
  description: string;
}

/** options 只保留非空、去重后的值（顺序保持首次出现） */
export function normalizeOptions(options: string[] | undefined): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const option of options || []) {
    const trimmed = option.trim();
    if (!trimmed || seen.has(trimmed)) continue;
    seen.add(trimmed);
    result.push(trimmed);
  }
  return result;
}

export function variablesToRows(variables: Record<string, VariableSpec> | undefined): StartVariableRow[] {
  return Object.entries(variables || {}).map(([name, spec]) => ({
    name,
    label: spec?.label || '',
    type: spec?.type || 'string',
    required: !!spec?.required,
    options: normalizeOptions(spec?.options),
    default: spec?.default,
    description: spec?.description || '',
  }));
}

/** rows → definition.variables；非法/重复名字的行直接跳过（保存前由 invalidVariableNames 拦下） */
export function rowsToVariables(rows: StartVariableRow[]): Record<string, VariableSpec> {
  const result: Record<string, VariableSpec> = {};
  const seen = new Set<string>();
  for (const row of rows) {
    const name = row.name.trim();
    if (!name || !NODE_KEY_PATTERN.test(name) || seen.has(name)) continue;
    seen.add(name);
    const spec: VariableSpec = { type: row.type };
    if (row.label?.trim()) spec.label = row.label.trim();
    if (row.required) spec.required = true;
    if (row.default !== undefined && row.default !== null && row.default !== '') spec.default = row.default;
    if (row.type === 'string') {
      const options = normalizeOptions(row.options);
      if (options.length > 0) spec.options = options;
    }
    if (row.description.trim()) spec.description = row.description.trim();
    result[name] = spec;
  }
  return result;
}

export function invalidVariableNames(rows: StartVariableRow[]): string[] {
  const seen = new Set<string>();
  const invalid: string[] = [];
  for (const row of rows) {
    const name = row.name.trim();
    if (!name) continue;
    if (!NODE_KEY_PATTERN.test(name) || seen.has(name)) invalid.push(name);
    seen.add(name);
  }
  return [...new Set(invalid)];
}
