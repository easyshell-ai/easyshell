/**
 * 条件构建器的纯逻辑（与 React 组件分开，满足 react-refresh 单组件导出规则）。
 *
 * 设计约束：后端语法不新增任何东西。构建器只负责把「左值 / 操作符 / 右值」拼成
 * 已经存在的表达式字符串；高级 textarea 仍然是值来源（source of truth）。
 */

export const CONDITION_OPERATORS = ['==', '!=', '>', '<', '>=', '<=', 'contains'] as const;

export type ConditionOperator = (typeof CONDITION_OPERATORS)[number];

export interface ConditionParts {
  left: string;
  operator: ConditionOperator;
  /** 已去掉引号的展示值；变量路径原样保留 */
  right: string;
}

const OPERATOR_PATTERN = /(==|!=|>=|<=|>|<|contains)/;
const SIMPLE_EXPRESSION = /^(\S+)\s*(==|!=|>=|<=|>|<|contains)\s*(.+)$/;
const PATH_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/;
const NUMBER_PATTERN = /^-?\d+(?:\.\d+)?$/;

export function isVariableLikeOperand(value: string): boolean {
  return PATH_PATTERN.test(value);
}

function unquote(value: string): string {
  if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
    return value.slice(1, -1).replace(/\\'/g, "'").replace(/\\\\/g, '\\');
  }
  return value;
}

function quoteLiteral(value: string): string {
  return `'${value.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
}

/**
 * 右值格式化：变量路径 / 数字 / 布尔 / 已带引号的字符串原样保留，
 * 其余按后端单引号字符串字面量转义。
 */
export function formatOperand(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return '';
  if (isVariableLikeOperand(trimmed)) return trimmed;
  if (NUMBER_PATTERN.test(trimmed)) return trimmed;
  if (trimmed === 'true' || trimmed === 'false' || trimmed === 'null') return trimmed;
  if (
    (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
    (trimmed.startsWith('"') && trimmed.endsWith('"'))
  ) {
    return trimmed;
  }
  return quoteLiteral(trimmed);
}

/** 结构化字段 → 表达式；左右任一为空返回空串（不覆盖用户正在写的 raw 文本） */
export function composeCondition(parts: ConditionParts): string {
  const left = parts.left.trim();
  const right = parts.right.trim();
  if (!left || !right) return '';
  return `${left} ${parts.operator} ${formatOperand(right)}`;
}

/**
 * 表达式 → 结构化字段。只解析构建器能表达的单条件（不支持 && / ||）；
 * 解析失败返回 null，UI 继续让 raw textarea 作为唯一来源。
 */
export function parseCondition(expression: string | null | undefined): ConditionParts | null {
  const text = (expression || '').trim();
  if (!text) return null;
  if (/\s(?:&&|\|\|)\s/.test(text)) return null;
  const match = SIMPLE_EXPRESSION.exec(text);
  if (!match) return null;
  const [, left, operator, rawRight] = match;
  if (!left.trim() || !rawRight.trim()) return null;
  if (OPERATOR_PATTERN.test(rawRight)) return null;
  if (!CONDITION_OPERATORS.includes(operator as ConditionOperator)) return null;
  return {
    left: left.trim(),
    operator: operator as ConditionOperator,
    right: unquote(rawRight.trim()),
  };
}

/** 表达式里是否含有构建器无法表达的运算符（用于提示用户使用高级模式） */
export function isAdvancedCondition(expression: string | null | undefined): boolean {
  const text = (expression || '').trim();
  return !!text && parseCondition(text) === null && OPERATOR_PATTERN.test(text);
}
