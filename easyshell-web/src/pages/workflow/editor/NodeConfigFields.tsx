import React from 'react';
import { Button, Input, Select, Space, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  EMPTY_TARGETS,
  NOTIFY_CHANNELS,
  TARGETS_MODES,
  joinIdList,
  joinNumberList,
  parseIdList,
  parseNumberList,
  type TargetsMode,
  type TargetsValue,
} from './nodeConfig';

/**
 * 节点配置表单的复合控件：targets 寻址 + parameters 键值对 + notify 渠道多选。
 *
 * 这三个字段都不是标量，antd 的 `Form.Item` 托管不了（值形态是对象/数组，且
 * targets 要按 mode 动态显隐），所以与 `EdgeEditModal` 同一套路：受控 state +
 * `value`/`onChange`，由 `NodeFormPanel` 在提交时合成进 `node.config`。
 */

const { Text } = Typography;

/* ── targets 寻址表单 ───────────────────────────────────────────── */

export interface TargetsEditorProps {
  value?: TargetsValue;
  onChange?: (next: TargetsValue) => void;
}

/**
 * 寻址表单：mode 下拉 + 按 mode 动态显隐的对应字段。
 *
 * 只渲染当前 mode 的字段（`NodeFormPanel` 提交时也只写那一个键），
 * 这样 definition JSON 里不会残留"上次选过 cluster 留下的 clusterIds"。
 */
export const TargetsEditor: React.FC<TargetsEditorProps> = ({ value, onChange }) => {
  const { t } = useTranslation();
  const current = value || EMPTY_TARGETS;
  const patch = (next: Partial<TargetsValue>) => onChange?.({ ...current, ...next });

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Select
        value={current.mode}
        onChange={(mode: TargetsMode) => patch({ mode })}
        options={TARGETS_MODES.map((mode) => ({
          value: mode,
          label: t(`workflow.form.targetsMode.${mode}`),
        }))}
        style={{ width: '100%' }}
        aria-label={t('workflow.form.targets')}
      />
      {current.mode === 'explicit' && (
        <Input
          value={joinIdList(current.agentIds)}
          onChange={(event) => patch({ agentIds: parseIdList(event.target.value) })}
          placeholder="agent-1, agent-2"
          aria-label={t('workflow.form.agentIds')}
        />
      )}
      {current.mode === 'cluster' && (
        <Input
          value={joinNumberList(current.clusterIds)}
          onChange={(event) => patch({ clusterIds: parseNumberList(event.target.value) })}
          placeholder="1, 2"
          aria-label={t('workflow.form.clusterIds')}
        />
      )}
      {current.mode === 'tag' && (
        <Input
          value={joinNumberList(current.tagIds)}
          onChange={(event) => patch({ tagIds: parseNumberList(event.target.value) })}
          placeholder="3, 7"
          aria-label={t('workflow.form.tagIds')}
        />
      )}
      {current.mode === 'variable' && (
        <Input
          value={current.variableRef}
          onChange={(event) => patch({ variableRef: event.target.value })}
          placeholder="nodes.pick.output.hosts"
          style={{ fontFamily: 'monospace' }}
          aria-label={t('workflow.form.variableRef')}
        />
      )}
    </Space>
  );
};

/* ── parameters 键值对编辑器 ────────────────────────────────────── */

export interface ParametersEditorProps {
  value?: Record<string, string>;
  onChange?: (next: Record<string, string>) => void;
  /** 整组的可访问名（Form.Item 没有 name 时 antd 不会把 label 绑到控件上） */
  ariaLabel?: string;
}

/**
 * 键值对编辑器：键 = 脚本里的 `{{NAME}}` 槽位名，值 = 字面量或 `${path}` 表达式。
 *
 * 空键行是"刚点添加、还没取名"的中间态，保留在表单 state 里让用户能继续输入；
 * 落进 definition 前由 `NodeFormPanel` 统一滤掉（服务端把空键当噪声）。
 * 已有空键行时不允许再添加—— Record 装不下两个空键，加了也会被 quietly 吞掉一行。
 */
export const ParametersEditor: React.FC<ParametersEditorProps> = ({ value, onChange, ariaLabel }) => {
  const { t } = useTranslation();
  const entries = Object.entries(value || {});
  const hasUnnamed = entries.some(([key]) => !key.trim());

  const emit = (next: Array<[string, string]>) => {
    const record: Record<string, string> = {};
    for (const [key, item] of next) record[key] = item;
    onChange?.(record);
  };

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }} role="group" aria-label={ariaLabel}>
      {entries.length === 0 && <Text type="secondary">{t('workflow.form.parametersEmpty')}</Text>}
      {entries.map(([key, item], index) => (
        <Space key={index} size={4} style={{ width: '100%' }} align="start">
          <Input
            value={key}
            onChange={(event) => {
              const next = [...entries];
              next[index] = [event.target.value, item];
              emit(next);
            }}
            placeholder={t('workflow.form.parameterKey')}
            style={{ width: 120, fontFamily: 'monospace' }}
            aria-label={t('workflow.form.parameterKey')}
          />
          <Input
            value={item}
            onChange={(event) => {
              const next = [...entries];
              next[index] = [key, event.target.value];
              emit(next);
            }}
            placeholder="${nodes.n1.output.x}"
            style={{ flex: 1, fontFamily: 'monospace', fontSize: 12 }}
            aria-label={t('workflow.form.parameterValue')}
          />
          <Button
            danger
            icon={<DeleteOutlined />}
            aria-label={t('common.remove')}
            onClick={() => emit(entries.filter((_, position) => position !== index))}
          />
        </Space>
      ))}
      <Button
        block
        icon={<PlusOutlined />}
        disabled={hasUnnamed}
        onClick={() => emit([...entries, ['', '']])}
      >
        {t('workflow.form.addParameter')}
      </Button>
    </Space>
  );
};

/* ── notify 渠道多选 ────────────────────────────────────────────── */

export interface ChannelsEditorProps {
  value?: string[];
  onChange?: (next: string[]) => void;
}

/** 渠道多选：选项就是服务端 6 个真实 key，不提供自由输入（拼错 = 运行期 skipped） */
export const ChannelsEditor: React.FC<ChannelsEditorProps> = ({ value, onChange }) => {
  const { t } = useTranslation();
  const selected = value || [];
  return (
    <Select
      mode="multiple"
      value={selected}
      onChange={(next: string[]) => onChange?.(next)}
      options={NOTIFY_CHANNELS.map((channel) => ({
        value: channel,
        label: t(`workflow.form.channelOption.${channel}`),
      }))}
      style={{ width: '100%' }}
      aria-label={t('workflow.form.channels')}
    />
  );
};
