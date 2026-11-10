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
import type { WorkflowVariableGroup } from '../variables';
import { VariableInput } from './inspector/VariablePicker';

/**
 * 节点配置表单的复合控件：targets 寻址 + parameters 键值对 + notify 渠道多选。
 *
 * 这三个字段都不是标量，antd 的 `Form.Item` 托管不了（值形态是对象/数组，且
 * targets 要按 mode 动态显隐），所以与 `EdgeEditModal` 同一套路：受控 state +
 * `value`/`onChange`，由 `NodeFormPanel` 在提交时合成进 `node.config`。
 */

const { Text } = Typography;

/* ── targets 寻址表单 ───────────────────────────────────────────── */

export interface TargetOption {
  value: string;
  label: string;
}

export interface TargetOptions {
  hosts?: TargetOption[];
  clusters?: TargetOption[];
  tags?: TargetOption[];
  /** 某个列表 API 失败时，该字段回落到自由输入（不阻断编辑） */
  fallbackHosts?: boolean;
  fallbackClusters?: boolean;
  fallbackTags?: boolean;
}

export interface TargetsEditorProps {
  value?: TargetsValue;
  onChange?: (next: TargetsValue) => void;
  options?: TargetOptions;
  groups?: WorkflowVariableGroup[];
}

/**
 * 寻址表单：mode 下拉 + 按 mode 动态显隐的对应字段。
 *
 * 只渲染当前 mode 的字段（`NodeFormPanel` 提交时也只写那一个键），
 * 这样 definition JSON 里不会残留"上次选过 cluster 留下的 clusterIds"。
 *
 * 真实下拉由 getHostList / getClusterList / getTagList 提供；接口失败或未启用
 * 远程选项时退回原有逗号分隔输入，保证离线/测试环境仍能编辑。
 */
export const TargetsEditor: React.FC<TargetsEditorProps> = ({ value, onChange, options, groups }) => {
  const { t } = useTranslation();
  const current = value || EMPTY_TARGETS;
  const patch = (next: Partial<TargetsValue>) => onChange?.({ ...current, ...next });

  const hostOptions = options?.hosts;
  const clusterOptions = options?.clusters;
  const tagOptions = options?.tags;

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
      {current.mode === 'explicit' &&
        (hostOptions && !options?.fallbackHosts ? (
          <Select
            mode="multiple"
            value={current.agentIds}
            onChange={(agentIds: string[]) => patch({ agentIds })}
            options={hostOptions}
            placeholder={t('workflow.form.agentIdsPlaceholder')}
            style={{ width: '100%' }}
            aria-label={t('workflow.form.agentIds')}
            showSearch
            optionFilterProp="label"
          />
        ) : (
          <Input
            value={joinIdList(current.agentIds)}
            onChange={(event) => patch({ agentIds: parseIdList(event.target.value) })}
            placeholder="agent-1, agent-2"
            aria-label={t('workflow.form.agentIds')}
          />
        ))}
      {current.mode === 'cluster' &&
        (clusterOptions && !options?.fallbackClusters ? (
          <Select
            mode="multiple"
            value={current.clusterIds.map(String)}
            onChange={(ids: string[]) => patch({ clusterIds: ids.map(Number).filter(Number.isFinite) })}
            options={clusterOptions}
            placeholder={t('workflow.form.clusterIdsPlaceholder')}
            style={{ width: '100%' }}
            aria-label={t('workflow.form.clusterIds')}
            showSearch
            optionFilterProp="label"
          />
        ) : (
          <Input
            value={joinNumberList(current.clusterIds)}
            onChange={(event) => patch({ clusterIds: parseNumberList(event.target.value) })}
            placeholder="1, 2"
            aria-label={t('workflow.form.clusterIds')}
          />
        ))}
      {current.mode === 'tag' &&
        (tagOptions && !options?.fallbackTags ? (
          <Select
            mode="multiple"
            value={current.tagIds.map(String)}
            onChange={(ids: string[]) => patch({ tagIds: ids.map(Number).filter(Number.isFinite) })}
            options={tagOptions}
            placeholder={t('workflow.form.tagIdsPlaceholder')}
            style={{ width: '100%' }}
            aria-label={t('workflow.form.tagIds')}
            showSearch
            optionFilterProp="label"
          />
        ) : (
          <Input
            value={joinNumberList(current.tagIds)}
            onChange={(event) => patch({ tagIds: parseNumberList(event.target.value) })}
            placeholder="3, 7"
            aria-label={t('workflow.form.tagIds')}
          />
        ))}
      {current.mode === 'variable' && (
        <VariableInput
          value={current.variableRef}
          onChange={(variableRef) => patch({ variableRef })}
          groups={groups || []}
          mode="path"
          placeholder="nodes.pick.output.hosts"
          monospace
          ariaLabel={t('workflow.form.variableRef')}
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
  /** 上游可插入变量（值字段使用 VariablePicker） */
  groups?: WorkflowVariableGroup[];
  /** 文案覆盖（code 节点复用同一控件时用 inputs 口径） */
  addLabel?: string;
  emptyText?: string;
  keyPlaceholder?: string;
  keyAriaLabel?: string;
  valueAriaLabel?: string;
}

/**
 * 键值对编辑器：键 = 脚本里的 `{{NAME}}` 槽位名，值 = 字面量或 `${path}` 表达式。
 *
 * 空键行是"刚点添加、还没取名"的中间态，保留在表单 state 里让用户能继续输入；
 * 落进 definition 前由 `NodeFormPanel` 统一滤掉（服务端把空键当噪声）。
 * 已有空键行时不允许再添加—— Record 装不下两个空键，加了也会被 quietly 吞掉一行。
 */
export const ParametersEditor: React.FC<ParametersEditorProps> = ({
  value,
  onChange,
  ariaLabel,
  groups = [],
  addLabel,
  emptyText,
  keyPlaceholder,
  keyAriaLabel,
  valueAriaLabel,
}) => {
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
      {entries.length === 0 && <Text type="secondary">{emptyText || t('workflow.form.parametersEmpty')}</Text>}
      {entries.map(([key, item], index) => (
        <Space key={index} size={4} style={{ width: '100%' }} align="start">
          <Input
            value={key}
            onChange={(event) => {
              const next = [...entries];
              next[index] = [event.target.value, item];
              emit(next);
            }}
            placeholder={keyPlaceholder || t('workflow.form.parameterKey')}
            style={{ width: 120, fontFamily: 'monospace' }}
            aria-label={keyAriaLabel || t('workflow.form.parameterKey')}
          />
          <div style={{ flex: 1, minWidth: 0 }}>
            <VariableInput
              value={item}
              onChange={(next) => {
                const entries_ = [...entries];
                entries_[index] = [key, next];
                emit(entries_);
              }}
              groups={groups}
              mode="template"
              ariaLabel={valueAriaLabel || t('workflow.form.parameterValue')}
              placeholder="${nodes.n1.output.x}"
              monospace
            />
          </div>
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
        {addLabel || t('workflow.form.addParameter')}
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
