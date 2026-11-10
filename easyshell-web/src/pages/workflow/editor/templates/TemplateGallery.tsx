import React, { useState } from 'react';
import { Alert, Button, Card, Modal, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowDefinition } from '../../types';
import { WORKFLOW_TEMPLATES, type WorkflowTemplateId } from './index';

/**
 * 模板库（设计 §3.4）：3 个可一键替换当前图定义的模板。
 * 当前图有 start/end 之外的节点时先确认（不可撤销的替换必须二次确认）。
 */
export interface TemplateGalleryProps {
  open: boolean;
  definition: WorkflowDefinition;
  onCancel: () => void;
  onApply: (definition: WorkflowDefinition, templateId: WorkflowTemplateId) => void;
}

export const TemplateGallery: React.FC<TemplateGalleryProps> = ({ open, definition, onCancel, onApply }) => {
  const { t } = useTranslation();
  const [pending, setPending] = useState<WorkflowTemplateId | null>(null);

  const hasContent = (definition.nodes || []).some((node) => node.type !== 'start' && node.type !== 'end');
  const apply = (id: WorkflowTemplateId) => {
    const template = WORKFLOW_TEMPLATES.find((item) => item.id === id);
    if (!template) return;
    onApply(template.build(), id);
    setPending(null);
  };

  return (
    <Modal
      title={t('workflow.template.title')}
      open={open}
      onCancel={onCancel}
      footer={<Button onClick={onCancel}>{t('common.cancel')}</Button>}
      width={760}
    >
      <div data-testid="workflow-template-gallery" className="wf-template-gallery">
        <Alert type="info" showIcon message={t('workflow.template.intro')} style={{ marginBottom: 12 }} />
        <div className="wf-template-grid">
          {WORKFLOW_TEMPLATES.map((template) => (
            <Card
              key={template.id}
              size="small"
              className="wf-template-card"
              title={
                <Space>
                  <span>{t(`workflow.template.${template.id}.name`)}</span>
                  <Tag color="blue">{t('workflow.template.badge')}</Tag>
                </Space>
              }
              actions={[
                <Button
                  key="apply"
                  type="primary"
                  data-testid={`workflow-template-${template.id}`}
                  onClick={() => (hasContent ? setPending(template.id) : apply(template.id))}
                >
                  {t('workflow.template.apply')}
                </Button>,
              ]}
            >
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                {t(`workflow.template.${template.id}.desc`)}
              </Typography.Paragraph>
            </Card>
          ))}
        </div>
      </div>

      <Modal
        title={t('workflow.template.replaceTitle')}
        open={pending !== null}
        onCancel={() => setPending(null)}
        onOk={() => pending && apply(pending)}
        okText={t('workflow.template.replaceConfirm')}
        cancelText={t('common.cancel')}
        okButtonProps={{ danger: true }}
      >
        {t('workflow.template.replaceBody')}
      </Modal>
    </Modal>
  );
};

export default TemplateGallery;
