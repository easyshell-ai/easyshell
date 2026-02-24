import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card,
  Button,
  Space,
  Switch,
  Select,
  Input,
  InputNumber,
  Tabs,
  Form,
  message,
  Typography,
  Tag,
  Spin,
  theme,
  Row,
  Col,
  Divider,
  Modal,
  AutoComplete,
  Popconfirm,
  Slider,
  Collapse,
  Alert,
} from 'antd';
import {
  SaveOutlined,
  ApiOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  PlusOutlined,
  DeleteOutlined,
  SettingOutlined,
  DatabaseOutlined,
  RobotOutlined,
  GithubOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useResponsive } from '../../hooks/useResponsive';
import { getAiConfig, saveAiConfig, testAiConnection, copilotRequestDeviceCode, copilotPollToken, copilotGetStatus, copilotLogout, getCopilotModels } from '../../api/ai';
import type { AiTestResult } from '../../types';

const { Title, Text } = Typography;

const BUILTIN_PROVIDERS: Record<string, { label: string; hasApiKey: boolean; hasBaseUrl: boolean; models: string[] }> = {
  openai: {
    label: 'OpenAI',
    hasApiKey: true,
    hasBaseUrl: true,
    models: ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo', 'o1', 'o1-mini', 'o3-mini'],
  },
  anthropic: {
    label: 'Claude',
    hasApiKey: true,
    hasBaseUrl: false,
    models: ['claude-sonnet-4-20250514', 'claude-opus-4-20250514', 'claude-3-5-haiku-20241022'],
  },
  gemini: {
    label: 'Gemini',
    hasApiKey: true,
    hasBaseUrl: true,
    models: ['gemini-2.0-flash', 'gemini-2.0-flash-lite', 'gemini-1.5-pro'],
  },
  ollama: {
    label: 'Ollama',
    hasApiKey: false,
    hasBaseUrl: true,
    models: [],
  },
  'github-copilot': {
    label: 'GitHub Copilot',
    hasApiKey: false,
    hasBaseUrl: true,
    models: ['gpt-4o', 'gpt-4o-mini', 'claude-sonnet-4-20250514', 'o3-mini'],
  },
};

const EMBEDDING_PROVIDERS: Record<string, { label: string; models: string[] }> = {
  openai: {
    label: 'OpenAI',
    models: ['text-embedding-3-small', 'text-embedding-3-large', 'text-embedding-ada-002'],
  },
  dashscope: {
    label: 'DashScope (阿里云)',
    models: ['text-embedding-v3', 'text-embedding-v2', 'text-embedding-v1'],
  },
  ollama: {
    label: 'Ollama',
    models: ['nomic-embed-text', 'mxbai-embed-large', 'all-minilm'],
  },
  gemini: {
    label: 'Gemini',
    models: ['text-embedding-004'],
  },
};

const CHANNEL_LABELS: Record<string, string> = {
  telegram: 'Telegram',
  discord: 'Discord',
  dingtalk: '钉钉',
  feishu: '飞书',
  slack: 'Slack',
  'wechat-work': '企业微信',
};

const CHANNEL_SETTINGS_META: Record<string, { key: string; label: string; sensitive: boolean; placeholder: string }[]> = {
  telegram: [
    { key: 'bot-token', label: 'Bot Token', sensitive: true, placeholder: '请输入 Telegram Bot Token' },
    { key: 'allowed-chat-ids', label: '允许的 Chat ID', sensitive: false, placeholder: '多个 ID 用逗号分隔' },
  ],
  discord: [
    { key: 'bot-token', label: 'Bot Token', sensitive: true, placeholder: '请输入 Discord Bot Token' },
    { key: 'guild-id', label: 'Server (Guild) ID', sensitive: false, placeholder: '请输入 Discord Server ID' },
    { key: 'allowed-channel-ids', label: '允许的 Channel ID', sensitive: false, placeholder: '多个 ID 用逗号分隔' },
  ],
  dingtalk: [
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入钉钉 Webhook URL' },
    { key: 'secret', label: '签名密钥', sensitive: true, placeholder: '请输入钉钉签名 Secret' },
  ],
  feishu: [
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入飞书 Webhook URL' },
    { key: 'secret', label: '签名密钥', sensitive: true, placeholder: '请输入飞书签名 Secret' },
  ],
  slack: [
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入 Slack Webhook URL' },
    { key: 'bot-token', label: 'Bot Token', sensitive: true, placeholder: '请输入 Slack Bot Token' },
  ],
  'wechat-work': [
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入企业微信 Webhook URL' },
  ],
};

interface TestState {
  loading: boolean;
  result: AiTestResult | null;
}

interface ProviderForm {
  apiKey: string;
  baseUrl: string;
  model: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
}

interface EmbeddingForm {
  provider: string;
  model: string;
  apiKey: string;
  baseUrl: string;
}

interface OrchestratorForm {
  maxIterations: number;
  maxToolCalls: number;
  maxConsecutiveErrors: number;
  sopEnabled: boolean;
  memoryEnabled: boolean;
  systemPromptOverride: string;
}

const AiConfig: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { isMobile } = useResponsive();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [defaultProvider, setDefaultProvider] = useState('openai');
  const [providerForms, setProviderForms] = useState<Record<string, ProviderForm>>({});
  const [dailyLimit, setDailyLimit] = useState(100);
  const [maxTokens, setMaxTokens] = useState(4096);
  const [chatTimeout, setChatTimeout] = useState(120);
  const [testStates, setTestStates] = useState<Record<string, TestState>>({});
  const [channelForms, setChannelForms] = useState<Record<string, { enabled: boolean; settings: Record<string, string> }>>({});
  const [channelContextMode, setChannelContextMode] = useState<string>('persistent');
  const [channelSessionTimeout, setChannelSessionTimeout] = useState<number>(30);
  const [channelDefaultProvider, setChannelDefaultProvider] = useState<string>('');
  const [channelDefaultModel, setChannelDefaultModel] = useState<string>('');
  const [customProviders, setCustomProviders] = useState<Record<string, string>>({});
  const [addProviderModalVisible, setAddProviderModalVisible] = useState(false);
  const [newProviderKey, setNewProviderKey] = useState('');
  const [newProviderLabel, setNewProviderLabel] = useState('');

  // GitHub Copilot OAuth Device Flow state
  const [copilotAuthed, setCopilotAuthed] = useState(false);
  const [copilotLoading, setCopilotLoading] = useState(false);
  const [copilotDeviceInfo, setCopilotDeviceInfo] = useState<{ userCode: string; verificationUri: string; deviceCode: string } | null>(null);
  const [copilotPolling, setCopilotPolling] = useState(false);
  const copilotPollTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const [copilotModels, setCopilotModels] = useState<{ id: string; name: string }[]>([]);
  const [copilotModelsLoading, setCopilotModelsLoading] = useState(false);

  // Embedding config state
  const [embeddingForm, setEmbeddingForm] = useState<EmbeddingForm>({
    provider: 'dashscope',
    model: 'text-embedding-v3',
    apiKey: '',
    baseUrl: '',
  });

  // Orchestrator config state
  const [orchestratorForm, setOrchestratorForm] = useState<OrchestratorForm>({
    maxIterations: 10,
    maxToolCalls: 50,
    maxConsecutiveErrors: 3,
    sopEnabled: true,
    memoryEnabled: true,
    systemPromptOverride: '',
  });

  const allProviders = (): { key: string; label: string }[] => {
    const result: { key: string; label: string }[] = [];
    Object.entries(BUILTIN_PROVIDERS).forEach(([key, cfg]) => {
      result.push({ key, label: cfg.label });
    });
    Object.entries(customProviders).forEach(([key, label]) => {
      if (!BUILTIN_PROVIDERS[key]) {
        result.push({ key, label });
      }
    });
    return result;
  };

  const fetchConfig = useCallback(() => {
    setLoading(true);
    getAiConfig()
      .then((res) => {
        if (res.code === 200 && res.data) {
          const data = res.data;
          setEnabled(data.enabled);
          setDefaultProvider(data.defaultProvider || 'openai');
          setDailyLimit(data.quota?.dailyLimit ?? 100);
          setMaxTokens(data.quota?.maxTokens ?? 4096);
          setChatTimeout(data.quota?.chatTimeout ?? 120);

          const forms: Record<string, ProviderForm> = {};
          const customs: Record<string, string> = {};
          Object.entries(data.providers || {}).forEach(([key, p]) => {
            forms[key] = {
              apiKey: p.apiKey || '',
              baseUrl: p.baseUrl || '',
              model: p.model || '',
              temperature: p.temperature,
              topP: p.topP,
              maxTokens: p.maxTokens,
            };
            if (!BUILTIN_PROVIDERS[key]) {
              customs[key] = key;
            }
          });
          setProviderForms(forms);
          setCustomProviders(customs);

          const channels: Record<string, { enabled: boolean; settings: Record<string, string> }> = {};
          Object.entries(data.channels || {}).forEach(([key, ch]) => {
            channels[key] = {
              enabled: ch.enabled,
              settings: { ...(ch.settings || {}) },
            };
          });
          setChannelForms(channels);

          if (data.channelContext) {
            setChannelContextMode(data.channelContext.contextMode || 'persistent');
            setChannelSessionTimeout(data.channelContext.sessionTimeout ?? 30);
            setChannelDefaultProvider(data.channelContext.defaultProvider || '');
            setChannelDefaultModel(data.channelContext.defaultModel || '');
          }

          if (data.embedding) {
            setEmbeddingForm({
              provider: data.embedding.provider || 'dashscope',
              model: data.embedding.model || 'text-embedding-v3',
              apiKey: data.embedding.apiKey || '',
              baseUrl: data.embedding.baseUrl || '',
            });
          }

          if (data.orchestrator) {
            setOrchestratorForm({
              maxIterations: data.orchestrator.maxIterations ?? 10,
              maxToolCalls: data.orchestrator.maxToolCalls ?? 50,
              maxConsecutiveErrors: data.orchestrator.maxConsecutiveErrors ?? 3,
              sopEnabled: data.orchestrator.sopEnabled ?? true,
              memoryEnabled: data.orchestrator.memoryEnabled ?? true,
              systemPromptOverride: data.orchestrator.systemPromptOverride || '',
            });
          }
        }
      })
      .catch(() => message.error(t('aiConfig.fetchError')))
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => {
    fetchConfig();
  }, [fetchConfig]);

  useEffect(() => {
    copilotGetStatus()
      .then((res) => {
        if (res.code === 200 && res.data) {
          setCopilotAuthed(res.data.authenticated);
        }
      })
      .catch(() => {});
  }, []);

  const fetchCopilotModels = useCallback(() => {
    setCopilotModelsLoading(true);
    getCopilotModels()
      .then((res) => {
        if (res.code === 200 && res.data) {
          setCopilotModels(res.data.map((m) => ({ id: m.id, name: m.name || m.id })));
        }
      })
      .catch(() => {})
      .finally(() => setCopilotModelsLoading(false));
  }, []);

  useEffect(() => {
    if (copilotAuthed) {
      fetchCopilotModels();
    }
  }, [copilotAuthed, fetchCopilotModels]);

  useEffect(() => {
    return () => {
      if (copilotPollTimer.current) {
        clearInterval(copilotPollTimer.current);
      }
    };
  }, []);

  const handleSave = async () => {
    setSaving(true);
    try {
      const providers: Record<string, { apiKey?: string; baseUrl?: string; model?: string; temperature?: number; topP?: number; maxTokens?: number }> = {};
      Object.entries(providerForms).forEach(([key, form]) => {
        providers[key] = {};
        if (form.apiKey && !form.apiKey.includes('***')) {
          providers[key].apiKey = form.apiKey;
        }
        providers[key].baseUrl = form.baseUrl;
        providers[key].model = form.model;
        if (form.temperature !== undefined) providers[key].temperature = form.temperature;
        if (form.topP !== undefined) providers[key].topP = form.topP;
        if (form.maxTokens !== undefined) providers[key].maxTokens = form.maxTokens;
      });

      const res = await saveAiConfig({
        enabled,
        defaultProvider,
        providers,
        quota: { dailyLimit, maxTokens, chatTimeout },
        channels: channelForms,
        channelContext: {
          contextMode: channelContextMode,
          sessionTimeout: channelSessionTimeout,
          defaultProvider: channelDefaultProvider,
          defaultModel: channelDefaultModel,
        },
        embedding: {
          provider: embeddingForm.provider,
          model: embeddingForm.model,
          apiKey: embeddingForm.apiKey && !embeddingForm.apiKey.includes('***') ? embeddingForm.apiKey : undefined,
          baseUrl: embeddingForm.baseUrl,
        },
        orchestrator: {
          maxIterations: orchestratorForm.maxIterations,
          maxToolCalls: orchestratorForm.maxToolCalls,
          maxConsecutiveErrors: orchestratorForm.maxConsecutiveErrors,
          sopEnabled: orchestratorForm.sopEnabled,
          memoryEnabled: orchestratorForm.memoryEnabled,
          systemPromptOverride: orchestratorForm.systemPromptOverride,
        },
      });

      if (res.code === 200) {
        message.success(t('aiConfig.saveSuccess'));
        fetchConfig();
      } else {
        message.error(res.message || t('aiConfig.saveError'));
      }
    } catch {
      message.error(t('aiConfig.saveError'));
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async (provider: string) => {
    setTestStates((prev) => ({ ...prev, [provider]: { loading: true, result: null } }));

    const form = providerForms[provider];
    try {
      const res = await testAiConnection({
        provider,
        apiKey: form?.apiKey && !form.apiKey.includes('***') ? form.apiKey : undefined,
        baseUrl: form?.baseUrl || undefined,
        model: form?.model || undefined,
      });

      if (res.code === 200) {
        setTestStates((prev) => ({ ...prev, [provider]: { loading: false, result: res.data } }));
      } else {
        setTestStates((prev) => ({
          ...prev,
          [provider]: {
            loading: false,
            result: { success: false, message: res.message || t('aiConfig.testError'), responseTimeMs: 0, modelInfo: '' },
          },
        }));
      }
    } catch {
      setTestStates((prev) => ({
        ...prev,
        [provider]: {
          loading: false,
          result: { success: false, message: t('aiConfig.testError'), responseTimeMs: 0, modelInfo: '' },
        },
      }));
    }
  };

  const updateProviderForm = (provider: string, field: string, value: string | number | undefined) => {
    setProviderForms((prev) => ({
      ...prev,
      [provider]: { ...(prev[provider] || { apiKey: '', baseUrl: '', model: '' }), [field]: value },
    }));
  };

  const handleAddProvider = () => {
    const key = newProviderKey.trim().toLowerCase().replace(/[^a-z0-9-_]/g, '');
    const label = newProviderLabel.trim();
    if (!key) { message.warning(t('aiConfig.providerKeyRequired')); return; }
    if (!label) { message.warning(t('aiConfig.providerLabelRequired')); return; }
    if (BUILTIN_PROVIDERS[key] || customProviders[key]) { message.warning(t('aiConfig.providerExists')); return; }
    setCustomProviders((prev) => ({ ...prev, [key]: label }));
    setProviderForms((prev) => ({ ...prev, [key]: { apiKey: '', baseUrl: '', model: '' } }));
    setNewProviderKey('');
    setNewProviderLabel('');
    setAddProviderModalVisible(false);
    message.success(t('aiConfig.providerAdded', { name: label }));
  };

  const handleRemoveProvider = (key: string) => {
    setCustomProviders((prev) => { const next = { ...prev }; delete next[key]; return next; });
    setProviderForms((prev) => { const next = { ...prev }; delete next[key]; return next; });
    if (defaultProvider === key) setDefaultProvider('openai');
    message.success(t('aiConfig.providerRemoved'));
  };

  const updateChannelEnabled = (channel: string, value: boolean) => {
    setChannelForms((prev) => ({
      ...prev,
      [channel]: { ...prev[channel], enabled: value },
    }));
  };

  const updateChannelSetting = (channel: string, key: string, value: string) => {
    setChannelForms((prev) => ({
      ...prev,
      [channel]: {
        ...prev[channel],
        settings: { ...(prev[channel]?.settings || {}), [key]: value },
      },
    }));
  };

  const handleCopilotLogin = async () => {
    setCopilotLoading(true);
    try {
      const res = await copilotRequestDeviceCode();
      if (res.code === 200 && res.data) {
        const { deviceCode, userCode, verificationUri, interval } = res.data;
        setCopilotDeviceInfo({ userCode, verificationUri, deviceCode });
        window.open(verificationUri, '_blank');
        setCopilotPolling(true);
        const pollInterval = Math.max((interval || 5) * 1000, 5000);
        copilotPollTimer.current = setInterval(async () => {
          try {
            const pollRes = await copilotPollToken(deviceCode);
            if (pollRes.code === 200 && pollRes.data) {
              if (pollRes.data.status === 'success') {
                clearInterval(copilotPollTimer.current!);
                copilotPollTimer.current = null;
                setCopilotPolling(false);
                setCopilotDeviceInfo(null);
                setCopilotAuthed(true);
                message.success(t('aiConfig.provider.copilotAuthSuccess'));
              } else if (pollRes.data.status === 'expired' || pollRes.data.status === 'denied') {
                clearInterval(copilotPollTimer.current!);
                copilotPollTimer.current = null;
                setCopilotPolling(false);
                setCopilotDeviceInfo(null);
                message.error(pollRes.data.message || t('aiConfig.provider.copilotAuthFailed'));
              }
            }
          } catch {
            // ignore poll errors
          }
        }, pollInterval);
      } else {
        message.error(res.message || t('aiConfig.provider.copilotDeviceCodeError'));
      }
    } catch {
      message.error(t('aiConfig.provider.copilotDeviceCodeError'));
    } finally {
      setCopilotLoading(false);
    }
  };

  const handleCopilotLogout = async () => {
    try {
      await copilotLogout();
      setCopilotAuthed(false);
      message.success(t('aiConfig.provider.copilotLogoutSuccess'));
    } catch {
      message.error(t('aiConfig.provider.copilotLogoutError'));
    }
  };

  const renderProviderTab = (providerKey: string) => {
    const form = providerForms[providerKey] || { apiKey: '', baseUrl: '', model: '' };
    const testState = testStates[providerKey];
    const builtIn = BUILTIN_PROVIDERS[providerKey];
    const hasApiKey = builtIn ? builtIn.hasApiKey : true;
    const hasBaseUrl = builtIn ? builtIn.hasBaseUrl : true;
    const modelSuggestions = providerKey === 'github-copilot' && copilotModels.length > 0 ? copilotModels.map((m) => m.id) : (builtIn ? builtIn.models : []);
    const isCustom = !builtIn;
    const providerLabel = builtIn ? builtIn.label : customProviders[providerKey] || providerKey;

    return (
      <div style={{ opacity: enabled ? 1 : 0.5, pointerEvents: enabled ? 'auto' : 'none' }}>
        {isCustom && (
          <div style={{ marginBottom: 16 }}>
            <Popconfirm title={t('aiConfig.removeProviderConfirm')} onConfirm={() => handleRemoveProvider(providerKey)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
              <Button danger size="small" icon={<DeleteOutlined />}>{t('aiConfig.removeProvider')}</Button>
            </Popconfirm>
          </div>
        )}
        <Form layout="vertical" style={{ maxWidth: 600 }}>
          {hasApiKey && (
            <Form.Item label={t('aiConfig.provider.apiKey')}>
              <Space.Compact style={{ width: '100%' }}>
                <Input.Password
                  value={form.apiKey}
                  onChange={(e) => updateProviderForm(providerKey, 'apiKey', e.target.value)}
                  placeholder={t('aiConfig.provider.apiKeyPlaceholder', { provider: providerLabel })}
                  style={{ flex: 1 }}
                />
                <Button
                  icon={testState?.loading ? <LoadingOutlined /> : <ApiOutlined />}
                  loading={testState?.loading}
                  onClick={() => handleTest(providerKey)}
                >
                  {t('aiConfig.provider.testConnection')}
                </Button>
              </Space.Compact>
            </Form.Item>
          )}

          {!hasApiKey && providerKey !== 'github-copilot' && (
            <Form.Item label={t('aiConfig.provider.connectionStatus')}>
              <Button
                icon={testState?.loading ? <LoadingOutlined /> : <ApiOutlined />}
                loading={testState?.loading}
                onClick={() => handleTest(providerKey)}
              >
                {t('aiConfig.provider.testConnection')}
              </Button>
            </Form.Item>
          )}

          {providerKey === 'github-copilot' && (
            <Form.Item label={t('aiConfig.provider.copilotAuth')}>
              {copilotAuthed ? (
                <Space>
                  <Tag icon={<CheckCircleOutlined />} color="success">{t('aiConfig.provider.copilotAuthed')}</Tag>
                  <Popconfirm title={t('aiConfig.provider.copilotLogoutConfirm')} onConfirm={handleCopilotLogout} okText={t('common.confirm')} cancelText={t('common.cancel')}>
                    <Button size="small" danger>{t('aiConfig.provider.copilotLogout')}</Button>
                  </Popconfirm>
                  <Button
                    icon={testState?.loading ? <LoadingOutlined /> : <ApiOutlined />}
                    loading={testState?.loading}
                    onClick={() => handleTest(providerKey)}
                    size="small"
                  >
                    {t('aiConfig.provider.testConnection')}
                  </Button>
                </Space>
              ) : (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Button
                    type="primary"
                    icon={<GithubOutlined />}
                    loading={copilotLoading}
                    onClick={handleCopilotLogin}
                  >
                    {t('aiConfig.provider.copilotLogin')}
                  </Button>
                  {copilotDeviceInfo && (
                    <Alert
                      type="info"
                      showIcon
                      message={
                        <Space direction="vertical" size={4}>
                          <Text>{t('aiConfig.provider.copilotDeviceCodeHint')}</Text>
                          <Text
                            copyable
                            strong
                            style={{ fontSize: 24, letterSpacing: 4, fontFamily: 'monospace' }}
                          >
                            {copilotDeviceInfo.userCode}
                          </Text>
                          {copilotPolling && (
                            <Space>
                              <LoadingOutlined />
                              <Text type="secondary">{t('aiConfig.provider.copilotPolling')}</Text>
                            </Space>
                          )}
                          <Button
                            type="link"
                            style={{ padding: 0 }}
                            onClick={() => window.open(copilotDeviceInfo.verificationUri, '_blank')}
                          >
                            {t('aiConfig.provider.copilotReopenGithub')}
                          </Button>
                        </Space>
                      }
                    />
                  )}
                </Space>
              )}
            </Form.Item>
          )}

          {hasBaseUrl && (
            <Form.Item label={t('aiConfig.provider.baseUrl')}>
              <Input
                value={form.baseUrl}
                onChange={(e) => updateProviderForm(providerKey, 'baseUrl', e.target.value)}
                placeholder={t('aiConfig.provider.baseUrlPlaceholder')}
              />
            </Form.Item>
          )}

          <Form.Item label={t('aiConfig.provider.model')} extra={t('aiConfig.provider.modelHint')}>
            <Space.Compact style={{ width: '100%' }}>
              <AutoComplete
                value={form.model}
                onChange={(v) => updateProviderForm(providerKey, 'model', v)}
                options={modelSuggestions.map((m) => ({ label: m, value: m }))}
                placeholder={modelSuggestions.length > 0 ? t('aiConfig.provider.modelPlaceholder') : t('aiConfig.provider.modelPlaceholder')}
                filterOption={(input, option) =>
                  (option?.value as string)?.toLowerCase().includes(input.toLowerCase()) ?? false
                }
                style={{ flex: 1 }}
              />
              {providerKey === 'github-copilot' && copilotAuthed && (
                <Button
                  icon={copilotModelsLoading ? <LoadingOutlined /> : <ApiOutlined />}
                  loading={copilotModelsLoading}
                  onClick={fetchCopilotModels}
                >
                  {t('aiConfig.provider.refreshModels')}
                </Button>
              )}
            </Space.Compact>
          </Form.Item>

          <Collapse
            ghost
            items={[
              {
                key: 'advanced',
                label: (
                  <Space>
                    <SettingOutlined />
                    <span>{t('aiConfig.provider.advancedSettings')}</span>
                  </Space>
                ),
                children: (
                  <>
                    <Form.Item label={t('aiConfig.provider.temperature')} extra={t('aiConfig.provider.temperatureHint')}>
                      <Row gutter={16}>
                        <Col span={16}>
                          <Slider
                            min={0}
                            max={2}
                            step={0.1}
                            value={form.temperature ?? 0.7}
                            onChange={(v) => updateProviderForm(providerKey, 'temperature', v)}
                          />
                        </Col>
                        <Col span={8}>
                          <InputNumber
                            min={0}
                            max={2}
                            step={0.1}
                            value={form.temperature ?? 0.7}
                            onChange={(v) => updateProviderForm(providerKey, 'temperature', v ?? undefined)}
                            style={{ width: '100%' }}
                          />
                        </Col>
                      </Row>
                    </Form.Item>

                    <Form.Item label={t('aiConfig.provider.topP')} extra={t('aiConfig.provider.topPHint')}>
                      <Row gutter={16}>
                        <Col span={16}>
                          <Slider
                            min={0}
                            max={1}
                            step={0.05}
                            value={form.topP ?? 0.9}
                            onChange={(v) => updateProviderForm(providerKey, 'topP', v)}
                          />
                        </Col>
                        <Col span={8}>
                          <InputNumber
                            min={0}
                            max={1}
                            step={0.05}
                            value={form.topP ?? 0.9}
                            onChange={(v) => updateProviderForm(providerKey, 'topP', v ?? undefined)}
                            style={{ width: '100%' }}
                          />
                        </Col>
                      </Row>
                    </Form.Item>

                    <Form.Item label={t('aiConfig.provider.providerMaxTokens')} extra={t('aiConfig.provider.providerMaxTokensHint')}>
                      <InputNumber
                        min={256}
                        max={128000}
                        value={form.maxTokens}
                        onChange={(v) => updateProviderForm(providerKey, 'maxTokens', v ?? undefined)}
                        placeholder={t('aiConfig.provider.providerMaxTokensPlaceholder')}
                        style={{ width: '100%' }}
                      />
                    </Form.Item>
                  </>
                ),
              },
            ]}
          />

          {testState?.result && (
            <Form.Item label={t('aiConfig.provider.connectionStatus')}>
              <div
                style={{
                  padding: '12px 16px',
                  borderRadius: 8,
                  background: testState.result.success
                    ? token.colorSuccessBg
                    : token.colorErrorBg,
                  border: `1px solid ${testState.result.success ? token.colorSuccessBorder : token.colorErrorBorder}`,
                }}
              >
                <Space>
                  {testState.result.success ? (
                    <CheckCircleOutlined style={{ color: token.colorSuccess, fontSize: 16 }} />
                  ) : (
                    <CloseCircleOutlined style={{ color: token.colorError, fontSize: 16 }} />
                  )}
                  <Text strong style={{ color: testState.result.success ? token.colorSuccess : token.colorError }}>
                    {testState.result.message}
                  </Text>
                  {testState.result.success && testState.result.responseTimeMs > 0 && (
                    <Tag color="blue">{testState.result.responseTimeMs}ms</Tag>
                  )}
                </Space>
                {testState.result.success && testState.result.modelInfo && (
                  <div style={{ marginTop: 8 }}>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {testState.result.modelInfo}
                    </Text>
                  </div>
                )}
              </div>
            </Form.Item>
          )}
        </Form>
      </div>
    );
  };

  const renderChannelTab = (channel: string) => {
    const form = channelForms[channel] || { enabled: false, settings: {} };
    const meta = CHANNEL_SETTINGS_META[channel] || [];

    return (
      <div style={{ opacity: enabled ? 1 : 0.5, pointerEvents: enabled ? 'auto' : 'none' }}>
        <Form layout="vertical" style={{ maxWidth: 600 }}>
          <Form.Item label={t('aiConfig.channel.enabled')}>
            <Switch checked={form.enabled} onChange={(v) => updateChannelEnabled(channel, v)} />
          </Form.Item>
          {meta.map((field) => (
            <Form.Item key={field.key} label={field.label}>
              {field.sensitive ? (
                <Input.Password
                  value={form.settings?.[field.key] || ''}
                  onChange={(e) => updateChannelSetting(channel, field.key, e.target.value)}
                  placeholder={field.placeholder}
                />
              ) : (
                <Input
                  value={form.settings?.[field.key] || ''}
                  onChange={(e) => updateChannelSetting(channel, field.key, e.target.value)}
                  placeholder={field.placeholder}
                />
              )}
            </Form.Item>
          ))}
          <Divider dashed />
          <Form.Item label={t('aiConfig.channel.channelProvider')} extra={t('aiConfig.channel.channelProviderHint')}>
            <Select
              value={form.settings?.['provider'] || ''}
              onChange={(v) => updateChannelSetting(channel, 'provider', v)}
              allowClear
              placeholder={t('aiConfig.channel.channelProviderPlaceholder')}
              options={[
                { label: t('aiConfig.channel.useDefault'), value: '' },
                ...allProviders().map(({ key, label }) => ({ label, value: key })),
              ]}
            />
          </Form.Item>
          <Form.Item label={t('aiConfig.channel.channelModel')} extra={t('aiConfig.channel.channelModelHint')}>
            <Input
              value={form.settings?.['model'] || ''}
              onChange={(e) => updateChannelSetting(channel, 'model', e.target.value)}
              placeholder={t('aiConfig.channel.channelModelPlaceholder')}
            />
          </Form.Item>
        </Form>
      </div>
    );
  };

  const renderEmbeddingConfig = () => {
    const embeddingModels = EMBEDDING_PROVIDERS[embeddingForm.provider]?.models || [];

    return (
      <Card
        style={{ borderRadius: 12, marginBottom: 16, opacity: enabled ? 1 : 0.5, pointerEvents: enabled ? 'auto' : 'none' }}
      >
        <Space style={{ marginBottom: 16 }}>
          <DatabaseOutlined style={{ fontSize: 18, color: token.colorPrimary }} />
          <Title level={5} style={{ margin: 0, color: token.colorText }}>{t('aiConfig.embedding.title')}</Title>
        </Space>
        <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
          {t('aiConfig.embedding.description')}
        </Text>
        <Form layout="vertical" style={{ maxWidth: 600 }}>
          <Form.Item label={t('aiConfig.embedding.provider')}>
            <Select
              value={embeddingForm.provider}
              onChange={(v) => {
                setEmbeddingForm((prev) => ({
                  ...prev,
                  provider: v,
                  model: EMBEDDING_PROVIDERS[v]?.models[0] || '',
                }));
              }}
              options={Object.entries(EMBEDDING_PROVIDERS).map(([key, cfg]) => ({
                label: cfg.label,
                value: key,
              }))}
            />
          </Form.Item>

          <Form.Item label={t('aiConfig.embedding.model')}>
            <AutoComplete
              value={embeddingForm.model}
              onChange={(v) => setEmbeddingForm((prev) => ({ ...prev, model: v }))}
              options={embeddingModels.map((m) => ({ label: m, value: m }))}
              placeholder={t('aiConfig.embedding.modelPlaceholder')}
              filterOption={(input, option) =>
                (option?.value as string)?.toLowerCase().includes(input.toLowerCase()) ?? false
              }
            />
          </Form.Item>

          <Form.Item label={t('aiConfig.embedding.apiKey')}>
            <Input.Password
              value={embeddingForm.apiKey}
              onChange={(e) => setEmbeddingForm((prev) => ({ ...prev, apiKey: e.target.value }))}
              placeholder={t('aiConfig.embedding.apiKeyPlaceholder')}
            />
          </Form.Item>

          <Form.Item label={t('aiConfig.embedding.baseUrl')} extra={t('aiConfig.embedding.baseUrlHint')}>
            <Input
              value={embeddingForm.baseUrl}
              onChange={(e) => setEmbeddingForm((prev) => ({ ...prev, baseUrl: e.target.value }))}
              placeholder={t('aiConfig.embedding.baseUrlPlaceholder')}
            />
          </Form.Item>
        </Form>
      </Card>
    );
  };

  const renderOrchestratorConfig = () => {
    return (
      <Card
        style={{ borderRadius: 12, marginBottom: 16, opacity: enabled ? 1 : 0.5, pointerEvents: enabled ? 'auto' : 'none' }}
      >
        <Space style={{ marginBottom: 16 }}>
          <RobotOutlined style={{ fontSize: isMobile ? 16 : 18, color: token.colorPrimary }} />
          <Title level={5} style={{ margin: 0, color: token.colorText }}>{t('aiConfig.orchestrator.title')}</Title>
        </Space>
        <Text type="secondary" style={{ display: 'block', marginBottom: 16, fontSize: isMobile ? 12 : 14 }}>
          {t('aiConfig.orchestrator.description')}
        </Text>
        <Form layout="vertical" style={{ maxWidth: isMobile ? '100%' : 600 }}>
          <Row gutter={[16, 0]}>
            <Col xs={24} sm={8}>
              <Form.Item label={t('aiConfig.orchestrator.maxIterations')} extra={!isMobile && t('aiConfig.orchestrator.maxIterationsHint')}>
                <InputNumber
                  value={orchestratorForm.maxIterations}
                  onChange={(v) => setOrchestratorForm((prev) => ({ ...prev, maxIterations: v ?? 10 }))}
                  min={1}
                  max={100}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item label={t('aiConfig.orchestrator.maxToolCalls')} extra={!isMobile && t('aiConfig.orchestrator.maxToolCallsHint')}>
                <InputNumber
                  value={orchestratorForm.maxToolCalls}
                  onChange={(v) => setOrchestratorForm((prev) => ({ ...prev, maxToolCalls: v ?? 50 }))}
                  min={1}
                  max={500}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item label={t('aiConfig.orchestrator.maxConsecutiveErrors')} extra={!isMobile && t('aiConfig.orchestrator.maxConsecutiveErrorsHint')}>
                <InputNumber
                  value={orchestratorForm.maxConsecutiveErrors}
                  onChange={(v) => setOrchestratorForm((prev) => ({ ...prev, maxConsecutiveErrors: v ?? 3 }))}
                  min={1}
                  max={10}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </Col>
          </Row>

          <Divider />

          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12}>
              <Form.Item label={t('aiConfig.orchestrator.sopEnabled')}>
                <Space wrap>
                  <Switch
                    checked={orchestratorForm.sopEnabled}
                    onChange={(v) => setOrchestratorForm((prev) => ({ ...prev, sopEnabled: v }))}
                  />
                  {!isMobile && <Text type="secondary">{t('aiConfig.orchestrator.sopEnabledHint')}</Text>}
                </Space>
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item label={t('aiConfig.orchestrator.memoryEnabled')}>
                <Space wrap>
                  <Switch
                    checked={orchestratorForm.memoryEnabled}
                    onChange={(v) => setOrchestratorForm((prev) => ({ ...prev, memoryEnabled: v }))}
                  />
                  {!isMobile && <Text type="secondary">{t('aiConfig.orchestrator.memoryEnabledHint')}</Text>}
                </Space>
              </Form.Item>
            </Col>
          </Row>

          <Form.Item>
            <Button type="link" onClick={() => navigate('/system/risk')} style={{ padding: 0 }}>
              {t('aiConfig.orchestrator.riskControlLink')}
            </Button>
          </Form.Item>

          <Form.Item
            label={t('aiConfig.orchestrator.systemPromptOverride')}
            extra={t('aiConfig.orchestrator.systemPromptOverrideHint')}
          >
            <Input.TextArea
              value={orchestratorForm.systemPromptOverride}
              onChange={(e) => setOrchestratorForm((prev) => ({ ...prev, systemPromptOverride: e.target.value }))}
              placeholder={t('aiConfig.orchestrator.systemPromptOverridePlaceholder')}
              rows={4}
            />
          </Form.Item>
        </Form>
      </Card>
    );
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div>
      <div style={{ 
        marginBottom: 16, 
        display: 'flex', 
        justifyContent: 'space-between', 
        alignItems: 'center',
        flexWrap: isMobile ? 'wrap' : 'nowrap',
        gap: 8,
      }}>
        <Title level={isMobile ? 5 : 4} style={{ margin: 0, color: token.colorText }}>
          {t('aiConfig.title')}
        </Title>
        <Button
          type="primary"
          icon={<SaveOutlined />}
          loading={saving}
          onClick={handleSave}
          size={isMobile ? 'small' : 'middle'}
        >
          {t('aiConfig.saveConfig')}
        </Button>
      </div>

      <Card style={{ borderRadius: 12, marginBottom: 16 }}>
        <Row gutter={[16, 16]} align="middle">
          <Col xs={24} sm={12} md={8}>
            <Space size="middle" wrap>
              <Text strong style={{ color: token.colorText }}>{t('aiConfig.enableAi')}</Text>
              <Switch checked={enabled} onChange={setEnabled} />
            </Space>
          </Col>
          {!isMobile && (
            <Col>
              <Divider type="vertical" style={{ height: 24 }} />
            </Col>
          )}
          <Col xs={24} sm={12} md={14}>
            <Space size="middle" wrap>
              <Text strong style={{ color: token.colorText }}>{t('aiConfig.defaultProvider')}</Text>
              <Select
                value={defaultProvider}
                onChange={setDefaultProvider}
                style={{ width: isMobile ? 140 : 160 }}
                disabled={!enabled}
                options={allProviders().map(({ key, label }) => ({ label, value: key }))}
              />
              {enabled && (
                <Tag color="green">{t('aiConfig.providerEnabled')}</Tag>
              )}
              {!enabled && (
                <Tag color="default">{t('aiConfig.providerDisabled')}</Tag>
              )}
            </Space>
          </Col>
        </Row>
      </Card>

      <Card style={{ borderRadius: 12, marginBottom: 16 }}>
        <Tabs
          items={allProviders().map(({ key, label }) => ({
            key,
            label,
            children: renderProviderTab(key),
          }))}
          tabBarExtraContent={
            <Button
              type="dashed"
              size="small"
              icon={<PlusOutlined />}
              onClick={() => setAddProviderModalVisible(true)}
              disabled={!enabled}
            >
              {t('aiConfig.addProvider')}
            </Button>
          }
        />
      </Card>

      {renderEmbeddingConfig()}

      {renderOrchestratorConfig()}

      <Card
        style={{ borderRadius: 12, marginBottom: 16, opacity: enabled ? 1 : 0.5, pointerEvents: enabled ? 'auto' : 'none' }}
      >
        <Title level={5} style={{ marginTop: 0, color: token.colorText }}>{t('aiConfig.quota.title')}</Title>
        <Form layout="vertical" style={{ maxWidth: 400 }}>
          <Form.Item label={t('aiConfig.quota.dailyLimit')}>
            <InputNumber
              value={dailyLimit}
              onChange={(v) => setDailyLimit(v ?? 100)}
              min={1}
              max={10000}
              addonAfter={t('common.times')}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item label={t('aiConfig.quota.maxTokens')}>
            <InputNumber
              value={maxTokens}
              onChange={(v) => setMaxTokens(v ?? 4096)}
              min={256}
              max={128000}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item label={t('aiConfig.quota.chatTimeout')} extra={t('aiConfig.quota.chatTimeoutHint')}>
            <InputNumber
              value={chatTimeout}
              onChange={(v) => setChatTimeout(v ?? 120)}
              min={10}
              max={600}
              addonAfter={t('common.seconds')}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Form>
      </Card>

      <Card
        style={{ borderRadius: 12, marginBottom: 16, opacity: enabled ? 1 : 0.5, pointerEvents: enabled ? 'auto' : 'none' }}
      >
        <Title level={5} style={{ marginTop: 0, color: token.colorText }}>{t('aiConfig.channel.title')}</Title>
        <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
          {t('aiConfig.channel.description')}
        </Text>
        <Collapse
          ghost
          style={{ marginBottom: 16 }}
          items={[
            {
              key: 'context',
              label: (
                <Space>
                  <SettingOutlined />
                  <span>{t('aiConfig.channel.contextTitle')}</span>
                </Space>
              ),
              children: (
                <Form layout="vertical" style={{ maxWidth: 600 }}>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item label={t('aiConfig.channel.contextMode')} extra={t('aiConfig.channel.contextModeHint')}>
                        <Select
                          value={channelContextMode}
                          onChange={setChannelContextMode}
                          options={[
                            { label: t('aiConfig.channel.contextModePersistent'), value: 'persistent' },
                            { label: t('aiConfig.channel.contextModeStateless'), value: 'stateless' },
                          ]}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item label={t('aiConfig.channel.sessionTimeout')} extra={t('aiConfig.channel.sessionTimeoutHint')}>
                        <InputNumber
                          value={channelSessionTimeout}
                          onChange={(v) => setChannelSessionTimeout(v ?? 30)}
                          min={0}
                          max={1440}
                          addonAfter={t('common.minutes')}
                          style={{ width: '100%' }}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item label={t('aiConfig.channel.defaultProvider')} extra={t('aiConfig.channel.defaultProviderHint')}>
                        <Select
                          value={channelDefaultProvider}
                          onChange={setChannelDefaultProvider}
                          allowClear
                          placeholder={t('aiConfig.channel.defaultProviderPlaceholder')}
                          options={[
                            { label: t('aiConfig.channel.useSystemDefault'), value: '' },
                            ...allProviders().map(({ key, label }) => ({ label, value: key })),
                          ]}
                        />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item label={t('aiConfig.channel.defaultModel')} extra={t('aiConfig.channel.defaultModelHint')}>
                        <Input
                          value={channelDefaultModel}
                          onChange={(e) => setChannelDefaultModel(e.target.value)}
                          placeholder={t('aiConfig.channel.defaultModelPlaceholder')}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Alert
                    type="info"
                    showIcon
                    message={t('aiConfig.channel.newCommandHint')}
                    style={{ marginTop: 8 }}
                  />
                </Form>
              ),
            },
          ]}
        />
        <Tabs
          items={Object.keys(CHANNEL_LABELS).map((key) => ({
            key,
            label: t(`aiConfig.channel.${key === 'wechat-work' ? 'wechatWork' : key}`),
            children: renderChannelTab(key),
          }))}
        />
      </Card>

      <Modal
        title={t('aiConfig.addProviderModal.title')}
        open={addProviderModalVisible}
        onCancel={() => { setAddProviderModalVisible(false); setNewProviderKey(''); setNewProviderLabel(''); }}
        onOk={handleAddProvider}
        okText={t('common.add')}
        cancelText={t('common.cancel')}
        destroyOnClose
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item label={t('aiConfig.addProviderModal.key')} extra={t('aiConfig.addProviderModal.keyHint')}>
            <Input
              value={newProviderKey}
              onChange={(e) => setNewProviderKey(e.target.value)}
              placeholder={t('aiConfig.addProviderModal.keyPlaceholder')}
            />
          </Form.Item>
          <Form.Item label={t('aiConfig.addProviderModal.label')}>
            <Input
              value={newProviderLabel}
              onChange={(e) => setNewProviderLabel(e.target.value)}
              placeholder={t('aiConfig.addProviderModal.labelPlaceholder')}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AiConfig;
