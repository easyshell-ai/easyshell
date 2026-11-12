import React from 'react';
import { Alert, Button, Form, Input, Space, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { CLASSIFIER_NAME_PATTERN, MAX_CLASSIFIER_CLASSES } from '../../graph';
import type { ClassifierClass } from '../../types';
import type { WorkflowVariableGroup } from '../../variables';
import { VariableInput } from './VariablePicker';
import { AiProviderModelFields } from './AiProviderModelFields';

/**
 * 问题分类节点：input / class 列表（1..10）/ instructions / provider / model。
 * 每个 class 对应一个真实出边把手（class 名）+ default 兜底。
 */
export interface ClassifierFieldsProps {
  groups: WorkflowVariableGroup[];
  classes: ClassifierClass[];
  onClassesChange: (next: ClassifierClass[]) => void;
}

function invalidClassNames(classes: ClassifierClass[]): string[] {
  const seen = new Set<string>();
  const invalid: string[] = [];
  for (const item of classes) {
    const name = (item.name || '').trim();
    if (!name) continue;
    if (!CLASSIFIER_NAME_PATTERN.test(name) || seen.has(name)) invalid.push(name);
    seen.add(name);
  }
  return [...new Set(invalid)];
}

export const ClassifierFields: React.FC<ClassifierFieldsProps> = ({ groups, classes, onClassesChange }) => {
  const { t } = useTranslation();
  const invalid = invalidClassNames(classes);

  const patch = (index: number, changes: Partial<ClassifierClass>) => {
    onClassesChange(classes.map((item, position) => (position === index ? { ...item, ...changes } : item)));
  };

  return (
    <div data-testid="classifier-fields">
      <Alert type="info" showIcon message={t('workflow.classifier.warning')} style={{ marginBottom: 12 }} />
      <Form.Item
        name="classifierInput"
        label={t('workflow.classifier.input')}
        tooltip={t('workflow.classifier.inputHint')}
        rules={[{ required: true, message: t('workflow.classifier.inputRequired') }]}
      >
        <VariableInput
          groups={groups}
          mode="template"
          ariaLabel={t('workflow.classifier.input')}
          placeholder="${nodes.check.output}"
          monospace
        />
      </Form.Item>

      <Form.Item label={t('workflow.classifier.classes')} required tooltip={t('workflow.classifier.classesHint')}>
        <div className="wf-row-editor" data-testid="classifier-classes">
          {classes.length === 0 && (
            <Typography.Text type="secondary">{t('workflow.classifier.emptyClasses')}</Typography.Text>
          )}
          {classes.map((item, index) => (
            <div className="wf-mapping-row" key={index}>
              <Space size={4} style={{ width: '100%' }}>
                <Input
                  value={item.name}
                  status={
                    item.name.trim() && !CLASSIFIER_NAME_PATTERN.test(item.name.trim()) ? 'error' : undefined
                  }
                  onChange={(event) => patch(index, { name: event.target.value })}
                  placeholder={t('workflow.classifier.classNamePlaceholder')}
                  style={{ width: 150, fontFamily: 'monospace' }}
                  aria-label={t('workflow.classifier.className')}
                />
                <Button
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  aria-label={t('common.remove')}
                  onClick={() => onClassesChange(classes.filter((_, position) => position !== index))}
                />
              </Space>
              <Input
                size="small"
                value={item.description || ''}
                onChange={(event) => patch(index, { description: event.target.value })}
                placeholder={t('workflow.classifier.classDescriptionPlaceholder')}
                aria-label={t('workflow.classifier.classDescription')}
              />
            </div>
          ))}
          <Button
            block
            icon={<PlusOutlined />}
            disabled={classes.length >= MAX_CLASSIFIER_CLASSES}
            onClick={() => onClassesChange([...classes, { name: '', description: '' }])}
          >
            {t('workflow.classifier.addClass')}
          </Button>
          <Typography.Text type="secondary" className="wf-row-editor-hint">
            {t('workflow.classifier.classCount', { count: classes.length, max: MAX_CLASSIFIER_CLASSES })}
          </Typography.Text>
        </div>
      </Form.Item>

      {invalid.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={t('workflow.classifier.invalidClassName')}
          description={invalid.join(', ')}
          style={{ marginBottom: 12 }}
        />
      )}
      {classes.length > MAX_CLASSIFIER_CLASSES && (
        <Alert type="error" showIcon message={t('workflow.classifier.tooManyClasses')} style={{ marginBottom: 12 }} />
      )}

      <Form.Item name="classifierInstructions" label={t('workflow.classifier.instructions')} tooltip={t('workflow.classifier.instructionsHint')}>
        <Input.TextArea
          rows={3}
          style={{ fontSize: 12 }}
          aria-label={t('workflow.classifier.instructions')}
          placeholder={t('workflow.classifier.instructionsPlaceholder')}
        />
      </Form.Item>

      <AiProviderModelFields providerName="classifierProvider" modelName="classifierModel" />

      <Typography.Text type="secondary" className="wf-row-editor-hint">
        {t('workflow.classifier.outputHint', { count: classes.length })}
      </Typography.Text>
    </div>
  );
};

export default ClassifierFields;
