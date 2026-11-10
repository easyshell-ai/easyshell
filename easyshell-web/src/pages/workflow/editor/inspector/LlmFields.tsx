import React from 'react';
import { Alert, Form, Input, InputNumber, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowVariableGroup } from '../../variables';
import { VariableInput } from './VariablePicker';
import { AiProviderModelFields } from './AiProviderModelFields';

/**
 * 通用 LLM 节点字段：provider/model / systemPrompt / userPrompt / outputMode /
 * jsonSchema / temperature / maxInputChars。
 *
 * 安全边界：LLM 只生成文本或 JSON，不执行命令；输出不能直接作为 script-exec 的 raw 参数。
 */
export interface LlmFieldsProps {
  groups: WorkflowVariableGroup[];
}

export const LlmFields: React.FC<LlmFieldsProps> = ({ groups }) => {
  const { t } = useTranslation();
  return (
    <div data-testid="llm-fields">
      <Alert type="info" showIcon message={t('workflow.llm.warning')} style={{ marginBottom: 12 }} />
      <AiProviderModelFields providerName="llmProvider" modelName="llmModel" />
      <Form.Item
        name="llmUserPrompt"
        label={t('workflow.llm.userPrompt')}
        tooltip={t('workflow.llm.userPromptHint')}
        rules={[{ required: true, message: t('workflow.llm.userPromptRequired') }]}
      >
        <VariableInput
          groups={groups}
          mode="template"
          ariaLabel={t('workflow.llm.userPrompt')}
          multiline
          rows={5}
          placeholder="Analyze ${nodes.check.output}"
          monospace
        />
      </Form.Item>
      <Form.Item name="llmSystemPrompt" label={t('workflow.llm.systemPrompt')} tooltip={t('workflow.llm.systemPromptHint')}>
        <VariableInput
          groups={groups}
          mode="template"
          ariaLabel={t('workflow.llm.systemPrompt')}
          multiline
          rows={3}
          placeholder={t('workflow.llm.systemPromptPlaceholder')}
          monospace
        />
      </Form.Item>
      <Form.Item
        name="llmOutputMode"
        label={t('workflow.llm.outputMode')}
        tooltip={t('workflow.llm.outputModeHint')}
      >
        <Select
          aria-label={t('workflow.llm.outputMode')}
          options={[
            { value: 'text', label: t('workflow.llm.outputModeText') },
            { value: 'json', label: t('workflow.llm.outputModeJson') },
          ]}
        />
      </Form.Item>
      <Form.Item noStyle shouldUpdate={(prev, next) => prev.llmOutputMode !== next.llmOutputMode}>
        {({ getFieldValue }) =>
          getFieldValue('llmOutputMode') === 'json' ? (
            <Form.Item
              name="llmJsonSchema"
              label={t('workflow.llm.jsonSchema')}
              tooltip={t('workflow.llm.jsonSchemaHint')}
            >
              <Input.TextArea
                rows={5}
                aria-label={t('workflow.llm.jsonSchema')}
                placeholder={'{"type":"object","properties":{"summary":{"type":"string"}}}'}
                style={{ fontFamily: 'monospace', fontSize: 12 }}
              />
            </Form.Item>
          ) : null
        }
      </Form.Item>
      <Form.Item
        name="llmTemperature"
        label={t('workflow.llm.temperature')}
        tooltip={t('workflow.llm.temperatureHint')}
      >
        <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} aria-label={t('workflow.llm.temperature')} />
      </Form.Item>
      <Form.Item
        name="llmMaxInputChars"
        label={t('workflow.llm.maxInputChars')}
        tooltip={t('workflow.llm.maxInputCharsHint')}
      >
        <InputNumber min={1000} max={32000} step={500} style={{ width: '100%' }} aria-label={t('workflow.llm.maxInputChars')} />
      </Form.Item>
    </div>
  );
};

export default LlmFields;
