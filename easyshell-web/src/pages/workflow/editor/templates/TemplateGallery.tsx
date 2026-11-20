import React, { useState } from 'react';
import { Alert, Button, Card, Modal, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowDefinition } from '../../types';
import { WORKFLOW_TEMPLATES, type WorkflowTemplateId } from './index';

/**
 * 模板库（设计 §3.4）：可一键替换当前图定义的模板。
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
                  <span>{template.id === 'aiReview' ? t('workflow.template.aiReview.safeName', { defaultValue: 'Manual AI review' }) : t(`workflow.template.${template.id}.safeName`)}</span>
                  <Tag color="blue">{t('workflow.template.badge')}</Tag>
                  <Tag color={template.id === 'restart' ? 'red' : template.id === 'aiReview' ? 'purple' : 'orange'}>
                    {template.id === 'aiReview'
                      ? t('workflow.template.aiReview.realAiCall', { defaultValue: 'Real AI call' })
                      : t(template.id === 'restart' ? 'workflow.template.realSideEffects' : 'workflow.template.simulated')}
                  </Tag>
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
                {template.id === 'aiReview'
                  ? t('workflow.template.aiReview.safeDesc', { defaultValue: 'Review a manually supplied observation with AI. The result is advisory and requires human review; no host commands or notifications are issued.' })
                  : t(`workflow.template.${template.id}.safeDesc`)}
              </Typography.Paragraph>
              {template.id === 'restart' && (
                <Alert type="warning" showIcon message={t('workflow.template.restart.executionWarning', { defaultValue: 'This template can restart a real service and has side effects. Service health is not verified. High-risk approval binding is not implemented, so the command may still be blocked even after approval.' })} />
              )}
              {template.id === 'aiReview' && (
                <Alert type="warning" showIcon message={t('workflow.template.aiReview.externalInputWarning', { defaultValue: 'Configure the default AI provider and model before running. Input is sent to the configured AI provider; do not enter sensitive information.' })} />
              )}
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
