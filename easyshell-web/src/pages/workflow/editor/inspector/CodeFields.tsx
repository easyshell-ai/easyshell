import React from 'react';
import { Alert, Button, Form, Input, InputNumber, Select, Typography } from 'antd';
import { CodeOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { WorkflowVariableGroup } from '../../variables';
import { ParametersEditor } from '../NodeConfigFields';

/**
 * 沙箱数据转换节点：language / inputs 映射 / monospace code / timeout。
 *
 * 仅做数据变换，不提供主机操作；代码在服务端隔离沙箱执行（无 host IO / 命令 / 网络）。
 */
export interface CodeFieldsProps {
  groups: WorkflowVariableGroup[];
  inputs: Record<string, string>;
  onInputsChange: (next: Record<string, string>) => void;
}

const SKELETONS: Record<'javascript' | 'python', string> = {
  javascript: 'function main(input) {\n  return { result: input };\n}',
  python: 'def main(input):\n    return {"result": input}\n',
};

export const CodeFields: React.FC<CodeFieldsProps> = ({ groups, inputs, onInputsChange }) => {
  const { t } = useTranslation();
  const form = Form.useFormInstance();
  const language = (Form.useWatch('codeLanguage', form) as 'javascript' | 'python' | undefined) || 'javascript';

  const insertSkeleton = () => {
    const current = form.getFieldValue('codeLanguage') === 'python' ? 'python' : 'javascript';
    form.setFieldValue('code', SKELETONS[current]);
  };

  return (
    <div data-testid="code-fields">
      <Alert type="info" showIcon message={t('workflow.code.warning')} style={{ marginBottom: 12 }} />
      <Form.Item name="codeLanguage" label={t('workflow.code.language')} tooltip={t('workflow.code.languageHint')}>
        <Select
          aria-label={t('workflow.code.language')}
          options={[
            { value: 'javascript', label: t('workflow.code.languageJavascript') },
            { value: 'python', label: t('workflow.code.languagePython') },
          ]}
        />
      </Form.Item>
      <Form.Item
        name="code"
        label={t('workflow.code.code')}
        tooltip={t('workflow.code.codeHint')}
        rules={[{ required: true, message: t('workflow.code.codeRequired') }]}
      >
        <Input.TextArea rows={10} style={{ fontFamily: 'monospace', fontSize: 12 }} placeholder={SKELETONS[language]} />
      </Form.Item>
      <Button icon={<CodeOutlined />} onClick={insertSkeleton} htmlType="button" style={{ marginBottom: 12 }}>
        {t('workflow.code.insertSkeleton')}
      </Button>
      <Form.Item label={t('workflow.code.inputs')} tooltip={t('workflow.code.inputsHint')}>
        <ParametersEditor
          value={inputs}
          onChange={onInputsChange}
          groups={groups}
          ariaLabel={t('workflow.code.inputs')}
          addLabel={t('workflow.code.addInput')}
          emptyText={t('workflow.code.inputsEmpty')}
          keyPlaceholder={t('workflow.code.inputNamePlaceholder')}
          keyAriaLabel={t('workflow.code.inputName')}
          valueAriaLabel={t('workflow.code.inputValue')}
        />
      </Form.Item>
      <Form.Item name="codeTimeoutSec" label={t('workflow.code.timeoutSec')} tooltip={t('workflow.code.timeoutSecHint')}>
        <InputNumber min={1} max={15} style={{ width: '100%' }} addonAfter={t('common.seconds')} aria-label={t('workflow.code.timeoutSec')} />
      </Form.Item>
      <Typography.Text type="secondary" className="wf-row-editor-hint">
        {t('workflow.code.outputHint')}
      </Typography.Text>
    </div>
  );
};

export default CodeFields;
