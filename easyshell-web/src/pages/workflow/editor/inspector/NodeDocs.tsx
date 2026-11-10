import React from 'react';
import { Alert, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowNodeType } from '../../types';
import { nodeRegistryEntry } from '../nodeRegistry';

/**
 * 说明 Tab：节点用途 / 何时使用 / 输入输出 / 最小示例。
 * 文案全部走 workflow.node.docs.<type>.*，示例是代码（允许保留英文命令）。
 * code 节点同时给出 JavaScript 与 Python 两套 main 骨架。
 */
export interface NodeDocsProps {
  type: WorkflowNodeType;
}

const SECURITY_TYPES: WorkflowNodeType[] = ['ai-analyze', 'llm', 'classifier', 'code', 'approval', 'script-exec'];

export const NodeDocs: React.FC<NodeDocsProps> = ({ type }) => {
  const { t } = useTranslation();
  const base = `workflow.node.docs.${type}`;
  return (
    <div className="wf-node-docs" data-testid="node-docs">
      <div className="wf-docs-head">
        <Typography.Title level={5} style={{ margin: 0 }}>
          {t(`workflow.node.type.${type}`)}
        </Typography.Title>
        <Tag>{t(`workflow.node.category.${nodeRegistryEntry(type).category}`)}</Tag>
      </div>
      <Typography.Paragraph>{t(`${base}.purpose`)}</Typography.Paragraph>
      <Typography.Text strong>{t('workflow.inspector.docs.when')}</Typography.Text>
      <Typography.Paragraph type="secondary">{t(`${base}.when`)}</Typography.Paragraph>
      <Space direction="vertical" size={4} style={{ width: '100%' }}>
        <div>
          <Typography.Text strong>{t('workflow.inspector.docs.input')}</Typography.Text>
          <div>{t(`${base}.input`)}</div>
        </div>
        <div>
          <Typography.Text strong>{t('workflow.inspector.docs.output')}</Typography.Text>
          <div>{t(`${base}.output`)}</div>
        </div>
      </Space>
      <div className="wf-docs-example">
        <Typography.Text strong>{t('workflow.inspector.docs.example')}</Typography.Text>
        <pre className="wf-inline-json">{t(`${base}.example`)}</pre>
        {type === 'code' && (
          <>
            <Typography.Text strong>{t('workflow.node.docs.code.pythonLabel')}</Typography.Text>
            <pre className="wf-inline-json">{t(`${base}.examplePython`)}</pre>
          </>
        )}
      </div>
      {SECURITY_TYPES.includes(type) && (
        <Alert type="warning" showIcon message={t(`workflow.inspector.docs.security.${type}`)} />
      )}
    </div>
  );
};

export default NodeDocs;
