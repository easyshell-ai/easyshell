import React from 'react';
import { Alert, Button, Input, InputNumber, Select, Space, Switch, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { NODE_KEY_PATTERN } from '../../graph';
import type { VariableValueType } from '../../types';
import {
  invalidVariableNames,
  normalizeOptions,
  type StartVariableRow,
} from './startVariablesModel';

/**
 * Start 节点配置 = 工作流入参编辑器（写 definition.variables，设计 §3.3）。
 *
 * 行结构：label（展示名）/ name（key，monospace）/ type / required / options（仅 string）/
 * default（类型感知）/ description。空数组 = 没有入参。
 * name 采用与服务端一致的 ^[a-z][a-z0-9_]*$，前端只做提前提示，权威校验仍在服务端。
 */

const TYPES: VariableValueType[] = ['string', 'number', 'boolean', 'json'];

export interface StartVariablesEditorProps {
  value?: StartVariableRow[];
  onChange?: (rows: StartVariableRow[]) => void;
}

/** 默认值控件按类型切换；有 options 的 string 用下拉，json 用多行文本 + JSON.parse */
export const StartVariablesEditor: React.FC<StartVariablesEditorProps> = ({ value, onChange }) => {
  const { t } = useTranslation();
  const rows = value || [];
  const invalid = invalidVariableNames(rows);

  const emit = (next: StartVariableRow[]) => onChange?.(next);
  const patch = (index: number, changes: Partial<StartVariableRow>) => {
    emit(rows.map((row, position) => (position === index ? { ...row, ...changes } : row)));
  };

  return (
    <div data-testid="start-variables-editor" className="wf-row-editor">
      {rows.length === 0 && (
        <Typography.Text type="secondary" className="wf-row-editor-empty">
          {t('workflow.start.empty')}
        </Typography.Text>
      )}
      {rows.map((row, index) => (
        <div className="wf-mapping-row" key={index}>
          <Space size={4} wrap style={{ width: '100%' }}>
            <Input
              value={row.label}
              onChange={(event) => patch(index, { label: event.target.value })}
              placeholder={t('workflow.start.labelPlaceholder')}
              style={{ width: 120 }}
              aria-label={t('workflow.start.label')}
              data-testid="start-label"
            />
            <Input
              value={row.name}
              status={row.name.trim() && !NODE_KEY_PATTERN.test(row.name.trim()) ? 'error' : undefined}
              onChange={(event) => patch(index, { name: event.target.value })}
              placeholder={t('workflow.start.namePlaceholder')}
              style={{ width: 130, fontFamily: 'monospace' }}
              aria-label={t('workflow.start.name')}
            />
            <Select
              value={row.type}
              onChange={(type: VariableValueType) =>
                patch(index, {
                  type,
                  default: type === 'boolean' ? false : type === 'number' ? undefined : '',
                  // options 只属于 string；切到其它类型时清空，避免 definition 残留无效字段
                  options: type === 'string' ? row.options : [],
                })
              }
              options={TYPES.map((type) => ({ value: type, label: t(`workflow.variable.type.${type}`) }))}
              style={{ width: 96 }}
              aria-label={t('workflow.start.type')}
            />
            <Space size={4}>
              <Switch
                size="small"
                checked={row.required}
                onChange={(required) => patch(index, { required })}
                aria-label={t('workflow.start.required')}
              />
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('workflow.start.required')}
              </Typography.Text>
            </Space>
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              aria-label={t('common.remove')}
              onClick={() => emit(rows.filter((_, position) => position !== index))}
            />
          </Space>

          {row.type === 'string' && (
            <OptionsEditor
              value={row.options}
              onChange={(options) => patch(index, { options })}
            />
          )}

          <Space size={4} wrap style={{ width: '100%' }}>
            <DefaultField
              row={row}
              onChange={(value_) => patch(index, { default: value_ })}
            />
            <Input
              value={row.description}
              onChange={(event) => patch(index, { description: event.target.value })}
              placeholder={t('workflow.start.descriptionPlaceholder')}
              size="small"
              style={{ flex: 1, minWidth: 150 }}
              aria-label={t('workflow.start.description')}
            />
          </Space>
        </div>
      ))}
      {invalid.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={t('workflow.start.invalidName')}
          description={invalid.join(', ')}
        />
      )}
      <Button
        block
        icon={<PlusOutlined />}
        onClick={() =>
          emit([
            ...rows,
            { name: '', label: '', type: 'string', required: false, options: [], default: '', description: '' },
          ])
        }
      >
        {t('workflow.start.addVariable')}
      </Button>
    </div>
  );
};

/** string 枚举编辑器：只有当用户确实需要固定选项时才添加行 */
const OptionsEditor: React.FC<{ value: string[]; onChange: (next: string[]) => void }> = ({
  value,
  onChange,
}) => {
  const { t } = useTranslation();
  const options = value || [];
  return (
    <div className="wf-start-options" data-testid="start-options">
      <Typography.Text type="secondary" className="wf-start-options-title">
        {t('workflow.start.options')}
      </Typography.Text>
      {options.map((option, index) => (
        <Space key={index} size={4} style={{ width: '100%' }}>
          <Input
            size="small"
            value={option}
            onChange={(event) => {
              const next = [...options];
              next[index] = event.target.value;
              onChange(next);
            }}
            placeholder={t('workflow.start.optionPlaceholder')}
            aria-label={t('workflow.start.optionValue')}
          />
          <Button
            danger
            size="small"
            icon={<DeleteOutlined />}
            aria-label={t('common.remove')}
            onClick={() => onChange(options.filter((_, position) => position !== index))}
          />
        </Space>
      ))}
      <Button
        size="small"
        type="dashed"
        icon={<PlusOutlined />}
        onClick={() => onChange([...options, ''])}
      >
        {t('workflow.start.addOption')}
      </Button>
      <Typography.Text type="secondary" className="wf-start-options-hint">
        {t('workflow.start.optionsHint')}
      </Typography.Text>
    </div>
  );
};

const DefaultField: React.FC<{ row: StartVariableRow; onChange: (value: unknown) => void }> = ({ row, onChange }) => {
  const { t } = useTranslation();
  const options = normalizeOptions(row.options);
  if (row.type === 'string' && options.length > 0) {
    return (
      <Select
        allowClear
        size="small"
        value={typeof row.default === 'string' && row.default ? row.default : undefined}
        onChange={(value) => onChange(value ?? undefined)}
        options={options.map((option) => ({ value: option, label: option }))}
        placeholder={t('workflow.start.defaultPlaceholder')}
        style={{ width: 150 }}
        aria-label={t('workflow.start.default')}
      />
    );
  }
  if (row.type === 'boolean') {
    return <Switch size="small" checked={row.default === true} onChange={onChange} aria-label={t('workflow.start.default')} />;
  }
  if (row.type === 'number') {
    return (
      <InputNumber
        value={typeof row.default === 'number' ? row.default : undefined}
        onChange={(value) => onChange(value ?? undefined)}
        placeholder={t('workflow.start.defaultPlaceholder')}
        style={{ width: 96 }}
        aria-label={t('workflow.start.default')}
      />
    );
  }
  if (row.type === 'json') {
    return (
      <Input
        value={row.default == null ? '' : typeof row.default === 'string' ? row.default : JSON.stringify(row.default)}
        onChange={(event) => {
          const raw = event.target.value;
          try {
            onChange(raw.trim() ? JSON.parse(raw) : undefined);
          } catch {
            onChange(raw);
          }
        }}
        placeholder="[]"
        style={{ width: 150, fontFamily: 'monospace' }}
        aria-label={t('workflow.start.default')}
      />
    );
  }
  return (
    <Input
      value={typeof row.default === 'string' ? row.default : row.default == null ? '' : String(row.default)}
      onChange={(event) => onChange(event.target.value)}
      placeholder={t('workflow.start.defaultPlaceholder')}
      style={{ width: 150 }}
      aria-label={t('workflow.start.default')}
    />
  );
};

export default StartVariablesEditor;
