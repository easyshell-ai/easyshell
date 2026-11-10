/** Start 变量编辑器的纯数据层（与组件分开，满足 react-refresh 单组件导出规则）。 */

import { NODE_KEY_PATTERN } from '../../graph';
import type { VariableSpec, VariableValueType } from '../../types';

export interface StartVariableRow {
  name: string;
  type: VariableValueType;
  required: boolean;
  default?: unknown;
  description: string;
}

export function variablesToRows(variables: Record<string, VariableSpec> | undefined): StartVariableRow[] {
  return Object.entries(variables || {}).map(([name, spec]) => ({
    name,
    type: spec?.type || 'string',
    required: !!spec?.required,
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
    if (row.required) spec.required = true;
    if (row.default !== undefined && row.default !== null && row.default !== '') spec.default = row.default;
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
