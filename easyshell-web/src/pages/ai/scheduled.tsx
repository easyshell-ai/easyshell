import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Card,
  Button,
  Table,
  Tag,
  Switch,
  Modal,
  Form,
  Input,
  Select,
  Space,
  Typography,
  Spin,
  Popconfirm,
  message,
  theme,
  Segmented,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getScheduledTasks,
  createScheduledTask,
  updateScheduledTask,
  deleteScheduledTask,
  enableScheduledTask,
  disableScheduledTask,
  runScheduledTask,
  getTemplates,
} from '../../api/ai';
import { getHostList } from '../../api/host';
import { getClusterList } from '../../api/cluster';
import { getTagList } from '../../api/tag';
import type { AiScheduledTask, AiScheduledTaskRequest, BuiltInTemplate, Agent, ClusterVO, TagVO } from '../../types';
import { taskTypeMap } from '../../utils/status';
import { formatTime } from '../../utils/format';
import { Cron } from 'react-js-cron';
import type { CronError } from 'react-js-cron';
import 'react-js-cron/dist/styles.css';

const { Title, Text } = Typography;
const { TextArea } = Input;

// --- CronBuilder Component (react-js-cron wrapper for Spring 6-field cron) ---

interface CronBuilderProps {
  value?: string;
  onChange?: (val: string) => void;
}

/** Convert Spring 6-field cron to 5-field for react-js-cron */
const springTo5Field = (cron: string | undefined): string => {
  if (!cron) return '0 2 * * *';
  const parts = cron.trim().split(/\s+/);
  // 6-field: sec min hour dayOfMonth month dayOfWeek
  if (parts.length === 6) {
    const fiveField = parts.slice(1).join(' ');
    // Replace '?' with '*' since react-js-cron doesn't support '?'
    return fiveField.replace(/\?/g, '*');
  }
  // Already 5-field
  if (parts.length === 5) return cron.replace(/\?/g, '*');
  return '0 2 * * *';
};

/** Convert 5-field cron back to Spring 6-field (prefix with '0' for seconds) */
const toSpring6Field = (fiveField: string): string => {
  return `0 ${fiveField}`;
};

const CronBuilder: React.FC<CronBuilderProps> = ({ value, onChange }) => {
  const { token } = theme.useToken();
  const { t, i18n } = useTranslation();
  const [mode, setMode] = useState<'visual' | 'raw'>('visual');
  const [cronError, setCronError] = useState<CronError>();

  const fiveFieldValue = useMemo(() => springTo5Field(value), [value]);

  const handleCronChange = useCallback((newFiveField: string) => {
    onChange?.(toSpring6Field(newFiveField));
  }, [onChange]);

  const CRON_PRESETS_LABELS = [
    { label: t('scheduled.cron.presets.daily2am'), value: '0 0 2 * * ?' },
    { label: t('scheduled.cron.presets.hourly'), value: '0 0 * * * ?' },
    { label: t('scheduled.cron.presets.every30min'), value: '0 */30 * * * ?' },
    { label: t('scheduled.cron.presets.daily9am'), value: '0 0 9 * * ?' },
    { label: t('scheduled.cron.presets.weekly3am'), value: '0 0 3 ? * 1' },
  ];

  const cronLocale = useMemo(() => i18n.language === 'zh-CN' ? {
    everyText: '每',
    emptyMonths: '每月',
    emptyMonthDays: '每天',
    emptyMonthDaysShort: '每天',
    emptyWeekDays: '每个工作日',
    emptyWeekDaysShort: '每天',
    emptyHours: '每小时',
    emptyMinutes: '每分钟',
    emptyMinutesForHourPeriod: '每分钟',
    yearOption: '年',
    monthOption: '月',
    weekOption: '周',
    dayOption: '天',
    hourOption: '小时',
    minuteOption: '分钟',
    rebootOption: '重启时',
    prefixPeriod: '每',
    prefixMonths: '的',
    prefixMonthDays: '的',
    prefixWeekDays: '的',
    prefixWeekDaysForMonthAndYearPeriod: '且',
    prefixHours: '的',
    prefixMinutes: ':',
    prefixMinutesForHourPeriod: '的第',
    suffixMinutesForHourPeriod: '分钟',
    errorInvalidCron: '无效的 cron 表达式',
    clearButtonText: '清除',
    weekDays: ['周日', '周一', '周二', '周三', '周四', '周五', '周六'],
    months: ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月'],
    altWeekDays: ['周日', '周一', '周二', '周三', '周四', '周五', '周六'],
    altMonths: ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月'],
  } : undefined, [i18n.language]);

  if (mode === 'raw') {
    return (
      <div>
        <div style={{ marginBottom: 8 }}>
          <Segmented size="small" value={mode} onChange={(v) => setMode(v as 'visual' | 'raw')} options={[{ label: t('scheduled.cron.visual'), value: 'visual' }, { label: t('scheduled.cron.raw'), value: 'raw' }]} />
        </div>
        <Input
          value={value}
          onChange={(e) => onChange?.(e.target.value)}
          placeholder="0 0 2 * * ?"
          style={{ fontFamily: 'monospace' }}
        />
        <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 4 }}>
          {t('scheduled.cron.hint')}
        </Text>
      </div>
    );
  }

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Segmented size="small" value={mode} onChange={(v) => setMode(v as 'visual' | 'raw')} options={[{ label: t('scheduled.cron.visual'), value: 'visual' }, { label: t('scheduled.cron.raw'), value: 'raw' }]} />
        <code style={{ fontSize: 12, padding: '2px 8px', borderRadius: 4, background: token.colorFillTertiary }}>{value || '0 0 2 * * ?'}</code>
      </div>

      <div style={{ marginBottom: 8 }}>
        <Space size={4} wrap>
          {CRON_PRESETS_LABELS.map(p => (
            <Button key={p.value} size="small" type={value === p.value ? 'primary' : 'default'} onClick={() => onChange?.(p.value)}>
              {p.label}
            </Button>
          ))}
        </Space>
      </div>

      <Cron
        value={fiveFieldValue}
        setValue={handleCronChange}
        onError={setCronError}
        clearButton={false}
        allowEmpty="never"
        humanizeLabels
        locale={cronLocale}
        allowedPeriods={['year', 'month', 'week', 'day', 'hour', 'minute']}
      />
      {cronError && (
        <Text type="danger" style={{ fontSize: 11, display: 'block', marginTop: 4 }}>
          {cronError.description}
        </Text>
      )}
    </div>
  );
};

// --- Main Component ---

const AiScheduled: React.FC = () => {
  const { token } = theme.useToken();
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [tasks, setTasks] = useState<AiScheduledTask[]>([]);
  const [templates, setTemplates] = useState<BuiltInTemplate[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingTask, setEditingTask] = useState<AiScheduledTask | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [runningIds, setRunningIds] = useState<Set<number>>(new Set());
  const [form] = Form.useForm();

  const [hosts, setHosts] = useState<Agent[]>([]);
  const [clusters, setClusters] = useState<ClusterVO[]>([]);
  const [tags, setTags] = useState<TagVO[]>([]);
  const [loadingTargets, setLoadingTargets] = useState(false);

  const targetType = Form.useWatch('targetType', form);
  const notifyStrategy = Form.useWatch('notifyStrategy', form);

  const fetchTasks = useCallback(() => {
    setLoading(true);
    getScheduledTasks()
      .then((res) => {
        if (res.code === 200 && res.data) {
          setTasks(res.data);
        }
      })
      .catch(() => message.error(t('scheduled.fetchError')))
      .finally(() => setLoading(false));
  }, []);

  const fetchTemplates = useCallback(() => {
    getTemplates()
      .then((res) => {
        if (res.code === 200 && res.data) {
          setTemplates(res.data);
        }
      })
      .catch(() => {});
  }, []);

  const fetchTargets = useCallback(() => {
    setLoadingTargets(true);
    Promise.all([
      getHostList().then(r => { if (r.code === 200) setHosts(r.data || []); }),
      getClusterList().then(r => { if (r.code === 200) setClusters(r.data || []); }),
      getTagList().then(r => { if (r.code === 200) setTags(r.data || []); }),
    ]).catch(() => {}).finally(() => setLoadingTargets(false));
  }, []);

  const targetOptions = useMemo(() => {
    switch (targetType) {
      case 'agent':
        return hosts.map(h => ({ label: `${h.hostname} (${h.ip})`, value: h.id }));
      case 'cluster':
        return clusters.map(c => ({ label: c.name, value: String(c.id) }));
      case 'tag':
        return tags.map(t => ({ label: t.name, value: String(t.id) }));
      default:
        return [];
    }
  }, [targetType, hosts, clusters, tags]);

  useEffect(() => {
    fetchTasks();
    fetchTemplates();
    fetchTargets();
  }, [fetchTasks, fetchTemplates, fetchTargets]);

  const handleCreate = () => {
    setEditingTask(null);
    form.resetFields();
    form.setFieldsValue({ taskType: 'inspect', targetType: 'agent', enabled: true, cronExpression: '0 0 2 * * ?', notifyStrategy: 'none' });
    setModalVisible(true);
  };

  const handleEdit = (task: AiScheduledTask) => {
    setEditingTask(task);
    form.setFieldsValue({
      name: task.name,
      description: task.description,
      taskType: task.taskType,
      cronExpression: task.cronExpression,
      targetType: task.targetType,
      targetIds: task.targetIds ? task.targetIds.split(',').map(s => s.trim()).filter(Boolean) : [],
      scriptTemplate: task.scriptTemplate,
      aiPrompt: task.aiPrompt,
      enabled: task.enabled,
      notifyStrategy: task.notifyStrategy || 'none',
      notifyChannels: task.notifyChannels ? task.notifyChannels.split(',').map(s => s.trim()).filter(Boolean) : [],
      notifyAiPrompt: task.notifyAiPrompt || '',
    });
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const data: AiScheduledTaskRequest = {
        name: values.name,
        description: values.description,
        taskType: values.taskType,
        cronExpression: values.cronExpression,
        targetType: values.targetType,
        targetIds: Array.isArray(values.targetIds) ? values.targetIds.join(',') : (values.targetIds || ''),
        scriptTemplate: values.scriptTemplate,
        aiPrompt: values.aiPrompt,
        enabled: values.enabled,
        notifyStrategy: values.notifyStrategy,
        notifyChannels: Array.isArray(values.notifyChannels) ? values.notifyChannels.join(',') : (values.notifyChannels || ''),
        notifyAiPrompt: values.notifyAiPrompt || '',
      };

      let res;
      if (editingTask) {
        res = await updateScheduledTask(editingTask.id, data);
      } else {
        res = await createScheduledTask(data);
      }

      if (res.code === 200) {
        message.success(editingTask ? t('scheduled.updateSuccess') : t('scheduled.createSuccess'));
        setModalVisible(false);
        fetchTasks();
      } else {
        message.error(res.message || t('scheduled.deleteError'));
      }
    } catch {
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      const res = await deleteScheduledTask(id);
      if (res.code === 200) {
        message.success(t('scheduled.deleteSuccess'));
        fetchTasks();
      } else {
        message.error(res.message || t('scheduled.deleteError'));
      }
    } catch {
      message.error(t('scheduled.deleteError'));
    }
  };

  const handleToggleEnabled = async (task: AiScheduledTask, checked: boolean) => {
    try {
      const res = checked ? await enableScheduledTask(task.id) : await disableScheduledTask(task.id);
      if (res.code === 200) {
        message.success(checked ? t('scheduled.enableSuccess') : t('scheduled.disableSuccess'));
        fetchTasks();
      } else {
        message.error(res.message || t('scheduled.toggleError'));
      }
    } catch {
      message.error(t('scheduled.toggleError'));
    }
  };

  const handleRun = async (id: number) => {
    setRunningIds(prev => new Set(prev).add(id));
    try {
      const res = await runScheduledTask(id);
      if (res.code === 200) {
        message.success(t('scheduled.runSuccess'));
        fetchTasks();
      } else {
        message.error(res.message || t('scheduled.runError'));
      }
    } catch {
      message.error(t('scheduled.runError'));
    } finally {
      setRunningIds(prev => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  };

  const handleTaskTypeChange = (taskType: string) => {
    const tpl = templates.find((t) => t.type === taskType);
    if (tpl) {
      form.setFieldsValue({
        scriptTemplate: tpl.script,
        aiPrompt: tpl.aiPrompt,
        description: tpl.description,
      });
    } else {
      form.setFieldsValue({ scriptTemplate: '', aiPrompt: '' });
    }
  };

  const columns = [
    {
      title: t('scheduled.col.name'),
      dataIndex: 'name',
      key: 'name',
      width: 180,
      render: (text: string) => <span style={{ fontWeight: 500 }}>{text}</span>,
    },
    {
      title: t('scheduled.col.taskType'),
      dataIndex: 'taskType',
      key: 'taskType',
      width: 120,
      render: (type: string) => {
        const info = taskTypeMap[type] || { color: 'default', label: type };
        return <Tag color={info.color}>{t(info.label)}</Tag>;
      },
    },
    {
      title: t('scheduled.col.cronExpression'),
      dataIndex: 'cronExpression',
      key: 'cronExpression',
      width: 160,
      render: (text: string) => (
        <code style={{ padding: '2px 6px', borderRadius: 4, background: token.colorFillTertiary, fontSize: 12 }}>
          {text}
        </code>
      ),
    },
    {
      title: t('scheduled.col.targetType'),
      dataIndex: 'targetType',
      key: 'targetType',
      width: 80,
      render: (type: string) => t(`scheduled.targetType.${type}`, { defaultValue: type }),
    },
    {
      title: t('scheduled.col.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled: boolean, record: AiScheduledTask) => (
        <Switch
          checked={enabled}
          size="small"
          onChange={(checked) => handleToggleEnabled(record, checked)}
        />
      ),
    },
    {
      title: t('scheduled.col.lastRun'),
      dataIndex: 'lastRunAt',
      key: 'lastRunAt',
      width: 170,
      render: (time: string | null) => (
        <span style={{ color: token.colorTextSecondary, fontSize: 13 }}>{formatTime(time)}</span>
      ),
    },
    {
      title: t('scheduled.col.actions'),
      key: 'action',
      width: 220,
      render: (_: unknown, record: AiScheduledTask) => (
        <Space size={4}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          <Popconfirm title={t('scheduled.confirmRun')} onConfirm={() => handleRun(record.id)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
            <Button
              type="link"
              size="small"
              icon={<PlayCircleOutlined />}
              loading={runningIds.has(record.id)}
            >
              {t('common.confirm')}
            </Button>
          </Popconfirm>
          <Popconfirm title={t('scheduled.confirmDelete')} onConfirm={() => handleDelete(record.id)} okText={t('common.delete')} cancelText={t('common.cancel')}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  if (loading && tasks.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} style={{ margin: 0, color: token.colorText }}>
          {t('scheduled.title')}
        </Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchTasks}>
            {t('common.refresh')}
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            {t('scheduled.createTask')}
          </Button>
        </Space>
      </div>

      <Card style={{ borderRadius: 12 }} styles={{ body: { padding: 0 } }}>
        <Table
          dataSource={tasks}
          columns={columns}
          rowKey="id"
          pagination={false}
          locale={{ emptyText: t('scheduled.noTasks') }}
        />
      </Card>

      <Modal
        title={editingTask ? t('scheduled.editTask') : t('scheduled.createTask')}
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okText={editingTask ? t('common.save') : t('common.create')}
        cancelText={t('common.cancel')}
        width={680}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="name" label={t('scheduled.field.name')} rules={[{ required: true, message: t('scheduled.field.nameRequired') }]}>
            <Input placeholder={t('scheduled.field.namePlaceholder')} />
          </Form.Item>

          <Form.Item name="description" label={t('scheduled.field.description')}>
            <TextArea rows={2} placeholder={t('scheduled.field.descriptionPlaceholder')} />
          </Form.Item>

          <Form.Item name="taskType" label={t('scheduled.field.taskType')} rules={[{ required: true, message: t('scheduled.field.taskTypeRequired') }]}>
            <Select onChange={handleTaskTypeChange}>
              <Select.Option value="inspect">{t('scheduled.taskType.inspect')}</Select.Option>
              <Select.Option value="detect">{t('scheduled.taskType.detect')}</Select.Option>
              <Select.Option value="security">{t('scheduled.taskType.security')}</Select.Option>
              <Select.Option value="disk">{t('scheduled.taskType.disk')}</Select.Option>
              <Select.Option value="docker_health">{t('scheduled.taskType.docker_health')}</Select.Option>
              <Select.Option value="custom">{t('scheduled.taskType.custom')}</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item name="cronExpression" label={t('scheduled.field.cronExpression')} rules={[{ required: true, message: t('scheduled.field.cronRequired') }]}>
            <CronBuilder />
          </Form.Item>

          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="targetType" label={t('scheduled.field.targetType')} rules={[{ required: true, message: t('scheduled.field.targetTypeRequired') }]} style={{ flex: 1 }}>
              <Select onChange={() => form.setFieldsValue({ targetIds: [] })}>
                <Select.Option value="agent">{t('scheduled.targetType.agent')}</Select.Option>
                <Select.Option value="cluster">{t('scheduled.targetType.cluster')}</Select.Option>
                <Select.Option value="tag">{t('scheduled.targetType.tag')}</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item name="targetIds" label={t('scheduled.field.targetIds')} rules={[{ required: true, message: t('scheduled.field.targetIdsRequired') }]} style={{ flex: 2 }}>
              <Select
                mode="multiple"
                placeholder={t('scheduled.field.targetIdsPlaceholder')}
                loading={loadingTargets}
                options={targetOptions}
                showSearch
                filterOption={(input, option) =>
                  (option?.label as string)?.toLowerCase().includes(input.toLowerCase()) ?? false
                }
              />
            </Form.Item>
          </div>

          <Form.Item name="scriptTemplate" label={t('scheduled.field.scriptTemplate')}>
            <TextArea rows={8} placeholder={t('scheduled.field.scriptPlaceholder')} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>

          <Form.Item name="aiPrompt" label={t('scheduled.field.aiPrompt')}>
            <TextArea rows={3} placeholder={t('scheduled.field.aiPromptPlaceholder')} />
          </Form.Item>

          <Form.Item name="enabled" label={t('scheduled.field.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item name="notifyStrategy" label={t('scheduled.field.notifyStrategy')}>
            <Select placeholder={t('scheduled.field.notifyStrategyPlaceholder')}>
              <Select.Option value="none">{t('scheduled.field.notifyStrategyNone')}</Select.Option>
              <Select.Option value="always">{t('scheduled.field.notifyStrategyAlways')}</Select.Option>
              <Select.Option value="on_alert">{t('scheduled.field.notifyStrategyOnAlert')}</Select.Option>
              <Select.Option value="on_failure">{t('scheduled.field.notifyStrategyOnFailure')}</Select.Option>
              <Select.Option value="ai_decide">{t('scheduled.field.notifyStrategyAiDecide')}</Select.Option>
            </Select>
          </Form.Item>

          {notifyStrategy === 'ai_decide' && (
            <Form.Item name="notifyAiPrompt" label={t('scheduled.field.notifyAiPrompt')}>
              <TextArea rows={3} placeholder={t('scheduled.field.notifyAiPromptPlaceholder')} />
            </Form.Item>
          )}

          <Form.Item name="notifyChannels" label={t('scheduled.field.notifyChannels')}>
            <Select mode="multiple" placeholder={t('scheduled.field.notifyChannelsPlaceholder')}>
              <Select.Option value="telegram">Telegram</Select.Option>
              <Select.Option value="discord">Discord</Select.Option>
              <Select.Option value="dingtalk">DingTalk</Select.Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AiScheduled;
