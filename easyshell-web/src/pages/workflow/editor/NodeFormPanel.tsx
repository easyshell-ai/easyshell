import React, { useEffect, useMemo } from 'react';
import {
  Button,
  Col,
  Drawer,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Tag,
  Typography,
  Divider,
  Alert,
} from 'antd';
import { DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { theme } from 'antd';
import type { WorkflowNode, WorkflowNodeType } from '../types';
import { nodeHeight, NODE_WIDTH, sourceHandles } from '../graph';

/**
 * 配置表单 = 被选中节点的 config 编辑器（设计 §4.2 双编辑器硬规则）。
 *
 * 这里**不提供**新增/删除节点入口：结构变更唯一路径是画布（或画布的键盘等价操作），
 * 否则同一份 definition 有两个写入路径，409 冲突与 dirty 追踪都会出错。
 */

const { Text } = Typography;

export interface NodeFormPanelProps {
  node: WorkflowNode | null;
  open: boolean;
  onClose: () => void;
  onSave: (key: string, patch: Partial<WorkflowNode>) => void;
  onRequestDelete: (key: string) => void;
}

interface FormValues {
  name: string;
  scriptContent?: string;
  scriptId?: string;
  expression?: string;
  channel?: string;
  message?: string;
  mode?: 'confirm' | 'input';
  title?: string;
  durationSec?: number;
  outputVar?: string;
  retryMax?: number;
  retryIntervalSec?: number;
  timeoutSec?: number | null;
  onFailure?: WorkflowNode['onFailure'];
  onUnsafeValue?: WorkflowNode['onUnsafeValue'];
  riskPolicy?: WorkflowNode['riskPolicy'];
}

const NodeFormPanel: React.FC<NodeFormPanelProps> = ({ node, open, onClose, onSave, onRequestDelete }) => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [form] = Form.useForm<FormValues>();
  const nodeType: WorkflowNodeType | undefined = node?.type;

  const initialValues = useMemo<FormValues | undefined>(() => {
    if (!node) return undefined;
    const config = node.config || {};
    return {
      name: node.name,
      scriptContent: typeof config.scriptContent === 'string' ? config.scriptContent : '',
      scriptId: typeof config.scriptId === 'string' ? config.scriptId : '',
      expression: typeof config.expression === 'string' ? config.expression : '',
      channel: typeof config.channel === 'string' ? config.channel : 'webhook',
      message: typeof config.message === 'string' ? config.message : '',
      mode: config.mode === 'input' ? 'input' : 'confirm',
      title: typeof config.title === 'string' ? config.title : '',
      durationSec: typeof config.durationSec === 'number' ? config.durationSec : 60,
      outputVar: node.outputVar || '',
      retryMax: node.retryMax ?? 0,
      retryIntervalSec: node.retryIntervalSec ?? 5,
      timeoutSec: node.timeoutSec ?? null,
      onFailure: node.onFailure || 'abort',
      onUnsafeValue: node.onUnsafeValue || 'reject',
      riskPolicy: node.riskPolicy || 'block',
    };
  }, [node]);

  useEffect(() => {
    if (open && initialValues) form.setFieldsValue(initialValues);
  }, [open, initialValues, form]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (!node) return;
    const config: Record<string, unknown> = { ...(node.config || {}) };
    if (nodeType === 'script-exec') {
      config.scriptContent = values.scriptContent || '';
      if (values.scriptId) config.scriptId = values.scriptId;
    }
    if (nodeType === 'condition') config.expression = values.expression || '';
    if (nodeType === 'notify') {
      config.channel = values.channel || 'webhook';
      config.message = values.message || '';
    }
    if (nodeType === 'approval') {
      config.mode = values.mode || 'confirm';
      config.title = values.title || '';
    }
    if (nodeType === 'wait') config.durationSec = values.durationSec ?? 60;

    onSave(node.key, {
      name: values.name,
      config,
      outputVar: values.outputVar || undefined,
      retryMax: values.retryMax ?? 0,
      retryIntervalSec: values.retryIntervalSec ?? 5,
      timeoutSec: values.timeoutSec ?? null,
      onFailure: values.onFailure,
      onUnsafeValue: values.onUnsafeValue,
      riskPolicy: values.riskPolicy,
    });
  };

  return (
    <Drawer
      title={
        <Space>
          <span>{node ? node.name : t('workflow.form.title')}</span>
          {node && <Tag>{t(`workflow.node.type.${node.type}`)}</Tag>}
          {node && <Text type="secondary" code>{node.key}</Text>}
        </Space>
      }
      open={open}
      onClose={onClose}
      width={420}
      extra={
        node && (
          <Space>
            <Button icon={<SaveOutlined />} type="primary" onClick={handleSubmit}>
              {t('common.save')}
            </Button>
            <Button danger icon={<DeleteOutlined />} onClick={() => onRequestDelete(node.key)}>
              {t('common.delete')}
            </Button>
          </Space>
        )
      }
    >
      {node && nodeType ? (
        <Form form={form} layout="vertical" initialValues={initialValues} requiredMark={false}>
          <Form.Item name="name" label={t('workflow.form.name')} rules={[{ required: true, message: t('workflow.form.nameRequired') }]}>
            <Input />
          </Form.Item>

          {nodeType === 'script-exec' && (
            <>
              <Form.Item name="scriptId" label={t('workflow.form.scriptId')}>
                <Input placeholder={t('workflow.form.scriptIdPlaceholder')} />
              </Form.Item>
              <Form.Item
                name="scriptContent"
                label={t('workflow.form.scriptContent')}
                rules={[{ required: true, message: t('workflow.form.scriptContentRequired') }]}
                tooltip={t('workflow.form.scriptContentHint')}
              >
                <Input.TextArea rows={8} style={{ fontFamily: 'monospace', fontSize: 12 }} />
              </Form.Item>
              <Alert type="warning" showIcon message={t('workflow.form.riskHint')} style={{ marginBottom: 16 }} />
            </>
          )}

          {nodeType === 'condition' && (
            <Form.Item
              name="expression"
              label={t('workflow.form.expression')}
              rules={[{ required: true, message: t('workflow.form.expressionRequired') }]}
              tooltip={t('workflow.form.expressionHint')}
            >
              <Input placeholder="nodes.n1.output.count > 0" style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          )}

          {nodeType === 'notify' && (
            <>
              <Form.Item name="channel" label={t('workflow.form.channel')}>
                <Select
                  options={[
                    { value: 'webhook', label: 'Webhook' },
                    { value: 'email', label: 'Email' },
                    { value: 'bot', label: t('nav.system_bot') },
                  ]}
                />
              </Form.Item>
              <Form.Item name="message" label={t('workflow.form.message')} rules={[{ required: true }]}>
                <Input.TextArea rows={4} />
              </Form.Item>
            </>
          )}

          {nodeType === 'approval' && (
            <>
              <Form.Item name="mode" label={t('workflow.form.approvalMode')}>
                <Select
                  options={[
                    { value: 'confirm', label: t('workflow.form.approvalModeConfirm') },
                    { value: 'input', label: t('workflow.form.approvalModeInput') },
                  ]}
                />
              </Form.Item>
              <Form.Item name="title" label={t('workflow.form.approvalTitle')} rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </>
          )}

          {nodeType === 'wait' && (
            <Form.Item name="durationSec" label={t('workflow.form.durationSec')} rules={[{ required: true }]}>
              <InputNumber min={1} max={86400} style={{ width: '100%' }} addonAfter={t('common.seconds')} />
            </Form.Item>
          )}

          <Divider style={{ marginTop: 0 }}>{t('workflow.form.advanced')}</Divider>

          <Form.Item name="outputVar" label={t('workflow.form.outputVar')} tooltip={t('workflow.form.outputVarHint')}>
            <Input placeholder="result" style={{ fontFamily: 'monospace' }} />
          </Form.Item>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="retryMax" label={t('workflow.form.retryMax')}>
                <InputNumber min={0} max={10} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="retryIntervalSec" label={t('workflow.form.retryInterval')}>
                <InputNumber min={1} max={3600} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="timeoutSec" label={t('workflow.form.timeout')}>
                <InputNumber min={1} max={86400} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="onFailure" label={t('workflow.form.onFailure')}>
                <Select
                  options={[
                    { value: 'abort', label: 'abort' },
                    { value: 'skip', label: 'skip' },
                    { value: 'partial', label: 'partial' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="onUnsafeValue" label={t('workflow.form.onUnsafeValue')}>
                <Select
                  options={[
                    { value: 'reject', label: 'reject' },
                    { value: 'raw', label: 'raw' },
                    { value: 'truncate', label: 'truncate' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="riskPolicy" label={t('workflow.form.riskPolicy')}>
                <Select
                  options={[
                    { value: 'block', label: 'block' },
                    { value: 'allow_medium', label: 'allow_medium' },
                    { value: 'strict', label: 'strict' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>

          <div style={{ color: token.colorTextTertiary, fontSize: 12 }}>
            {t('workflow.form.nodeShape', { width: NODE_WIDTH, height: nodeHeight(nodeType) })}
            {' · '}
            {t('workflow.form.handles', {
              in: 1,
              out: sourceHandles(nodeType).length,
            })}
          </div>
        </Form>
      ) : (
        <Text type="secondary">{t('workflow.form.empty')}</Text>
      )}
    </Drawer>
  );
};

export default NodeFormPanel;
