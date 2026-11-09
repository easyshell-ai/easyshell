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
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ValidationIssue, WorkflowDefinition, WorkflowNode } from '../types';
import { canSetEdgeCondition, sourceHandles } from '../graph';

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

function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `idem-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

export const TriggerModal: React.FC<TriggerModalProps> = ({
  open, definition, workflowName, onCancel, onSubmit, loading,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<Record<string, unknown>>();
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);

  useEffect(() => {
    if (open) setIdempotencyKey(newIdempotencyKey());
  }, [open]);

  const variables = useMemo(() => Object.entries(definition.variables || {}), [definition.variables]);

  return (
    <Modal
      title={t('workflow.trigger.title', { name: workflowName })}
      open={open}
      onCancel={onCancel}
      onOk={() => form.validateFields().then((values) => onSubmit(values, idempotencyKey))}
      confirmLoading={loading}
      okText={t('workflow.trigger.submit')}
      cancelText={t('common.cancel')}
      width={560}
    >
      {variables.length === 0 ? (
        <Alert type="info" showIcon message={t('workflow.trigger.noVariables')} />
      ) : (
        <Form form={form} layout="vertical" requiredMark="optional">
          {variables.map(([name, spec]) => (
            <Form.Item
              key={name}
              name={name}
              label={
                <Space size={6}>
                  <Text code>{name}</Text>
                  <Tag>{spec.type}</Tag>
                  {spec.required && <Tag color="red">{t('workflow.trigger.required')}</Tag>}
                </Space>
              }
              tooltip={spec.description}
              rules={[{ required: spec.required, message: t('workflow.trigger.required') }]}
              initialValue={spec.default}
            >
              {spec.type === 'number' ? (
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
        </Form>
      )}
      <Form layout="vertical" style={{ marginTop: 8 }}>
        <Form.Item
          label={t('workflow.trigger.idempotencyKey')}
          tooltip={t('workflow.trigger.idempotencyHint')}
          required
        >
          <Space.Compact style={{ width: '100%' }}>
            <Input value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} />
            <Button icon={<ReloadOutlined />} onClick={() => setIdempotencyKey(newIdempotencyKey())} />
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

  const sourceType = nodes.find((n) => n.key === edge?.from)?.type;
  const conditionLocked =
    !!edge && !!sourceType && !canSetEdgeCondition(nodes, { from: edge.from, sourceHandle: edge.sourceHandle });
  const handleCount = sourceType ? sourceHandles(sourceType).length : 1;

  return (
    <Modal
      title={t('workflow.edge.title', { from: edge?.from, to: edge?.to })}
      open={open}
      onCancel={onCancel}
      onOk={() => edge && onSave(edge.id, { condition: condition.trim() || undefined, onUpstreamFailure })}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
    >
      {conditionLocked && <Alert type="info" showIcon message={t('workflow.edge.falseBranchLocked')} style={{ marginBottom: 12 }} />}
      <Form layout="vertical">
        <Form.Item
          label={t('workflow.edge.condition')}
          tooltip={t('workflow.edge.conditionHint')}
          extra={handleCount > 1 ? t('workflow.edge.dualHandleHint') : undefined}
        >
          <Input
            value={condition}
            disabled={conditionLocked}
            onChange={(event) => setCondition(event.target.value)}
            placeholder="nodes.n1.output.count > 0"
            style={{ fontFamily: 'monospace' }}
          />
        </Form.Item>
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
