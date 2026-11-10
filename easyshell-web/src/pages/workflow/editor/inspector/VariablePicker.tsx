import React, { useCallback, useMemo, useRef, useState } from 'react';
import { Badge, Button, Empty, Input, Popover, Tag, Typography } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { WorkflowVariableGroup, WorkflowVariableRef } from '../../variables';
import { formatVariableInsert, variableTestId, type VariableInsertMode } from './variableModel';

const GROUP_KIND_KEY: Record<WorkflowVariableGroup['kind'], string> = {
  input: 'workflow.variable.group.input',
  start: 'workflow.variable.group.start',
  node: 'workflow.variable.group.node',
  alias: 'workflow.variable.group.alias',
};

const TYPE_COLOR: Record<string, string> = {
  string: 'blue',
  number: 'gold',
  boolean: 'purple',
  json: 'geekblue',
};

export interface VariablePickerProps {
  groups: WorkflowVariableGroup[];
  mode: VariableInsertMode;
  onInsert: (path: string) => void;
  disabled?: boolean;
  /** 触发器文案覆盖（默认「插入变量」） */
  buttonText?: string;
  size?: 'small' | 'middle';
}

function matches(ref: WorkflowVariableRef, keyword: string): boolean {
  if (!keyword) return true;
  const haystack = [ref.path, ref.label, ref.description].filter(Boolean).join(' ').toLowerCase();
  return haystack.includes(keyword.toLowerCase());
}

export const VariablePicker: React.FC<VariablePickerProps> = ({
  groups,
  mode,
  onInsert,
  disabled,
  buttonText,
  size = 'small',
}) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [keyword, setKeyword] = useState('');

  const filtered = useMemo(
    () =>
      groups
        .map((group) => ({ ...group, refs: group.refs.filter((ref) => matches(ref, keyword)) }))
        .filter((group) => group.refs.length > 0),
    [groups, keyword]
  );
  const total = groups.reduce((sum, group) => sum + group.refs.length, 0);

  const content = (
    <div className="wf-variable-picker" data-testid="variable-picker-panel">
      <Input
        allowClear
        size="small"
        prefix={<SearchOutlined />}
        placeholder={t('workflow.variable.search')}
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        aria-label={t('workflow.variable.search')}
      />
      <div className="wf-variable-list">
        {total === 0 && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span className="wf-variable-empty">
                {t('workflow.variable.empty')}
                <br />
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {t('workflow.variable.emptyHint')}
                </Typography.Text>
              </span>
            }
          />
        )}
        {total > 0 && filtered.length === 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('workflow.variable.noMatch')}
          </Typography.Text>
        )}
        {filtered.map((group) => (
          <div key={group.id} className="wf-variable-group">
            <div className="wf-variable-group-head">
              <Tag style={{ marginInlineEnd: 6 }}>{t(GROUP_KIND_KEY[group.kind])}</Tag>
              <span className="wf-variable-group-label">{group.label}</span>
            </div>
            {group.refs.map((ref) => {
              const insert = formatVariableInsert(ref.path, mode);
              return (
                <button
                  key={`${group.id}:${ref.path}`}
                  type="button"
                  className="wf-variable-item"
                  data-testid={variableTestId(ref.path)}
                  title={`${insert}${ref.description ? ` · ${ref.description}` : ''}`}
                  onClick={() => {
                    onInsert(ref.path);
                    setOpen(false);
                  }}
                >
                  <span className="wf-variable-path">{insert}</span>
                  <span className="wf-variable-meta">
                    {ref.opaque && <Badge status="default" text={t('workflow.variable.opaque')} />}
                    {ref.type && <Tag color={TYPE_COLOR[ref.type] || 'default'}>{t(`workflow.variable.type.${ref.type}`)}</Tag>}
                    {ref.required && <Tag color="red">{t('workflow.variable.required')}</Tag>}
                  </span>
                </button>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setKeyword('');
      }}
      trigger="click"
      placement="bottomRight"
      content={content}
    >
      <Button
        size={size}
        type="text"
        disabled={disabled}
        data-testid="variable-picker"
        icon={<span className="wf-variable-token">{'${…}'}</span>}
      >
        {buttonText || t('workflow.variable.insert')}
      </Button>
    </Popover>
  );
};

/* ── 输入框包装：在光标位置插入（Input / TextArea 通用） ────────────── */

export interface VariableInputProps {
  value?: string;
  onChange?: (value: string) => void;
  groups: WorkflowVariableGroup[];
  mode: VariableInsertMode;
  /** antd Form.Item 注入的 id（label htmlFor 依赖它） */
  id?: string;
  ariaLabel?: string;
  placeholder?: string;
  multiline?: boolean;
  rows?: number;
  className?: string;
  monospace?: boolean;
  disabled?: boolean;
}

/**
 * 受控文本输入 + 变量选择器。antd Form.Item 注入 value/onChange；
 * 点击变量后把 `${path}` / `path` 插到光标处，并恢复光标到插入文本之后。
 */
export const VariableInput: React.FC<VariableInputProps> = ({
  value = '',
  onChange,
  groups,
  mode,
  id,
  ariaLabel,
  placeholder,
  multiline,
  rows = 4,
  className,
  monospace,
  disabled,
}) => {
  const { t } = useTranslation();
  const elementRef = useRef<HTMLInputElement | HTMLTextAreaElement | null>(null);
  const setInputRef = useCallback((instance: { input?: HTMLInputElement | null } | null) => {
    elementRef.current = instance?.input ?? null;
  }, []);
  const setTextAreaRef = useCallback(
    (instance: { resizableTextArea?: { textArea: HTMLTextAreaElement } | null } | null) => {
      elementRef.current = instance?.resizableTextArea?.textArea ?? null;
    },
    []
  );

  const insert = (path: string) => {
    const text = formatVariableInsert(path, mode);
    const element = elementRef.current;
    const start = element?.selectionStart ?? value.length;
    const end = element?.selectionEnd ?? value.length;
    const next = `${value.slice(0, start)}${text}${value.slice(end)}`;
    onChange?.(next);
    if (element) {
      const cursor = start + text.length;
      window.requestAnimationFrame(() => {
        element.focus();
        element.setSelectionRange(cursor, cursor);
      });
    }
  };

  const shared = {
    id,
    value,
    disabled,
    placeholder,
    'aria-label': ariaLabel,
    className,
    style: monospace ? { fontFamily: 'monospace', fontSize: 12 } : undefined,
  };

  return (
    <div className="wf-variable-field">
      {multiline ? (
        <Input.TextArea
          {...shared}
          rows={rows}
          ref={setTextAreaRef}
          onChange={(event) => onChange?.(event.target.value)}
        />
      ) : (
        <Input
          {...shared}
          ref={setInputRef}
          onChange={(event) => onChange?.(event.target.value)}
        />
      )}
      <div className="wf-variable-field-actions">
        <VariablePicker groups={groups} mode={mode} onInsert={insert} disabled={disabled} buttonText={t('workflow.variable.insert')} />
      </div>
    </div>
  );
};

export default VariablePicker;
