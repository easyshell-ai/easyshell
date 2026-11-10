import React, { useMemo, useState } from 'react';
import { Alert, Input, Select, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowVariableGroup } from '../../variables';
import { VariableInput } from './VariablePicker';
import {
  CONDITION_OPERATORS,
  composeCondition,
  isAdvancedCondition,
  parseCondition,
  type ConditionOperator,
  type ConditionParts,
} from './conditionBuilderModel';

/**
 * 条件节点构建器：左值（变量选择器，expression 模式）/ 操作符 / 右值（字面量或变量）
 * + 「高级：直接写表达式」raw textarea。
 *
 * raw textarea 是值的唯一来源：结构化字段只负责 compose 出表达式并回写；
 * 解析不了（&& / || / 复合表达式）时保留 raw 原文，并提示使用高级模式。
 */
export interface ConditionBuilderProps {
  value?: string;
  onChange?: (value: string) => void;
  groups: WorkflowVariableGroup[];
  /** antd Form.Item 注入的 id（label htmlFor 依赖它） */
  id?: string;
}

const EMPTY_PARTS: ConditionParts = { left: '', operator: '==', right: '' };

export const ConditionBuilder: React.FC<ConditionBuilderProps> = ({ value = '', onChange, groups, id }) => {
  const { t } = useTranslation();
  const parsedValue = useMemo(() => parseCondition(value), [value]);
  // 用户正在编辑的结构化态：以它 compose 出的表达式为基准；外部值一变即让位给解析结果
  const [editing, setEditing] = useState<{ base: string; parts: ConditionParts } | null>(null);
  const parts = editing && editing.base === value ? editing.parts : parsedValue ?? EMPTY_PARTS;
  const advanced = isAdvancedCondition(value);

  const update = (next: Partial<ConditionParts>) => {
    const merged = { ...parts, ...next };
    const expression = composeCondition(merged);
    // 左右没填全时不覆盖 raw（避免用户先点左值、表达式被清空）
    if (expression) {
      setEditing({ base: expression, parts: merged });
      onChange?.(expression);
    } else {
      setEditing({ base: value, parts: merged });
    }
  };

  return (
    <div className="wf-condition-builder" data-testid="condition-builder">
      <Typography.Text type="secondary" className="wf-condition-builder-hint">
        {t('workflow.condition.builderHint')}
      </Typography.Text>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        <div className="wf-condition-row">
          <span className="wf-condition-label">{t('workflow.condition.left')}</span>
          <VariableInput
            groups={groups}
            mode="expression"
            value={parts.left}
            onChange={(left) => update({ left })}
            ariaLabel={t('workflow.condition.left')}
            placeholder="nodes.n1.output.count"
            monospace
          />
        </div>
        <div className="wf-condition-row">
          <span className="wf-condition-label">{t('workflow.condition.operator')}</span>
          <Select
            value={parts.operator}
            onChange={(operator: ConditionOperator) => update({ operator })}
            options={CONDITION_OPERATORS.map((operator) => ({ value: operator, label: operator }))}
            style={{ width: 120 }}
            aria-label={t('workflow.condition.operator')}
          />
        </div>
        <div className="wf-condition-row">
          <span className="wf-condition-label">{t('workflow.condition.right')}</span>
          <VariableInput
            groups={groups}
            mode="expression"
            value={parts.right}
            onChange={(right) => update({ right })}
            ariaLabel={t('workflow.condition.right')}
            placeholder={t('workflow.condition.rightPlaceholder')}
            monospace
          />
        </div>
      </Space>

      <div className="wf-condition-advanced" data-testid="condition-advanced">
        <span className="wf-condition-label">{t('workflow.condition.advanced')}</span>
        <Input.TextArea
          id={id}
          value={value}
          onChange={(event) => onChange?.(event.target.value)}
          rows={3}
          placeholder="nodes.n1.output.count > 0"
          style={{ fontFamily: 'monospace', fontSize: 12 }}
          aria-label={t('workflow.form.expression')}
          data-testid="condition-raw"
        />
        <Typography.Text type="secondary" className="wf-condition-builder-hint">
          {t('workflow.condition.advancedHint')}
        </Typography.Text>
      </div>

      {advanced && (
        <Alert
          type="warning"
          showIcon
          message={t('workflow.condition.advancedOnly')}
          description={t('workflow.condition.advancedOnlyDesc')}
        />
      )}
    </div>
  );
};

export default ConditionBuilder;
