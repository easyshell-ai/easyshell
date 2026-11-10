import React from 'react';
import { Button, Space, Tag, Tooltip, Typography, message } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { WorkflowDefinition, WorkflowNode } from '../../types';
import { availableVariablesForNode, knownOutputFieldsOf, type WorkflowVariableGroup } from '../../variables';

/**
 * 数据 Tab：上游可用变量（可复制）、本节点输出路径、输入契约。
 * 变量目录唯一来源是 variables.ts（availableVariablesForNode），这里不另建目录。
 */
export interface DataFlowTabProps {
  definition: WorkflowDefinition;
  node: WorkflowNode;
}

const GROUP_KIND_KEY: Record<WorkflowVariableGroup['kind'], string> = {
  input: 'workflow.variable.group.input',
  start: 'workflow.variable.group.start',
  node: 'workflow.variable.group.node',
  alias: 'workflow.variable.group.alias',
};

export const DataFlowTab: React.FC<DataFlowTabProps> = ({ definition, node }) => {
  const { t } = useTranslation();
  const groups = availableVariablesForNode(definition, node.key);
  const outputFields = knownOutputFieldsOf(node);
  const total = groups.reduce((sum, group) => sum + group.refs.length, 0);

  const copy = (path: string) => {
    void navigator.clipboard?.writeText(`\${${path}}`).catch(() => undefined);
    message.success(t('workflow.inspector.data.copied'));
  };

  return (
    <div className="wf-data-tab" data-testid="node-data-flow">
      <Typography.Text strong>{t('workflow.inspector.data.upstream')}</Typography.Text>
      {total === 0 && (
        <Typography.Paragraph type="secondary" style={{ marginTop: 6 }}>
          {t('workflow.variable.empty')}
          <br />
          {t('workflow.variable.emptyHint')}
        </Typography.Paragraph>
      )}
      {groups.map((group) => (
        <div key={group.id} className="wf-data-group">
          <div className="wf-data-group-head">
            <Tag>{t(GROUP_KIND_KEY[group.kind])}</Tag>
            <span>{group.label}</span>
          </div>
          {group.refs.map((ref) => (
            <div key={ref.path} className="wf-data-row">
              <Tooltip title={`\${${ref.path}}`}>
                <Typography.Text code>{ref.path}</Typography.Text>
              </Tooltip>
              <Space size={4}>
                {ref.type && <Tag>{ref.type}</Tag>}
                <Button
                  size="small"
                  type="text"
                  icon={<CopyOutlined />}
                  aria-label={t('workflow.inspector.data.copy')}
                  onClick={() => copy(ref.path)}
                />
              </Space>
            </div>
          ))}
        </div>
      ))}

      <div className="wf-data-output">
        <Typography.Text strong>{t('workflow.inspector.data.outputPath')}</Typography.Text>
        <Typography.Paragraph code copyable style={{ marginBottom: 4 }}>
          {`nodes.${node.key}.output`}
        </Typography.Paragraph>
        {outputFields.length > 0 ? (
          outputFields.map((field) => (
            <div key={field.name} className="wf-data-row">
              <Typography.Text code>{`nodes.${node.key}.output.${field.name}`}</Typography.Text>
              <Tag>{field.type}</Tag>
            </div>
          ))
        ) : (
          <Typography.Text type="secondary">{t('workflow.inspector.data.opaqueOutput')}</Typography.Text>
        )}
      </div>

      <AlertLine type={node.type} />
    </div>
  );
};

const AlertLine: React.FC<{ type: WorkflowNode['type'] }> = ({ type }) => {
  const { t } = useTranslation();
  return (
    <div className="wf-data-contract">
      <Typography.Text strong>{t('workflow.inspector.data.contract')}</Typography.Text>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        {t(`workflow.node.docs.${type}.input`)}
      </Typography.Paragraph>
    </div>
  );
};

export default DataFlowTab;
