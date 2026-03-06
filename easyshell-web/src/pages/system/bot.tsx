import { useState, useEffect, useCallback } from 'react';
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
  Spin,
  theme,
  Row,
  Col,
  Divider,
  Collapse,
  Alert,
} from 'antd';
import {
  SaveOutlined,
  ApiOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import { getAiConfig, saveAiConfig } from '../../api/ai';

const { Title, Text } = Typography;

const CHANNEL_LABELS: Record<string, string> = {
  telegram: 'Telegram',
  discord: 'Discord',
  dingtalk: '钉钉',
  feishu: '飞书',
  slack: 'Slack',
  'wechat-work': '企业微信',
};

const CHANNEL_SETTINGS_META: Record<string, { key: string; label: string; sensitive: boolean; placeholder: string; type?: 'input' | 'select'; options?: { label: string; value: string }[]; visibleWhen?: { key: string; value: string } }[]> = {
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
    { key: 'mode', label: '连接模式', sensitive: false, placeholder: '', type: 'select', options: [{ label: 'Webhook', value: 'webhook' }, { label: 'Stream (免公网IP)', value: 'stream' }] },
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入钉钉 Webhook URL', visibleWhen: { key: 'mode', value: 'webhook' } },
    { key: 'secret', label: '签名密钥', sensitive: true, placeholder: '请输入钉钉签名 Secret', visibleWhen: { key: 'mode', value: 'webhook' } },
    { key: 'client-id', label: 'Client ID (AppKey)', sensitive: true, placeholder: '请输入钉钉应用 AppKey', visibleWhen: { key: 'mode', value: 'stream' } },
    { key: 'client-secret', label: 'Client Secret (AppSecret)', sensitive: true, placeholder: '请输入钉钉应用 AppSecret', visibleWhen: { key: 'mode', value: 'stream' } },
  ],
  feishu: [
    { key: 'mode', label: '连接模式', sensitive: false, placeholder: '', type: 'select', options: [{ label: 'Webhook', value: 'webhook' }, { label: 'Stream (长连接/免公网IP)', value: 'stream' }] },
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入飞书 Webhook URL', visibleWhen: { key: 'mode', value: 'webhook' } },
    { key: 'secret', label: '签名密钥', sensitive: true, placeholder: '请输入飞书签名 Secret', visibleWhen: { key: 'mode', value: 'webhook' } },
    { key: 'app-id', label: 'App ID', sensitive: true, placeholder: '请输入飞书应用 App ID', visibleWhen: { key: 'mode', value: 'stream' } },
    { key: 'app-secret', label: 'App Secret', sensitive: true, placeholder: '请输入飞书应用 App Secret', visibleWhen: { key: 'mode', value: 'stream' } },
  ],
  slack: [
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入 Slack Webhook URL' },
    { key: 'bot-token', label: 'Bot Token', sensitive: true, placeholder: '请输入 Slack Bot Token' },
  ],
  'wechat-work': [
    { key: 'webhook-url', label: 'Webhook 地址', sensitive: true, placeholder: '请输入企业微信 Webhook URL' },
  ],
};

const BotConfig: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [channelForms, setChannelForms] = useState<Record<string, { enabled: boolean; settings: Record<string, string> }>>({});
  const [channelContextMode, setChannelContextMode] = useState<string>('persistent');
  const [channelSessionTimeout, setChannelSessionTimeout] = useState<number>(30);
  const [channelDefaultProvider, setChannelDefaultProvider] = useState<string>('');
  const [channelDefaultModel, setChannelDefaultModel] = useState<string>('');
  const [providers, setProviders] = useState<{ key: string; label: string }[]>([]);

  const fetchConfig = useCallback(() => {
    setLoading(true);
    getAiConfig()
      .then((res) => {
        if (res.code === 200 && res.data) {
          const data = res.data;

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

          // Build provider list from API data
          const providerList: { key: string; label: string }[] = [];
          Object.entries(data.providers || {}).forEach(([key]) => {
            providerList.push({ key, label: key });
          });
          setProviders(providerList);
        }
      })
      .catch(() => message.error(t('aiConfig.fetchError')))
      .finally(() => setLoading(false));
  }, [t]);

  useEffect(() => {
    fetchConfig();
  }, [fetchConfig]);

  const handleSave = async () => {
    setSaving(true);
    try {
      // Fetch the full current config first, then overlay channel changes
      const currentRes = await getAiConfig();
      if (currentRes.code !== 200 || !currentRes.data) {
        message.error(t('aiConfig.fetchError'));
        setSaving(false);
        return;
      }
      const current = currentRes.data;

      const providerPayload: Record<string, { apiKey?: string; baseUrl?: string; model?: string; temperature?: number; topP?: number; maxTokens?: number }> = {};
      Object.entries(current.providers || {}).forEach(([key, p]) => {
        providerPayload[key] = {
          baseUrl: p.baseUrl,
          model: p.model,
          temperature: p.temperature,
          topP: p.topP,
          maxTokens: p.maxTokens,
        };
      });

      const res = await saveAiConfig({
        enabled: current.enabled,
        defaultProvider: current.defaultProvider,
        providers: providerPayload,
        quota: current.quota,
        channels: channelForms,
        channelContext: {
          contextMode: channelContextMode,
          sessionTimeout: channelSessionTimeout,
          defaultProvider: channelDefaultProvider,
          defaultModel: channelDefaultModel,
        },
        embedding: current.embedding ? {
          provider: current.embedding.provider,
          model: current.embedding.model,
          baseUrl: current.embedding.baseUrl,
        } : undefined,
        orchestrator: current.orchestrator ? {
          maxIterations: current.orchestrator.maxIterations,
          maxToolCalls: current.orchestrator.maxToolCalls,
          maxConsecutiveErrors: current.orchestrator.maxConsecutiveErrors,
          sopEnabled: current.orchestrator.sopEnabled,
          memoryEnabled: current.orchestrator.memoryEnabled,
          systemPromptOverride: current.orchestrator.systemPromptOverride,
        } : undefined,
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

  const renderChannelTab = (channel: string) => {
    const form = channelForms[channel] || { enabled: false, settings: {} };
    const meta = CHANNEL_SETTINGS_META[channel] || [];

    return (
      <div>
        <Form layout="vertical" style={{ maxWidth: 600 }}>
          <Form.Item label={t('aiConfig.channel.enabled')}>
            <Switch checked={form.enabled} onChange={(v) => updateChannelEnabled(channel, v)} />
          </Form.Item>
          {meta.map((field) => {
            // Handle visibleWhen conditional display
            if (field.visibleWhen) {
              const depValue = form.settings?.[field.visibleWhen.key] || '';
              if (depValue !== field.visibleWhen.value) return null;
            }
            return (
              <Form.Item key={field.key} label={field.label}>
                {field.type === 'select' && field.options ? (
                  <Select
                    value={form.settings?.[field.key] || (field.options[0]?.value ?? '')}
                    onChange={(v) => updateChannelSetting(channel, field.key, v)}
                    options={field.options}
                  />
                ) : field.sensitive ? (
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
            );
          })}
          <Divider dashed />
          <Form.Item label={t('aiConfig.channel.channelProvider')} extra={t('aiConfig.channel.channelProviderHint')}>
            <Select
              value={form.settings?.['provider'] || ''}
              onChange={(v) => updateChannelSetting(channel, 'provider', v)}
              allowClear
              placeholder={t('aiConfig.channel.channelProviderPlaceholder')}
              options={[
                { label: t('aiConfig.channel.useDefault'), value: '' },
                ...providers.map(({ key, label }) => ({ label, value: key })),
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

  return (
    <div style={{ padding: isMobile ? 16 : 24 }}>
      <Spin spinning={loading}>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Card
            title={
              <Space>
                <ApiOutlined style={{ fontSize: 20, color: token.colorPrimary }} />
                <Title level={4} style={{ margin: 0, color: token.colorText }}>{t('aiConfig.channel.title')}</Title>
              </Space>
            }
            extra={
              <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
                {t('common.save')}
              </Button>
            }
            style={{ borderRadius: 12 }}
          >
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
                                ...providers.map(({ key, label }) => ({ label, value: key })),
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
        </Space>
      </Spin>
    </div>
  );
};

export default BotConfig;
