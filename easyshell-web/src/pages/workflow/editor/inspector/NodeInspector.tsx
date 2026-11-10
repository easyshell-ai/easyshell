import React, { useRef } from 'react';
import { Button, Space, Tabs, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { VariableSpec, WorkflowDefinition, WorkflowNode } from '../../types';
import NodeFormPanel from '../NodeFormPanel';
import { NodeTypeIcon } from '../NodeIcon';
import { NodeDocs } from './NodeDocs';
import { DataFlowTab } from './DataFlowTab';

/**
 * 节点检查器（设计 §3.1/§3.3）：配置 / 数据 / 说明三 Tab。
 *
 * 外层布局（420px 侧栏或窄屏 Drawer）由 editor/index.tsx 决定；本组件只负责内容，
 * 避免在检查器内部再套一层 Drawer 造成小屏双抽屉。
 */
export interface NodeInspectorProps {
  node: WorkflowNode;
  definition: WorkflowDefinition;
  onSave: (key: string, patch: Partial<WorkflowNode>) => void;
  onSaveMeta?: (variables: Record<string, VariableSpec>, label?: string) => void;
  onRequestDelete: (key: string) => void;
  remoteOptions?: boolean;
}

export const NodeInspector: React.FC<NodeInspectorProps> = ({
  node,
  definition,
  onSave,
  onSaveMeta,
  onRequestDelete,
  remoteOptions,
}) => {
  const { t } = useTranslation();
  const fixed = node.type === 'start' || node.type === 'end';
  const submitRef = useRef<(() => Promise<void>) | null>(null);

  return (
    <section className="wf-inspector" data-testid="node-inspector" aria-label={t('workflow.inspector.title')}>
      <header className="wf-inspector-head">
        <span className="wf-inspector-icon">
          <NodeTypeIcon type={node.type} />
        </span>
        <div className="wf-inspector-title">
          <Typography.Text strong ellipsis>
            {node.name}
          </Typography.Text>
          <div className="wf-inspector-sub">
            <Tag>{t(`workflow.node.type.${node.type}`)}</Tag>
            <Typography.Text code type="secondary">
              {node.key}
            </Typography.Text>
            {fixed && <Tag>{t('workflow.node.fixed')}</Tag>}
          </div>
        </div>
      </header>
      <Tabs
        className="wf-inspector-tabs"
        defaultActiveKey="config"
        items={[
          {
            key: 'config',
            label: <span data-testid="node-inspector-tab-config">{t('workflow.inspector.tabs.config')}</span>,
            children: (
              <NodeFormPanel
                node={node}
                open
                definition={definition}
                onSave={onSave}
                onSaveMeta={onSaveMeta}
                onRequestDelete={onRequestDelete}
                remoteOptions={remoteOptions}
                showFooter={false}
                submitRef={submitRef}
              />
            ),
          },
          {
            key: 'data',
            label: <span data-testid="node-inspector-tab-data">{t('workflow.inspector.tabs.data')}</span>,
            children: <DataFlowTab definition={definition} node={node} />,
          },
          {
            key: 'docs',
            label: <span data-testid="node-inspector-tab-docs">{t('workflow.inspector.tabs.docs')}</span>,
            children: <NodeDocs type={node.type} />,
          },
        ]}
      />
      <footer className="wf-inspector-footer wf-inspector-footer-bar">
        <Space>
          <Button icon={<SaveOutlined />} type="primary" onClick={() => void submitRef.current?.()}>
            {t('common.save')}
          </Button>
          <Tooltip title={fixed ? t('workflow.inspector.deleteDisabled') : undefined}>
            <Button danger icon={<DeleteOutlined />} disabled={fixed} onClick={() => onRequestDelete(node.key)}>
              {t('common.delete')}
            </Button>
          </Tooltip>
          {fixed && <Tag>{t('workflow.node.fixed')}</Tag>}
        </Space>
      </footer>
    </section>
  );
};

export default NodeInspector;
