import React, { useEffect, useState } from 'react';
import { AutoComplete, Form, Input, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { getAiConfig, getProviderModels } from '../../../../api/ai';

/**
 * LLM / classifier 共用的 provider + model 选择。
 *
 * - provider 来自 getAiConfig().providers 的 key；空 = 使用默认 provider。
 * - model 来自 getProviderModels(provider)；AutoComplete 天然允许自由输入。
 * - 任意 AI 配置接口失败都退化成普通 Input，编辑不被阻断（离线/无权限环境）。
 */
export interface AiProviderModelFieldsProps {
  providerName: string;
  modelName: string;
}

interface ProviderState {
  providers: string[];
  failed: boolean;
  loaded: boolean;
}

export const AiProviderModelFields: React.FC<AiProviderModelFieldsProps> = ({ providerName, modelName }) => {
  const { t } = useTranslation();
  const [state, setState] = useState<ProviderState>({ providers: [], failed: false, loaded: false });

  useEffect(() => {
    let cancelled = false;
    getAiConfig()
      .then((res) => {
        if (cancelled) return;
        if (res.code === 200 && res.data && res.data.providers) {
          setState({ providers: Object.keys(res.data.providers), failed: false, loaded: true });
        } else {
          setState({ providers: [], failed: true, loaded: true });
        }
      })
      .catch(() => {
        if (!cancelled) setState({ providers: [], failed: true, loaded: true });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div data-testid="ai-provider-model-fields">
      <Form.Item name={providerName} label={t('workflow.ai.provider')} tooltip={t('workflow.ai.providerHint')}>
        {state.loaded && !state.failed && state.providers.length > 0 ? (
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t('workflow.ai.defaultProvider')}
            options={state.providers.map((provider) => ({ value: provider, label: provider }))}
          />
        ) : (
          <Input allowClear placeholder={t('workflow.ai.defaultProvider')} />
        )}
      </Form.Item>
      <Form.Item noStyle shouldUpdate={(prev, next) => prev[providerName] !== next[providerName]}>
        {({ getFieldValue }) => (
          <ModelField
            provider={typeof getFieldValue(providerName) === 'string' ? getFieldValue(providerName) : ''}
            modelName={modelName}
          />
        )}
      </Form.Item>
    </div>
  );
};

const ModelField: React.FC<{ provider: string; modelName: string }> = ({ provider, modelName }) => {
  const { t } = useTranslation();
  const [state, setState] = useState<{
    provider: string;
    models: Array<{ value: string; label: string }>;
    failed: boolean;
  }>({ provider: '', models: [], failed: false });

  useEffect(() => {
    let cancelled = false;
    if (!provider) return () => { cancelled = true; };
    getProviderModels(provider)
      .then((res) => {
        if (cancelled) return;
        if (res.code === 200 && Array.isArray(res.data)) {
          setState({
            provider,
            models: res.data.map((model) => ({ value: model.id, label: model.name || model.id })),
            failed: false,
          });
        } else {
          setState({ provider, models: [], failed: true });
        }
      })
      .catch(() => {
        if (!cancelled) setState({ provider, models: [], failed: true });
      });
    return () => {
      cancelled = true;
    };
  }, [provider]);

  // provider 切换后旧列表立即失效，不需要在 effect 里同步清空 state
  const options = state.provider === provider ? state.models : [];
  const failed = state.provider === provider ? state.failed : false;
  return (
    <Form.Item name={modelName} label={t('workflow.ai.model')} tooltip={t('workflow.ai.modelHint')}>
      {failed ? (
        <Input allowClear placeholder={t('workflow.ai.defaultModel')} />
      ) : (
        <AutoComplete
          allowClear
          options={options}
          placeholder={t('workflow.ai.defaultModel')}
          filterOption={(input, option) =>
            `${option?.value ?? ''} ${option?.label ?? ''}`.toLowerCase().includes(input.toLowerCase())
          }
        />
      )}
    </Form.Item>
  );
};

export default AiProviderModelFields;
