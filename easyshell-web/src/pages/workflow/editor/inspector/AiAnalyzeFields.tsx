import React from 'react';
import { Alert, Form, Input, InputNumber, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowVariableGroup } from '../../variables';
import { VariableInput } from './VariablePicker';

/**
 * AI 分析节点的配置字段：instructions / input / maxInputChars / outputMode / jsonSchema。
 * 安全边界：AI 只读上游输出并产出结论文本或 JSON，不执行任何命令（AGENTS.md §6）。
 */
export interface AiAnalyzeFieldsProps {
  groups: WorkflowVariableGroup[];
}

export const AiAnalyzeFields: React.FC<AiAnalyzeFieldsProps> = ({ groups }) => {
  const { t } = useTranslation();
  return (
    <div data-testid="ai-analyze-fields">
      <Alert type="warning" showIcon message={t('workflow.ai.warning')} style={{ marginBottom: 12 }} />
      <Form.Item
        name="aiInstructions"
        label={t('workflow.ai.instructions')}
        tooltip={t('workflow.ai.instructionsHint')}
        rules={[{ required: true, message: t('workflow.ai.instructionsRequired') }]}
      >
        <Input.TextArea rows={5} style={{ fontSize: 12 }} />
      </Form.Item>
      <Form.Item name="aiInput" label={t('workflow.ai.input')} tooltip={t('workflow.ai.inputHint')}>
        <VariableInput
          groups={groups}
          mode="template"
          ariaLabel={t('workflow.ai.input')}
          placeholder="${nodes.check.output}"
          monospace
        />
      </Form.Item>
      <Form.Item name="aiMaxInputChars" label={t('workflow.ai.maxInputChars')} tooltip={t('workflow.ai.maxInputCharsHint')}>
        <InputNumber min={1000} max={32000} step={500} style={{ width: '100%' }} aria-label={t('workflow.ai.maxInputChars')} />
      </Form.Item>
      <Form.Item name="aiOutputMode" label={t('workflow.ai.outputMode')} tooltip={t('workflow.ai.outputModeHint')}>
        <Select
          aria-label={t('workflow.ai.outputMode')}
          options={[
            { value: 'text', label: t('workflow.ai.outputModeText') },
            { value: 'json', label: t('workflow.ai.outputModeJson') },
          ]}
        />
      </Form.Item>
      <Form.Item noStyle shouldUpdate={(prev, next) => prev.aiOutputMode !== next.aiOutputMode}>
        {({ getFieldValue }) =>
          getFieldValue('aiOutputMode') === 'json' ? (
            <Form.Item name="aiJsonSchema" label={t('workflow.ai.jsonSchema')} tooltip={t('workflow.ai.jsonSchemaHint')}>
              <Input.TextArea
                rows={5}
                aria-label={t('workflow.ai.jsonSchema')}
                placeholder={'{"type":"object","properties":{"verdict":{"type":"string"}}}'}
                style={{ fontFamily: 'monospace', fontSize: 12 }}
              />
            </Form.Item>
          ) : null
        }
      </Form.Item>
    </div>
  );
};

export default AiAnalyzeFields;
