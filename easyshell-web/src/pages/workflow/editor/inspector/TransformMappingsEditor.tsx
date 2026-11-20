import React from 'react';
import { Button, Input, Space, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { TransformMapping } from '../../types';
import type { WorkflowVariableGroup } from '../../variables';
import { VariableInput } from './VariablePicker';

/**
 * Transform 节点配置 = 映射行（config.mappings）。
 * 纯 `${path}` 保留原类型；含字面量时按模板字符串拼接。
 */

export interface TransformMappingsEditorProps {
  value?: TransformMapping[];
  onChange?: (rows: TransformMapping[]) => void;
  groups: WorkflowVariableGroup[];
}

export const TransformMappingsEditor: React.FC<TransformMappingsEditorProps> = ({ value, onChange, groups }) => {
  const { t } = useTranslation();
  const rows = value || [];
  const emit = (next: TransformMapping[]) => onChange?.(next);
  const patch = (index: number, changes: Partial<TransformMapping>) =>
    emit(rows.map((row, position) => (position === index ? { ...row, ...changes } : row)));

  return (
    <div data-testid="transform-mappings-editor" className="wf-row-editor">
      <Typography.Text type="secondary" className="wf-row-editor-hint">
        {t('workflow.transform.hint')}
      </Typography.Text>
      {rows.length === 0 && (
        <Typography.Text type="secondary" className="wf-row-editor-empty">
          {t('workflow.transform.empty')}
        </Typography.Text>
      )}
      {rows.map((row, index) => (
        <div className="wf-mapping-row" key={index}>
          <Space size={4} style={{ width: '100%' }} align="start">
            <Input
              value={row.name}
              onChange={(event) => patch(index, { name: event.target.value })}
              placeholder={t('workflow.transform.namePlaceholder')}
              style={{ width: 130, fontFamily: 'monospace' }}
              aria-label={t('workflow.transform.name')}
            />
            <div style={{ flex: 1, minWidth: 0 }}>
              <VariableInput
                value={row.value}
                onChange={(next) => patch(index, { value: next })}
                groups={groups}
                mode="template"
                ariaLabel={t('workflow.transform.value')}
                placeholder="${nodes.check.output.usage}"
                monospace
              />
            </div>
            <Button
              danger
              icon={<DeleteOutlined />}
              aria-label={t('common.remove')}
              onClick={() => emit(rows.filter((_, position) => position !== index))}
            />
          </Space>
        </div>
      ))}
      <Button block icon={<PlusOutlined />} onClick={() => emit([...rows, { name: '', value: '' }])}>
        {t('workflow.transform.addMapping')}
      </Button>
    </div>
  );
};

export default TransformMappingsEditor;
