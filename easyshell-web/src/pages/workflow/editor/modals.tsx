import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ValidationIssue, WorkflowDefinition, WorkflowNode } from '../types';
import { sourceHandlesForNode } from '../graph';

const { Text } = Typography;

/* ── 发布弹窗：issues 列表（ERROR 红 / WARNING 黄） ───────────────── */

export interface PublishModalProps {
  open: boolean;
  issues: ValidationIssue[];
  /** 只有 WARNING 时才允许继续发布 */
  onCancel: () => void;
  onConfirm: () => void;
  onRevalidate: () => void;
  loading: boolean;
}

export const PublishModal: React.FC<PublishModalProps> = ({ open, issues, onCancel, onConfirm, onRevalidate, loading }) => {
  const { t } = useTranslation();
  const errors = issues.filter((i) => i.severity === 'ERROR');
  const warnings = issues.filter((i) => i.severity === 'WARNING');

  return (
    <Modal
      title={t('workflow.publish.title')}
      open={open}
      onCancel={onCancel}
      footer={
        <Space>
          <Button onClick={onCancel}>{t('common.cancel')}</Button>
          <Button icon={<ReloadOutlined />} onClick={onRevalidate} loading={loading}>
            {t('workflow.publish.revalidate')}
          </Button>
          <Button type="primary" danger={errors.length > 0} onClick={onConfirm} loading={loading} disabled={errors.length > 0}>
            {errors.length > 0 ? t('workflow.publish.blocked') : t('workflow.publish.confirm')}
          </Button>
        </Space>
      }
      width={640}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {errors.length === 0 && warnings.length === 0 && (
          <Alert type="success" showIcon message={t('workflow.publish.noIssues')} />
        )}
        {errors.length > 0 && (
          <Alert
            type="error"
            showIcon
            message={t('workflow.publish.errorCount', { count: errors.length })}
            description={<Text type="secondary">{t('workflow.publish.errorHint')}</Text>}
          />
        )}
        <div className="wf-issue-list">
          {[...errors, ...warnings].map((issue, index) => (
            <div
              key={`${issue.code}-${issue.nodeKey || 'definition'}-${index}`}
              className={`wf-issue wf-issue-${issue.severity.toLowerCase()}`}
            >
              <Space size={8} wrap>
                <Tag color={issue.severity === 'ERROR' ? 'red' : 'orange'}>{issue.severity}</Tag>
                <Text code>{issue.code}</Text>
                {issue.nodeKey && <Tag>{issue.nodeKey}</Tag>}
              </Space>
              <div>{issue.message}</div>
            </div>
          ))}
        </div>
      </Space>
    </Modal>
  );
};

/* ── 触发弹窗：input 表单 + Idempotency-Key ───────────────────────── */

export interface TriggerModalProps {
  open: boolean;
  definition: WorkflowDefinition;
  workflowName: string;
  onCancel: () => void;
  onSubmit: (inputs: Record<string, unknown>, idempotencyKey: string) => void;
  loading: boolean;
}

/**
 * 触发弹窗的表单值：入参变量（schema 限定 `^[a-z][a-z0-9_]{0,63}$`，不可能以 `_` 开头）
 * + 由 antd 托管的幂等键字段。
 */
interface TriggerFormValues extends Record<string, unknown> {
  __idem?: string;
}

function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `idem-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

/** antd `validateFields` 的 reject 形如 `{ errorFields, values, outOfDate }`，据此区分校验失败与其它异常 */
function isValidationRejection(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'errorFields' in error;
}

/** JSON 入参编辑框的初值：对象/数组序列化成文本，字符串原样展示 */
function jsonInitialValue(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '';
  }
}

export const TriggerModal: React.FC<TriggerModalProps> = ({
  open, definition, workflowName, onCancel, onSubmit, loading,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<TriggerFormValues>();

  // 每次打开弹窗都换一把新幂等键（字段由 antd 托管，直接写 store，Input 同步刷新）
  useEffect(() => {
    if (open) form.setFieldValue('__idem', newIdempotencyKey());
  }, [open, form]);

  const variables = useMemo(() => Object.entries(definition.variables || {}), [definition.variables]);

  const handleOk = async () => {
    try {
      // 无入参变量时表单同样已挂载（至少含幂等键字段），validateFields 直接放行，inputs 为 {}
      const { __idem, ...raw } = await form.validateFields();
      const inputs: Record<string, unknown> = { ...raw };
      // JSON 字段在 TextArea 里是文本；提交前转成对象/数组，空串表示未提供
      for (const [name, spec] of variables) {
        if (spec.type !== 'json') continue;
        const value = inputs[name];
        if (typeof value === 'string') {
          inputs[name] = value.trim() ? JSON.parse(value) : undefined;
        }
      }
      onSubmit(inputs, __idem ?? '');
    } catch (error) {
      // 校验失败（含幂等键被清空）或提交回调抛错：都必须给用户可见反馈，绝不再静默吞掉
      message.error(
        isValidationRejection(error) ? t('workflow.trigger.validationFailed') : t('workflow.trigger.failed'),
      );
    }
  };

  return (
    <Modal
      title={t('workflow.trigger.title', { name: workflowName })}
      open={open}
      onCancel={onCancel}
      onOk={() => void handleOk()}
      confirmLoading={loading}
      okText={t('workflow.trigger.submit')}
      cancelText={t('common.cancel')}
      width={560}
    >
      <Form form={form} layout="vertical" requiredMark="optional">
        {variables.length === 0 && (
          <Alert type="info" showIcon message={t('workflow.trigger.noVariables')} />
        )}
        {variables.map(([name, spec]) => (
          <Form.Item
            key={name}
            name={name}
            data-testid={`trigger-variable-${name}`}
            label={
              <Space size={6} wrap>
                <span>{spec.label || name}</span>
                {spec.label && spec.label !== name && <Text code>{name}</Text>}
                <Tag>{spec.type}</Tag>
                {spec.required && <Tag color="red">{t('workflow.trigger.required')}</Tag>}
              </Space>
            }
            tooltip={spec.description}
            rules={[
              { required: spec.required, message: t('workflow.trigger.required') },
              ...(spec.type === 'json'
                ? [
                    {
                      validator: (_rule: unknown, value: unknown) => {
                        if (typeof value !== 'string' || !value.trim()) return Promise.resolve();
                        try {
                          JSON.parse(value);
                          return Promise.resolve();
                        } catch {
                          return Promise.reject(new Error(t('workflow.trigger.invalidJson')));
                        }
                      },
                    },
                  ]
                : []),
            ]}
            initialValue={spec.type === 'json' ? jsonInitialValue(spec.default) : spec.default}
          >
            {spec.type === 'string' && (spec.options?.length ?? 0) > 0 ? (
              <Select
                options={(spec.options || []).map((option) => ({ value: option, label: option }))}
                showSearch
                optionFilterProp="label"
                placeholder={spec.description || t('workflow.trigger.selectPlaceholder')}
              />
            ) : spec.type === 'number' ? (
              <InputNumber style={{ width: '100%' }} />
            ) : spec.type === 'boolean' ? (
              <Switch />
            ) : spec.type === 'json' ? (
              <Input.TextArea rows={4} style={{ fontFamily: 'monospace', fontSize: 12 }} />
            ) : (
              <Input />
            )}
          </Form.Item>
        ))}
        <Form.Item
          label={t('workflow.trigger.idempotencyKey')}
          tooltip={t('workflow.trigger.idempotencyHint')}
          required
          style={{ marginTop: 8 }}
        >
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item
              name="__idem"
              noStyle
              initialValue={newIdempotencyKey()}
              rules={[{ required: true, message: t('workflow.trigger.required') }]}
            >
              <Input />
            </Form.Item>
            <Button icon={<ReloadOutlined />} onClick={() => form.setFieldValue('__idem', newIdempotencyKey())} />
          </Space.Compact>
        </Form.Item>
      </Form>
    </Modal>
  );
};

/* ── 409 三选一（不做自动合并，设计 §4.3） ─────────────────────────── */

export interface VersionConflictModalProps {
  open: boolean;
  onCancel: () => void;
  onOverwrite: () => void;
  onSaveCopy: () => void;
  onDiscard: () => void;
  busy: boolean;
}

export const VersionConflictModal: React.FC<VersionConflictModalProps> = ({
  open, onCancel, onOverwrite, onSaveCopy, onDiscard, busy,
}) => {
  const { t } = useTranslation();
  return (
    <Modal
      title={t('workflow.conflict.title')}
      open={open}
      onCancel={onCancel}
      footer={<Button onClick={onCancel}>{t('common.cancel')}</Button>}
      width={560}
    >
      <Alert type="warning" showIcon message={t('workflow.conflict.message')} style={{ marginBottom: 16 }} />
      <Space direction="vertical" style={{ width: '100%' }}>
        <Button block type="primary" loading={busy} onClick={onOverwrite}>
          {t('workflow.conflict.overwrite')}
        </Button>
        <Button block onClick={onSaveCopy}>
          {t('workflow.conflict.saveCopy')}
        </Button>
        <Button block danger onClick={onDiscard}>
          {t('workflow.conflict.discard')}
        </Button>
      </Space>
    </Modal>
  );
};

/* ── 连线条件编辑（双击边打开） ───────────────────────────────────── */

export interface EdgeEditModalProps {
  open: boolean;
  edge: {
    id: string;
    from: string;
    to: string;
    sourceHandle?: string | null;
    condition?: string;
    onUpstreamFailure?: 'abort' | 'skip' | 'partial';
  } | null;
  nodes: WorkflowNode[];
  onCancel: () => void;
  onSave: (edgeId: string, patch: { condition?: string; onUpstreamFailure?: 'abort' | 'skip' | 'partial' }) => void;
}

export const EdgeEditModal: React.FC<EdgeEditModalProps> = ({ open, edge, nodes, onCancel, onSave }) => {
  const { t } = useTranslation();
  const [condition, setCondition] = useState('');
  const [onUpstreamFailure, setOnUpstreamFailure] = useState<'abort' | 'skip' | 'partial'>('skip');

  useEffect(() => {
    if (open && edge) {
      setCondition(edge.condition || '');
      setOnUpstreamFailure(edge.onUpstreamFailure || 'skip');
    }
  }, [open, edge]);

  const sourceNode = nodes.find((n) => n.key === edge?.from);
  const sourceType = sourceNode?.type;
  // condition 的 IF/ELSE、classifier 的 class/default 都由 sourceHandle 真实门控（§2.2）：
  // 不再要求用户把表达式/分类写两遍，边编辑弹窗直接展示分支。
  const conditionGated = sourceType === 'condition';
  const classifierGated = sourceType === 'classifier';
  const branchGated = conditionGated || classifierGated;
  const handleCount = sourceNode ? sourceHandlesForNode(sourceNode).length : 1;
  const branchLabel = edge?.sourceHandle || (classifierGated ? 'default' : 'true');

  return (
    <Modal
      title={t('workflow.edge.title', { from: edge?.from, to: edge?.to })}
      open={open}
      onCancel={onCancel}
      onOk={() =>
        edge &&
        onSave(edge.id, {
          condition: branchGated ? undefined : condition.trim() || undefined,
          onUpstreamFailure,
        })
      }
      okText={t('common.save')}
      cancelText={t('common.cancel')}
    >
      {conditionGated && (
        <Alert
          type="info"
          showIcon
          message={t('workflow.edge.conditionGatedTitle')}
          description={
            <Space direction="vertical" size={4}>
              <span>{t('workflow.edge.conditionGatedDesc')}</span>
              <Space size={6}>
                <span>{t('workflow.edge.sourceBranch')}:</span>
                <Tag color={edge?.sourceHandle === 'false' ? 'orange' : 'green'}>
                  {edge?.sourceHandle === 'false' ? 'ELSE' : 'IF'}
                </Tag>
                <Text code>{branchLabel}</Text>
              </Space>
            </Space>
          }
          style={{ marginBottom: 12 }}
        />
      )}
      {classifierGated && (
        <Alert
          type="info"
          showIcon
          message={t('workflow.edge.classifierGatedTitle')}
          description={
            <Space direction="vertical" size={4}>
              <span>{t('workflow.edge.classifierGatedDesc')}</span>
              <Space size={6}>
                <span>{t('workflow.edge.sourceBranch')}:</span>
                <Tag color={branchLabel === 'default' ? 'default' : 'blue'}>{branchLabel}</Tag>
              </Space>
            </Space>
          }
          style={{ marginBottom: 12 }}
        />
      )}
      {!branchGated && (
        <Alert type="info" showIcon message={t('workflow.edge.conditionHint')} style={{ marginBottom: 12 }} />
      )}
      <Form layout="vertical">
        {!branchGated && (
          <Form.Item
            label={t('workflow.edge.condition')}
            tooltip={t('workflow.edge.conditionHint')}
            extra={handleCount > 1 ? t('workflow.edge.dualHandleHint') : undefined}
          >
            <Input
              value={condition}
              onChange={(event) => setCondition(event.target.value)}
              placeholder="nodes.n1.output.count > 0"
              style={{ fontFamily: 'monospace' }}
              aria-label={t('workflow.edge.condition')}
            />
          </Form.Item>
        )}
        <Form.Item label={t('workflow.edge.onUpstreamFailure')}>
          <Select
            value={onUpstreamFailure}
            onChange={(value) => setOnUpstreamFailure(value)}
            options={[
              { value: 'skip', label: 'skip' },
              { value: 'abort', label: 'abort' },
              { value: 'partial', label: 'partial' },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};
