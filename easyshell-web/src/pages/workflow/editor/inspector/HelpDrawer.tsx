import React from 'react';
import { Alert, Drawer, List, Space, Table, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { NODE_REGISTRY } from '../nodeRegistry';
import { NodeTypeIcon } from '../NodeIcon';

/**
 * 帮助抽屉（设计 §3.4）：4 步入门 / 变量语法 / 12 类节点速查 / 完整示例 / 安全边界。
 * 中英文均由 workflow.help.* 提供，代码示例保持英文（代码不是文案）。
 */
export interface HelpDrawerProps {
  open: boolean;
  onClose: () => void;
}

const STEPS = ['declare', 'connect', 'variables', 'outputs'] as const;

const SYNTAX_ROWS = [
  { key: 'template', example: '${nodes.check.output.usage}', whereKey: 'workflow.help.syntax.templateWhere' },
  { key: 'expression', example: 'nodes.check.output.result == true', whereKey: 'workflow.help.syntax.expressionWhere' },
  { key: 'script', example: '{{MOUNT}}', whereKey: 'workflow.help.syntax.scriptWhere' },
];

export const HelpDrawer: React.FC<HelpDrawerProps> = ({ open, onClose }) => {
  const { t } = useTranslation();

  return (
    <Drawer
      title={t('workflow.help.title')}
      open={open}
      onClose={onClose}
      width={620}
      className="wf-help-drawer"
    >
      <div data-testid="workflow-help-drawer">
        <Typography.Paragraph type="secondary">{t('workflow.help.intro')}</Typography.Paragraph>

        <Typography.Title level={5}>{t('workflow.help.steps.title')}</Typography.Title>
        <List
          size="small"
          dataSource={[...STEPS]}
          renderItem={(step, index) => (
            <List.Item>
              <Space align="start">
                <Tag color="blue">{index + 1}</Tag>
                <div>
                  <Typography.Text strong>{t(`workflow.help.steps.${step}.title`)}</Typography.Text>
                  <div>{t(`workflow.help.steps.${step}.desc`)}</div>
                </div>
              </Space>
            </List.Item>
          )}
        />

        <Typography.Title level={5} style={{ marginTop: 20 }}>
          {t('workflow.help.syntax.title')}
        </Typography.Title>
        <Table
          size="small"
          pagination={false}
          dataSource={SYNTAX_ROWS}
          columns={[
            { title: t('workflow.help.syntax.form'), dataIndex: 'key', width: 110, render: (key: string) => t(`workflow.help.syntax.${key}`) },
            { title: t('workflow.help.syntax.example'), dataIndex: 'example', render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
            { title: t('workflow.help.syntax.where'), dataIndex: 'whereKey', render: (key: string) => t(key) },
          ]}
        />

        <Typography.Title level={5} style={{ marginTop: 20 }}>
          {t('workflow.help.nodes.title')}
        </Typography.Title>
        <div className="wf-help-nodes">
          {NODE_REGISTRY.map((entry) => (
            <div key={entry.type} className="wf-help-node">
              <span className="wf-help-node-icon">
                <NodeTypeIcon type={entry.type} />
              </span>
              <div>
                <Space size={6}>
                  <Typography.Text strong>{t(`workflow.node.type.${entry.type}`)}</Typography.Text>
                  {entry.fixed && <Tag>{t('workflow.node.fixed')}</Tag>}
                </Space>
                <div className="wf-help-node-desc">{t(`workflow.node.desc.${entry.type}`)}</div>
              </div>
            </div>
          ))}
        </div>

        <Typography.Title level={5} style={{ marginTop: 20 }}>
          {t('workflow.help.aiNodes.title')}
        </Typography.Title>
        <Typography.Paragraph type="secondary">{t('workflow.help.aiNodes.intro')}</Typography.Paragraph>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {(['llm', 'classifier', 'code'] as const).map((type) => (
            <div key={type} className="wf-help-ai-node">
              <Space size={6}>
                <span className="wf-help-node-icon">
                  <NodeTypeIcon type={type} />
                </span>
                <Typography.Text strong>{t(`workflow.node.type.${type}`)}</Typography.Text>
              </Space>
              <div className="wf-help-node-desc">{t(`workflow.help.aiNodes.${type}.desc`)}</div>
              <pre className="wf-inline-json">{t(`workflow.help.aiNodes.${type}.example`)}</pre>
            </div>
          ))}
        </Space>

        <Typography.Title level={5} style={{ marginTop: 20 }}>
          {t('workflow.help.example.title')}
        </Typography.Title>
        <Typography.Paragraph type="secondary">{t('workflow.help.example.desc')}</Typography.Paragraph>
        <pre className="wf-inline-json">{`start (variables: targets, threshold)
  └─ script-exec  check   df -h {{MOUNT}}
      └─ transform metrics  usage / summary / threshold
          └─ condition over  nodes.metrics.output.usage > nodes.metrics.output.threshold
              ├─ true  → notify alert
              └─ false → end
          notify alert → end (outputs: summary, breached)`}</pre>

        <Typography.Title level={5} style={{ marginTop: 20 }}>
          {t('workflow.help.security.title')}
        </Typography.Title>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Alert type="warning" showIcon message={t('workflow.help.security.ai')} />
          <Alert type="info" showIcon message={t('workflow.help.security.approval')} />
          <Alert type="info" showIcon message={t('workflow.help.security.script')} />
          <Alert type="info" showIcon message={t('workflow.help.security.code')} />
          <Alert type="info" showIcon message={t('workflow.help.security.host')} />
        </Space>
      </div>
    </Drawer>
  );
};

export default HelpDrawer;
