/** 变量插入的纯逻辑（与 React 组件分开，满足 react-refresh 单组件导出规则）。 */

export type VariableInsertMode = 'template' | 'expression' | 'path';

/** 纯函数：插入文本（单测直接锁三种模式） */
export function formatVariableInsert(path: string, mode: VariableInsertMode): string {
  return mode === 'template' ? `\${${path}}` : path;
}

/** data-testid 后缀：路径里只保留字母数字，其余按段折叠成 - */
export function variableTestId(path: string): string {
  return `variable-picker-item-${path.replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '')}`;
}
