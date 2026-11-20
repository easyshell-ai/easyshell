import React from 'react';
import { Button, Input, Space, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { EndOutput } from '../../types';
import type { WorkflowVariableGroup } from '../../variables';
import { VariableInput } from './VariablePicker';

/**
 * End 节点配置 = 最终输出映射（config.outputs）。
 * 值支持字面量与 `${path}`；纯 `${path}` 在服务端保留原类型。
 */

export interface EndOutputsEditorProps {
  value?: EndOutput[];
  onChange?: (rows: EndOutput[]) => void;
  groups: WorkflowVariableGroup[];
}

export const EndOutputsEditor: React.FC<EndOutputsEditorProps> = ({ value, onChange, groups }) => {
  const { t } = useTranslation();
  const rows = value || [];
  const emit = (next: EndOutput[]) => onChange?.(next);
  const patch = (index: number, changes: Partial<EndOutput>) =>
    emit(rows.map((row, position) => (position === index ? { ...row, ...changes } : row)));

  return (
    <div data-testid="end-outputs-editor" className="wf-row-editor">
      <Typography.Text type="secondary" className="wf-row-editor-hint">
        {t('workflow.end.hint')}
      </Typography.Text>
      {rows.length === 0 && (
        <Typography.Text type="secondary" className="wf-row-editor-empty">
          {t('workflow.end.empty')}
        </Typography.Text>
      )}
      {rows.map((row, index) => (
        <div className="wf-mapping-row" key={index}>
          <Space size={4} style={{ width: '100%' }} align="start">
            <Input
              value={row.name}
              onChange={(event) => patch(index, { name: event.target.value })}
              placeholder={t('workflow.end.namePlaceholder')}
              style={{ width: 130, fontFamily: 'monospace' }}
              aria-label={t('workflow.end.name')}
            />
            <div style={{ flex: 1, minWidth: 0 }}>
              <VariableInput
                value={row.value}
                onChange={(next) => patch(index, { value: next })}
                groups={groups}
                mode="template"
                ariaLabel={t('workflow.end.value')}
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
        {t('workflow.end.addOutput')}
      </Button>
    </div>
  );
};

export default EndOutputsEditor;
