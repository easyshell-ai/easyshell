import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Input, InputNumber, Select, Typography } from 'antd';
import { CodeOutlined } from '@ant-design/icons';
import CodeMirror from '@uiw/react-codemirror';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { getSessionEpoch, subscribeSessionEpoch } from '../../../../api/request';
import { testWorkflowCode } from '../../api';
import type { WorkflowVariableGroup } from '../../variables';
import { ParametersEditor } from '../NodeConfigFields';

/**
 * 沙箱数据转换节点：language / inputs 映射 / monospace code / timeout。
 *
 * 样例由已配置的隔离执行服务运行。
 */
export interface CodeFieldsProps {
  workflowId?: number;
  groups: WorkflowVariableGroup[];
  inputs: Record<string, string>;
  onInputsChange: (next: Record<string, string>) => void;
}

const SKELETONS: Record<'javascript' | 'python', string> = {
  javascript: 'function main(input) {\n  return { result: input };\n}',
  python: 'def main(input):\n    return {"result": input}\n',
};

const LANGUAGE_EXTENSIONS = {
  javascript: [javascript()],
  python: [python()],
};

interface CodeEditorProps {
  value?: string;
  onChange?: (value: string) => void;
  id?: string;
  language: 'javascript' | 'python';
  label: string;
}

const CodeEditor: React.FC<CodeEditorProps> = ({ value, onChange, id, language, label }) => (
  <CodeMirror
    id={id}
    value={value ?? ''}
    onChange={onChange}
    extensions={LANGUAGE_EXTENSIONS[language]}
    height="220px"
    placeholder={SKELETONS[language]}
    aria-label={label}
    basicSetup={{ lineNumbers: true }}
  />
);

export const CodeFields: React.FC<CodeFieldsProps> = ({ workflowId: providedId, groups, inputs, onInputsChange }) => {
  const { t } = useTranslation();
  const { id } = useParams();
  const workflowId = providedId ?? Number(id);
  const validId = Number.isSafeInteger(workflowId) && workflowId > 0;
  const form = Form.useFormInstance();
  const language = (Form.useWatch('codeLanguage', form) as 'javascript' | 'python' | undefined) || 'javascript';
  const code = Form.useWatch('code', form) as string | undefined;
  const timeoutSec = Form.useWatch('codeTimeoutSec', form) as number | undefined;
  const [sample, setSample] = useState('{}');
  const [loading, setLoading] = useState(false);
  const [output, setOutput] = useState<string>();
  const [error, setError] = useState('');
  const viewSequence = useRef(0);
  const pending = useRef(false);
  let input: Record<string, unknown> | undefined;
  try {
    const parsed: unknown = JSON.parse(sample);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      input = parsed as Record<string, unknown>;
    }
  } catch { /* Validation is shown beside the sample input. */ }

  useEffect(() => {
    const invalidate = () => {
      viewSequence.current += 1;
      pending.current = false;
      setLoading(false);
      setOutput(undefined);
      setError('');
    };
    invalidate();
    const unsubscribe = subscribeSessionEpoch(invalidate);
    return () => {
      viewSequence.current += 1;
      pending.current = false;
      unsubscribe();
    };
  }, [workflowId, code, language, sample, timeoutSec]);

  const runSample = async () => {
    if (pending.current || !validId || !code?.trim() || !input) return;
    setOutput(undefined);
    setError('');
    if (typeof timeoutSec !== 'number' || !Number.isInteger(timeoutSec) || timeoutSec < 1 || timeoutSec > 5) {
      setError(t('workflow.code.sampleTimeoutInvalid'));
      return;
    }
    pending.current = true;
    setLoading(true);
    const sequence = ++viewSequence.current;
    const epoch = getSessionEpoch();
    const isCurrent = () => sequence === viewSequence.current && epoch === getSessionEpoch();
    try {
      const res = await testWorkflowCode(workflowId, { language, code, timeoutSec, input });
      if (!isCurrent()) return;
      if (res.code === 200) setOutput(JSON.stringify(res.data, null, 2) ?? 'null');
      else setError(t(res.code === 503 ? 'workflow.code.sampleServiceUnavailable' : 'workflow.code.sampleFailed'));
    } catch (cause: unknown) {
      if (!isCurrent()) return;
      const status = (cause as { response?: { status?: number } } | null)?.response?.status;
      setError(t(status === 503 ? 'workflow.code.sampleServiceUnavailable' : 'workflow.code.sampleError'));
    } finally {
      if (isCurrent()) {
        pending.current = false;
        setLoading(false);
      }
    }
  };
  const insertSkeleton = () => {
    const current = form.getFieldValue('codeLanguage') === 'python' ? 'python' : 'javascript';
    form.setFieldValue('code', SKELETONS[current]);
  };

  return (
    <div data-testid="code-fields">
      <Alert
        type="warning"
        showIcon
        message={t('workflow.code.unavailableNotice')}
        style={{ marginBottom: 12 }}
      />
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
      <Typography.Paragraph type="secondary">
        {t('workflow.code.inputContract', { defaultValue: 'Input names become properties of the object passed to main(input). Use upstream variable templates; names must start with a letter or underscore and contain only letters, digits or underscores (up to 64 characters).' })}
      </Typography.Paragraph>
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
        <CodeEditor language={language} label={t('workflow.code.code')} />
      </Form.Item>
      <Button icon={<CodeOutlined />} onClick={insertSkeleton} htmlType="button" style={{ marginBottom: 12 }}>
        {t('workflow.code.insertSkeleton')}
      </Button>
      <Form.Item name="codeTimeoutSec" label={t('workflow.code.timeoutSec')} tooltip={t('workflow.code.timeoutSecHint')}>
        <InputNumber min={1} max={5} style={{ width: '100%' }} addonAfter={t('common.seconds')} aria-label={t('workflow.code.timeoutSec')} />
      </Form.Item>
      <Typography.Title level={5}>{t('workflow.code.sampleTitle', { defaultValue: 'Run sandbox sample' })}</Typography.Title>
      <Alert type="warning" showIcon message={t('workflow.code.sampleWarning')} style={{ marginBottom: 12 }} />
      <Form.Item label={t('workflow.code.sampleInput', { defaultValue: 'Sample input (JSON object)' })} validateStatus={input ? undefined : 'error'} help={input ? undefined : t('workflow.code.sampleInvalid')}>
        <Input.TextArea aria-label={t('workflow.code.sampleInput', { defaultValue: 'Sample input (JSON object)' })} value={sample} onChange={(event) => setSample(event.target.value)} rows={4} style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      <Button htmlType="button" loading={loading} disabled={loading || !validId || !code?.trim() || !input} onClick={() => void runSample()}>
        {t('workflow.code.sampleRun', { defaultValue: 'Run sample' })}
      </Button>
      {error && <Alert type="error" showIcon message={error} />}
      {output !== undefined && <pre role="status" aria-label={t('workflow.code.sampleOutput')}>{output}</pre>}
      <Typography.Title level={5}>{t('workflow.code.outputTitle', { defaultValue: 'Output' })}</Typography.Title>
      <Typography.Paragraph type="secondary">
        {t('workflow.code.outputHint')}{' '}
        {t('workflow.code.outputContract', { defaultValue: 'Return an object to expose named fields. Output fields are determined by the return value; declarations and type validation are not supported.' })}
      </Typography.Paragraph>
    </div>
  );
};

export default CodeFields;
