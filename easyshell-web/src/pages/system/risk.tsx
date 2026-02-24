import { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Button,
  Tag,
  Input,
  Space,
  Typography,
  Spin,
  message,
  theme,
  Row,
  Col,
  Table,
  Empty,
} from 'antd';
import {
  SaveOutlined,
  PlusOutlined,
  ThunderboltOutlined,
  StopOutlined,
  WarningOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useResponsive } from '../../hooks/useResponsive';
import { getRiskRules, saveRiskRules, assessRisk } from '../../api/ai';
import { riskLevelMap } from '../../utils/status';
import type { RiskLevel, RiskAssessment } from '../../types';

const { Title, Text } = Typography;
const { TextArea } = Input;

const RiskConfig: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const { isMobile } = useResponsive();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [assessing, setAssessing] = useState(false);

  const [bannedCommands, setBannedCommands] = useState<string[]>([]);
  const [highCommands, setHighCommands] = useState<string[]>([]);
  const [lowCommands, setLowCommands] = useState<string[]>([]);

  const [newBanned, setNewBanned] = useState('');
  const [newHigh, setNewHigh] = useState('');
  const [newLow, setNewLow] = useState('');

  const [scriptContent, setScriptContent] = useState('');
  const [assessment, setAssessment] = useState<RiskAssessment | null>(null);

  const fetchRules = useCallback(() => {
    setLoading(true);
    getRiskRules()
      .then((res) => {
        if (res.code === 200 && res.data) {
          setBannedCommands(res.data.bannedCommands || []);
          setHighCommands(res.data.highCommands || []);
          setLowCommands(res.data.lowCommands || []);
        }
      })
      .catch(() => message.error(t('risk.fetchError')))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchRules();
  }, [fetchRules]);

  const handleSave = async () => {
    setSaving(true);
    try {
      const res = await saveRiskRules({
        bannedCommands,
        highCommands,
        lowCommands,
      });
      if (res.code === 200) {
        message.success(t('risk.saveSuccess'));
        fetchRules();
      } else {
        message.error(res.message || t('risk.saveError'));
      }
    } catch {
      message.error(t('risk.saveError'));
    } finally {
      setSaving(false);
    }
  };

  const handleAssess = async () => {
    if (!scriptContent.trim()) {
      message.warning(t('risk.scriptRequired'));
      return;
    }
    setAssessing(true);
    setAssessment(null);
    try {
      const res = await assessRisk({ scriptContent });
      if (res.code === 200 && res.data) {
        setAssessment(res.data);
      } else {
        message.error(res.message || t('risk.assessError'));
      }
    } catch {
      message.error(t('risk.assessError'));
    } finally {
      setAssessing(false);
    }
  };

  const addCommand = (
    list: string[],
    setList: (v: string[]) => void,
    value: string,
    setValue: (v: string) => void,
  ) => {
    const trimmed = value.trim();
    if (!trimmed) return;
    if (list.includes(trimmed)) {
      message.warning(t('risk.commandExists'));
      return;
    }
    setList([...list, trimmed]);
    setValue('');
  };

  const removeCommand = (list: string[], setList: (v: string[]) => void, cmd: string) => {
    setList(list.filter((c) => c !== cmd));
  };

  const renderCommandCard = (
    title: string,
    icon: React.ReactNode,
    color: string,
    borderColor: string,
    bgColor: string,
    commands: string[],
    setCommands: (v: string[]) => void,
    newValue: string,
    setNewValue: (v: string) => void,
    placeholder: string,
  ) => (
    <Card
      style={{ borderRadius: 12, borderTop: `3px solid ${borderColor}` }}
      styles={{ body: { padding: '16px 20px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        {icon}
        <Text strong style={{ fontSize: 15, color: token.colorText }}>{title}</Text>
        <Tag color={color}>{commands.length}</Tag>
      </div>

      <div
        style={{
          minHeight: 80,
          maxHeight: 200,
          overflowY: 'auto',
          padding: 12,
          borderRadius: 8,
          background: bgColor,
          marginBottom: 12,
        }}
      >
        {commands.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('risk.noCommands')} style={{ margin: '8px 0' }} />
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {commands.map((cmd) => (
              <Tag
                key={`custom-${cmd}`}
                color={color}
                closable
                onClose={() => removeCommand(commands, setCommands, cmd)}
                style={{ marginBottom: 4 }}
              >
                <code>{cmd}</code>
              </Tag>
            ))}
          </div>
        )}
      </div>

      <Space.Compact style={{ width: '100%' }}>
        <Input
          value={newValue}
          onChange={(e) => setNewValue(e.target.value)}
          onPressEnter={() => addCommand(commands, setCommands, newValue, setNewValue)}
          placeholder={placeholder}
          style={{ flex: 1 }}
        />
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => addCommand(commands, setCommands, newValue, setNewValue)}
        >
          {t('common.add')}
        </Button>
      </Space.Compact>
    </Card>
  );

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
          {t('risk.title')}
        </Title>
        <Button
          type="primary"
          icon={<SaveOutlined />}
          loading={saving}
          onClick={handleSave}
          size={isMobile ? 'small' : 'middle'}
        >
          {t('risk.saveRules')}
        </Button>
      </div>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          {renderCommandCard(
            t('risk.bannedCommands'),
            <StopOutlined style={{ fontSize: isMobile ? 16 : 18, color: '#eb2f96' }} />,
            'magenta',
            '#eb2f96',
            token.colorErrorBg,
            bannedCommands,
            setBannedCommands,
            newBanned,
            setNewBanned,
            t('risk.bannedPlaceholder'),
          )}
        </Col>
        <Col xs={24} md={8}>
          {renderCommandCard(
            t('risk.highCommands'),
            <WarningOutlined style={{ fontSize: isMobile ? 16 : 18, color: '#fa541c' }} />,
            'red',
            '#fa541c',
            token.colorWarningBg,
            highCommands,
            setHighCommands,
            newHigh,
            setNewHigh,
            t('risk.highPlaceholder'),
          )}
        </Col>
        <Col xs={24} md={8}>
          {renderCommandCard(
            t('risk.lowCommands'),
            <SafetyCertificateOutlined style={{ fontSize: isMobile ? 16 : 18, color: '#52c41a' }} />,
            'green',
            '#52c41a',
            token.colorSuccessBg,
            lowCommands,
            setLowCommands,
            newLow,
            setNewLow,
            t('risk.lowPlaceholder'),
          )}
        </Col>
      </Row>

      <Card style={{ borderRadius: 12 }}>
        <Title level={5} style={{ marginTop: 0, color: token.colorText }}>
          {t('risk.scriptAssess.title')}
        </Title>
        <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
          {t('risk.scriptAssess.description')}
        </Text>

        <TextArea
          value={scriptContent}
          onChange={(e) => setScriptContent(e.target.value)}
          rows={8}
          placeholder={t('risk.scriptAssess.placeholder')}
          style={{ marginBottom: 12, fontFamily: 'monospace' }}
        />

        <Button
          type="primary"
          icon={<ThunderboltOutlined />}
          loading={assessing}
          onClick={handleAssess}
          style={{ marginBottom: 16 }}
        >
          {t('risk.scriptAssess.button')}
        </Button>

        {assessment && (
          <div>
            <div
              style={{
                padding: '12px 16px',
                borderRadius: 8,
                marginBottom: 16,
                background: assessment.overallRisk === 'LOW'
                  ? token.colorSuccessBg
                  : assessment.overallRisk === 'MEDIUM'
                  ? token.colorWarningBg
                  : token.colorErrorBg,
                border: `1px solid ${
                  assessment.overallRisk === 'LOW'
                    ? token.colorSuccessBorder
                    : assessment.overallRisk === 'MEDIUM'
                    ? token.colorWarningBorder
                    : token.colorErrorBorder
                }`,
              }}
            >
              <Space>
                <Text strong style={{ color: token.colorText }}>{t('risk.scriptAssess.overallRisk')}</Text>
                <Tag color={riskLevelMap[assessment.overallRisk]?.color} style={{ fontSize: 14, padding: '2px 12px' }}>
                  {t(riskLevelMap[assessment.overallRisk]?.text)}
                </Tag>
                {assessment.autoExecutable && (
                  <Tag color="green">{t('risk.scriptAssess.autoExecutable')}</Tag>
                )}
                {!assessment.autoExecutable && (
                  <Tag color="red">{t('risk.scriptAssess.notAutoExecutable')}</Tag>
                )}
              </Space>
              {assessment.bannedMatches && assessment.bannedMatches.length > 0 && (
                <div style={{ marginTop: 8 }}>
                  <Text type="danger">
                    {t('risk.scriptAssess.bannedMatch')}{assessment.bannedMatches.join(', ')}
                  </Text>
                </div>
              )}
            </div>

            {assessment.commandRisks && assessment.commandRisks.length > 0 && (
              <Table
                dataSource={assessment.commandRisks.map((r, i) => ({ ...r, key: i }))}
                columns={[
                  {
                    title: t('risk.scriptAssess.colCommand'),
                    dataIndex: 'command',
                    key: 'command',
                    render: (text: string) => <code>{text}</code>,
                  },
                  {
                    title: t('risk.scriptAssess.colLevel'),
                    dataIndex: 'level',
                    key: 'level',
                    width: 120,
                    render: (level: RiskLevel) => (
                      <Tag color={riskLevelMap[level]?.color}>{t(riskLevelMap[level]?.text)}</Tag>
                    ),
                  },
                  {
                    title: t('risk.scriptAssess.colReason'),
                    dataIndex: 'reason',
                    key: 'reason',
                  },
                ]}
                pagination={false}
                size="small"
              />
            )}
          </div>
        )}
      </Card>
    </div>
  );
};

export default RiskConfig;
