import React, { useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, InputNumber, Select, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowVariableGroup } from '../../variables';
import { VariableInput } from './VariablePicker';
import { AiProviderModelFields } from './AiProviderModelFields';
import { editableSchema } from './editableSchema';

export const StructuredSchemaEditor: React.FC<{ value?: string; onChange?: (value: string) => void }> = ({ value = '', onChange }) => {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const schema = editableSchema(value);
  const write = (next: NonNullable<typeof schema>) => onChange?.(JSON.stringify({ type: 'object', ...next }, null, 2));
  return <div>
    <Alert type="info" showIcon message={t('workflow.structured.serverValidation')} />
    {!schema ? <Alert type="warning" showIcon message={t('workflow.structured.rawOnly')} /> : <div>
      {Object.entries(schema.properties).map(([field, spec]) => <Space key={field} wrap>
        <span>{field}</span>
        <Select aria-label={`${t('workflow.structured.fieldType')} ${field}`} value={spec.type} style={{ width: 120 }}
          options={['string', 'number', 'integer', 'boolean'].map((type) => ({ value: type, label: type }))}
          onChange={(type) => write({ ...schema, properties: { ...schema.properties, [field]: { type } } })} />
        <Checkbox checked={schema.required.includes(field)} onChange={(e) => write({ ...schema, required: e.target.checked ? [...schema.required, field] : schema.required.filter((v) => v !== field) })}>{t('workflow.structured.required')}</Checkbox>
        <Button aria-label={`${t('workflow.structured.removeField')} ${field}`} onClick={() => {
          const properties = { ...schema.properties }; delete properties[field];
          write({ ...schema, properties, required: schema.required.filter((v) => v !== field) });
        }}>{t('workflow.structured.removeField')}</Button>
      </Space>)}
      <Space wrap>
        <Input aria-label={t('workflow.structured.fieldName')} value={name} onChange={(e) => setName(e.target.value)} />
        <Button disabled={!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name) || Object.prototype.hasOwnProperty.call(schema.properties, name)} onClick={() => {
          write({ ...schema, properties: { ...schema.properties, [name]: { type: 'string' } } }); setName('');
        }}>{t('workflow.structured.addField')}</Button>
      </Space>
      <Checkbox checked={false} disabled>{t('workflow.structured.additionalProperties')}</Checkbox>
    </div>}
    <Input.TextArea aria-label={t('workflow.structured.rawSchema')} rows={5} value={value} onChange={(e) => onChange?.(e.target.value)} style={{ fontFamily: 'monospace', fontSize: 12 }} />
  </div>;
};

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
      <Alert type="warning" showIcon message={t('workflow.ai.externalInputWarning', { defaultValue: '输入会发送给配置的模型，请勿输入敏感数据。' })} style={{ marginBottom: 12 }} />
      <Alert type="warning" showIcon message={t('workflow.structured.savedProtection', { defaultValue: '严格结构化输出节点保存后，不能删除节点或修改节点标识、类型、输出模式及 Schema；其他配置仍可修改。' })} style={{ marginBottom: 12 }} />
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
            { value: 'json_schema_v1', label: t('workflow.structured.mode') },
          ]}
        />
      </Form.Item>
      <Form.Item noStyle shouldUpdate={(prev, next) => prev.llmOutputMode !== next.llmOutputMode}>
        {({ getFieldValue }) =>
          getFieldValue('llmOutputMode') === 'json_schema_v1' ? (
            <Form.Item name="llmJsonSchema" label={t('workflow.llm.jsonSchema')}><StructuredSchemaEditor /></Form.Item>
          ) : getFieldValue('llmOutputMode') === 'json' ? (
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
