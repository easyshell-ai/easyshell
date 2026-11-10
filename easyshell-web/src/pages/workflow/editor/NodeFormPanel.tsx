import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Col,
  Divider,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { theme } from 'antd';
import type {
  AiAnalyzeConfig,
  ApprovalConfig,
  ApprovalMode,
  ConditionConfig,
  EndOutput,
  NotifyConfig,
  ScriptExecConfig,
  TargetsConfig,
  TransformMapping,
  VariableSpec,
  WaitConfig,
  WorkflowDefinition,
  WorkflowNode,
  WorkflowNodeType,
} from '../types';
import { nodeHeight, NODE_WIDTH, sourceHandles } from '../graph';
import { availableVariablesForNode } from '../variables';
import type { WorkflowVariableGroup } from '../variables';
import { ChannelsEditor, ParametersEditor, TargetsEditor, type TargetOptions } from './NodeConfigFields';
import { EMPTY_TARGETS, NOTIFY_CHANNELS, TARGETS_MODES, type TargetsMode, type TargetsValue } from './nodeConfig';
import { getHostList } from '../../../api/host';
import { getClusterList } from '../../../api/cluster';
import { getTagList } from '../../../api/tag';
import { VariableInput } from './inspector/VariablePicker';
import { AiAnalyzeFields } from './inspector/AiAnalyzeFields';
import { StartVariablesEditor } from './inspector/StartVariablesEditor';
import {
  invalidVariableNames,
  rowsToVariables,
  variablesToRows,
  type StartVariableRow,
} from './inspector/startVariablesModel';
import { EndOutputsEditor } from './inspector/EndOutputsEditor';
import { TransformMappingsEditor } from './inspector/TransformMappingsEditor';

/**
 * 配置 Tab = 被选中节点的 config 编辑器（设计 §3.3）。
 *
 * 字段名契约：写进 {@link WorkflowNode.config} 的键与服务端执行器读取的键逐字一致——
 * `script`/`targets`/`parameters`/`rawParams`、`channels`/`messageTemplate`、
 * `title`/`approverRole`/`timeoutSec`/`onTimeout`、`expression`、`durationSec`、
 * `mappings`、`instructions`/`input`/`maxInputChars`、`outputs`。
 * 错一个字母 = 发布期 NODE_CONFIG_INVALID，所以这里没有前端别名层。
 *
 * Start 的入参不在 config：写 definition.variables，经 onSaveMeta（editor.updateMeta）。
 */

const { Text } = Typography;

const EMPTY_DEFINITION: WorkflowDefinition = {
  schemaVersion: 1,
  defaultOnUpstreamFailure: 'skip',
  onRejection: 'abort',
  variables: {},
  nodes: [],
  edges: [],
};

export interface NodeFormPanelProps {
  node: WorkflowNode | null;
  open?: boolean;
  definition?: WorkflowDefinition;
  onClose?: () => void;
  onSave: (key: string, patch: Partial<WorkflowNode>) => void;
  /** Start 节点专用：写 definition.variables */
  onSaveMeta?: (variables: Record<string, VariableSpec>, label?: string) => void;
  onRequestDelete: (key: string) => void;
  /** 脚本寻址使用 getHostList / getClusterList / getTagList 的真实下拉 */
  remoteOptions?: boolean;
  /** 检查器把保存/删除放到外层固定 footer 时设为 false */
  showFooter?: boolean;
  /** 由父组件持有提交函数（footer 在 Tabs 外层固定显示） */
  submitRef?: React.MutableRefObject<(() => Promise<void>) | null>;
}

/** antd Form 托管的标量字段（结构字段 targets/parameters/channels 走受控 state） */
interface FormValues {
  name: string;
  script?: string;
  scriptId?: string;
  expression?: string;
  messageTemplate?: string;
  title?: string;
  detailTemplate?: string;
  mode?: ApprovalMode;
  approverRole?: 'ADMIN' | 'SUPER_ADMIN';
  onTimeout?: 'approve' | 'reject' | 'fail';
  timeoutSec?: number | null;
  durationSec?: number;
  level?: NotifyConfig['level'];
  outputVar?: string;
  retryMax?: number;
  retryIntervalSec?: number;
  timeoutSecNode?: number | null;
  onFailure?: WorkflowNode['onFailure'];
  onUnsafeValue?: 'reject' | 'raw' | 'truncate';
  riskPolicy?: 'block' | 'allow_medium' | 'strict';
  aiInstructions?: string;
  aiInput?: string;
  aiMaxInputChars?: number;
}

/* ── config 读取助手（宽松：老草稿/手写 JSON 可能缺字段或类型不符） ── */

function str(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function num(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function strList(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}

function numList(value: unknown): number[] {
  return Array.isArray(value)
    ? value.filter((item): item is number => typeof item === 'number' && Number.isFinite(item))
    : [];
}

function strMap(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const result: Record<string, string> = {};
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    result[key] = item == null ? '' : String(item);
  }
  return result;
}

function mappingRows(value: unknown): TransformMapping[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => {
    const row = (item || {}) as Record<string, unknown>;
    return { name: str(row.name), value: str(row.value) };
  });
}

/** 从 config.targets 还原表单态；mode 缺省按 explicit（服务端把缺 mode 当发布 ERROR） */
function targetsOf(config: Record<string, unknown>): TargetsValue {
  const raw: Record<string, unknown> =
    config.targets && typeof config.targets === 'object' && !Array.isArray(config.targets)
      ? (config.targets as Record<string, unknown>)
      : {};
  const mode = TARGETS_MODES.includes(raw.mode as TargetsMode)
    ? (raw.mode as TargetsMode)
    : 'explicit';
  return {
    mode,
    agentIds: strList(raw.agentIds),
    clusterIds: numList(raw.clusterIds),
    tagIds: numList(raw.tagIds),
    variableRef: str(raw.variableRef),
  };
}

/** 表单态 targets → config.targets（只写当前 mode 的那一个键，不留上一 mode 的残留） */
function targetsConfigOf(value: TargetsValue): TargetsConfig {
  switch (value.mode) {
    case 'explicit':
      return { mode: 'explicit', agentIds: value.agentIds };
    case 'cluster':
      return { mode: 'cluster', clusterIds: value.clusterIds };
    case 'tag':
      return { mode: 'tag', tagIds: value.tagIds };
    default:
      return { mode: 'variable', variableRef: value.variableRef.trim() };
  }
}

/** 滤掉空键（"刚点添加还没取名"的中间态不该进 definition） */
function compactParameters(parameters: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(parameters)) {
    if (key.trim()) result[key.trim()] = value;
  }
  return result;
}

function configOutputs(value: unknown): EndOutput[] {
  return mappingRows(value);
}

const FIXED_TYPES: WorkflowNodeType[] = ['start', 'end'];

const NodeFormPanel: React.FC<NodeFormPanelProps> = ({
  node,
  open = true,
  definition = EMPTY_DEFINITION,
  onSave,
  onSaveMeta,
  onRequestDelete,
  remoteOptions = false,
  showFooter = true,
  submitRef,
}) => {
  const { t } = useTranslation();
  const { token } = theme.useToken();
  const [form] = Form.useForm<FormValues>();
  const nodeType: WorkflowNodeType | undefined = node?.type;

  // 结构字段（对象/数组）由面板自持：antd Form 托管不了 targets 的按 mode 动态显隐，
  // 而 parameters/channels 的增删行也不适合走 Form store
  const [targets, setTargets] = useState<TargetsValue>(EMPTY_TARGETS);
  const [parameters, setParameters] = useState<Record<string, string>>({});
  const [rawParams, setRawParams] = useState<string[]>([]);
  const [channels, setChannels] = useState<string[]>([...NOTIFY_CHANNELS.slice(0, 1)]);
  const [startRows, setStartRows] = useState<StartVariableRow[]>([]);
  const [endRows, setEndRows] = useState<EndOutput[]>([]);
  const [transformRows, setTransformRows] = useState<TransformMapping[]>([]);
  const [targetOptions, setTargetOptions] = useState<TargetOptions | undefined>(undefined);

  const groups = useMemo<WorkflowVariableGroup[]>(
    () => (node ? availableVariablesForNode(definition, node.key) : []),
    [definition, node]
  );

  const initialValues = useMemo<FormValues | undefined>(() => {
    if (!node) return undefined;
    const config = node.config || {};
    return {
      name: node.name,
      script: str(config.script),
      scriptId: str(config.scriptId),
      expression: str(config.expression),
      messageTemplate: str(config.messageTemplate),
      title: str(config.title),
      detailTemplate: str(config.detailTemplate),
      mode: config.mode === 'human-confirm' ? 'human-confirm' : 'approval',
      approverRole: config.approverRole === 'SUPER_ADMIN' ? 'SUPER_ADMIN' : 'ADMIN',
      onTimeout: (['approve', 'reject', 'fail'] as const).includes(config.onTimeout as never)
        ? (config.onTimeout as FormValues['onTimeout'])
        : 'reject',
      timeoutSec: typeof config.timeoutSec === 'number' ? config.timeoutSec : 86400,
      durationSec: num(config.durationSec, 60),
      level: (['info', 'warning', 'error'] as const).includes(config.level as never)
        ? (config.level as NotifyConfig['level'])
        : 'info',
      outputVar: node.outputVar || '',
      retryMax: node.retryMax ?? 0,
      retryIntervalSec: node.retryIntervalSec ?? 5,
      timeoutSecNode: node.timeoutSec ?? null,
      onFailure: node.onFailure || 'abort',
      onUnsafeValue:
        config.onUnsafeValue === 'raw' || config.onUnsafeValue === 'truncate'
          ? config.onUnsafeValue
          : 'reject',
      riskPolicy:
        config.riskPolicy === 'allow_medium' || config.riskPolicy === 'strict'
          ? config.riskPolicy
          : 'block',
      aiInstructions: str(config.instructions),
      aiInput: str(config.input),
      aiMaxInputChars: num(config.maxInputChars, 8000),
    };
  }, [node]);

  // 换节点/重开面板时把结构字段同步成该节点的 config（否则会带着上一个节点的值）
  useEffect(() => {
    if (!open || !node || !initialValues) return;
    const config = node.config || {};
    setTargets(targetsOf(config));
    setParameters(strMap(config.parameters));
    setRawParams(strList(config.rawParams));
    setChannels(strList(config.channels).length > 0 ? strList(config.channels) : ['telegram']);
    setStartRows(variablesToRows(definition.variables));
    setEndRows(configOutputs(config.outputs));
    setTransformRows(mappingRows(config.mappings));
    form.setFieldsValue(initialValues);
  }, [open, node, initialValues, form, definition.variables]);

  // 真实主机/集群/标签下拉：三个 API 独立失败，失败的那个字段退回自由输入
  useEffect(() => {
    if (!remoteOptions || !open || nodeType !== 'script-exec') return;
    let cancelled = false;
    Promise.allSettled([getHostList(), getClusterList(), getTagList()]).then(([hostRes, clusterRes, tagRes]) => {
      if (cancelled) return;
      const hostOk = hostRes.status === 'fulfilled' && hostRes.value.code === 200 && Array.isArray(hostRes.value.data);
      const clusterOk = clusterRes.status === 'fulfilled' && clusterRes.value.code === 200 && Array.isArray(clusterRes.value.data);
      const tagOk = tagRes.status === 'fulfilled' && tagRes.value.code === 200 && Array.isArray(tagRes.value.data);
      const hosts = hostOk
        ? (hostRes.value.data || []).map((host) => ({ value: host.id, label: `${host.hostname} · ${host.ip}` }))
        : [];
      const clusters = clusterOk
        ? (clusterRes.value.data || []).map((cluster) => ({ value: String(cluster.id), label: cluster.name }))
        : [];
      const tags = tagOk
        ? (tagRes.value.data || []).map((tag) => ({ value: String(tag.id), label: tag.name }))
        : [];
      setTargetOptions({
        hosts,
        clusters,
        tags,
        fallbackHosts: !hostOk || hosts.length === 0,
        fallbackClusters: !clusterOk || clusters.length === 0,
        fallbackTags: !tagOk || tags.length === 0,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [remoteOptions, open, nodeType]);

  const handleSubmit = async () => {
    if (!node) return;
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验失败：antd 已在字段上标红，不再静默提交
    }

    if (nodeType === 'start') {
      if (invalidVariableNames(startRows).length > 0) {
        message.error(t('workflow.start.invalidName'));
        return;
      }
      onSaveMeta?.(rowsToVariables(startRows), 'start-variables');
      onSave(node.key, {
        name: values.name,
        config: {},
        outputVar: values.outputVar || undefined,
        retryMax: values.retryMax ?? 0,
        retryIntervalSec: values.retryIntervalSec ?? 5,
        timeoutSec: values.timeoutSecNode ?? null,
        onFailure: values.onFailure,
      });
      message.success(t('workflow.inspector.saved'));
      return;
    }

    const config: Record<string, unknown> = { ...(node.config || {}) };

    if (nodeType === 'script-exec') {
      const scriptExec: ScriptExecConfig = {
        script: values.script || '',
        targets: targetsConfigOf(targets),
        parameters: compactParameters(parameters),
        rawParams,
        onUnsafeValue: values.onUnsafeValue || 'reject',
        riskPolicy: values.riskPolicy || 'block',
      };
      if (values.scriptId && values.scriptId.trim()) scriptExec.scriptId = values.scriptId.trim();
      Object.assign(config, scriptExec);
    }
    if (nodeType === 'condition') {
      const condition: ConditionConfig = { expression: values.expression || '' };
      Object.assign(config, condition);
    }
    if (nodeType === 'notify') {
      const notify: NotifyConfig = {
        channels,
        messageTemplate: values.messageTemplate || '',
        parameters: compactParameters(parameters),
        level: values.level || 'info',
      };
      Object.assign(config, notify);
    }
    if (nodeType === 'approval') {
      const approval: ApprovalConfig = {
        mode: values.mode || 'approval',
        title: values.title || '',
        approverRole: values.approverRole || 'ADMIN',
        timeoutSec: values.timeoutSec ?? 86400,
        onTimeout: values.onTimeout || 'reject',
        parameters: compactParameters(parameters),
      };
      if (values.detailTemplate && values.detailTemplate.trim()) {
        approval.detailTemplate = values.detailTemplate;
      }
      Object.assign(config, approval);
    }
    if (nodeType === 'wait') {
      const wait: WaitConfig = { durationSec: values.durationSec ?? 60 };
      Object.assign(config, wait);
    }
    if (nodeType === 'transform') {
      Object.assign(config, { mappings: transformRows.filter((row) => row.name.trim()) });
    }
    if (nodeType === 'end') {
      Object.assign(config, { outputs: endRows.filter((row) => row.name.trim()) });
    }
    if (nodeType === 'ai-analyze') {
      const ai: AiAnalyzeConfig = {
        instructions: values.aiInstructions || '',
        input: values.aiInput || '',
        maxInputChars: values.aiMaxInputChars ?? 8000,
      };
      Object.assign(config, ai);
    }

    onSave(node.key, {
      name: values.name,
      config,
      outputVar: values.outputVar || undefined,
      retryMax: values.retryMax ?? 0,
      retryIntervalSec: values.retryIntervalSec ?? 5,
      timeoutSec: values.timeoutSecNode ?? null,
      onFailure: values.onFailure,
    });
    message.success(t('workflow.inspector.saved'));
  };

  // 固定 footer 由 NodeInspector 渲染时，把提交函数交给父组件（ref 只在 effect 里写）
  useEffect(() => {
    if (submitRef) submitRef.current = handleSubmit;
  });

  /** raw 参数必须配 riskPolicy >= allow_medium（服务端 ERROR，这里提前告知而非代改） */
  const rawNeedsMedium =
    nodeType === 'script-exec' && rawParams.length > 0 && form.getFieldValue('riskPolicy') === 'block';
  const targetIdsEmpty =
    nodeType === 'script-exec' &&
    ((targets.mode === 'explicit' && targets.agentIds.length === 0) ||
      (targets.mode === 'cluster' && targets.clusterIds.length === 0) ||
      (targets.mode === 'tag' && targets.tagIds.length === 0) ||
      (targets.mode === 'variable' && !targets.variableRef.trim()));
  const fixed = !!nodeType && FIXED_TYPES.includes(nodeType);

  if (!open || !node || !nodeType) {
    return <Text type="secondary">{t('workflow.form.empty')}</Text>;
  }

  return (
    <Form form={form} layout="vertical" initialValues={initialValues} requiredMark="optional" className="wf-node-form">
      <Form.Item name="name" label={t('workflow.form.name')} rules={[{ required: true, message: t('workflow.form.nameRequired') }]}>
        <Input />
      </Form.Item>

      {nodeType === 'start' && (
        <StartVariablesEditor value={startRows} onChange={setStartRows} />
      )}

      {nodeType === 'end' && <EndOutputsEditor value={endRows} onChange={setEndRows} groups={groups} />}

      {nodeType === 'transform' && (
        <TransformMappingsEditor value={transformRows} onChange={setTransformRows} groups={groups} />
      )}

      {nodeType === 'ai-analyze' && <AiAnalyzeFields groups={groups} />}

      {nodeType === 'script-exec' && (
        <>
          <Form.Item
            name="script"
            label={t('workflow.form.script')}
            rules={[{ required: true, message: t('workflow.form.scriptRequired') }]}
            tooltip={t('workflow.form.scriptHint')}
          >
            <Input.TextArea rows={8} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>
          <Form.Item name="scriptId" label={t('workflow.form.scriptId')} tooltip={t('workflow.form.scriptIdHint')}>
            <Input placeholder={t('workflow.form.scriptIdPlaceholder')} />
          </Form.Item>
          <Form.Item
            label={t('workflow.form.targets')}
            required
            tooltip={t('workflow.form.targetsHint')}
            extra={targetIdsEmpty ? t('workflow.form.targetsEmpty') : undefined}
          >
            <TargetsEditor value={targets} onChange={setTargets} options={targetOptions} groups={groups} />
          </Form.Item>
          <Form.Item label={t('workflow.form.parameters')} tooltip={t('workflow.form.parametersHint')}>
            <ParametersEditor value={parameters} onChange={setParameters} ariaLabel={t('workflow.form.parameters')} groups={groups} />
          </Form.Item>
          <Form.Item label={t('workflow.form.rawParams')} tooltip={t('workflow.form.rawParamsHint')}>
            <Select
              mode="multiple"
              value={rawParams}
              onChange={(next: string[]) => setRawParams(next)}
              options={Object.keys(parameters).map((key) => ({ value: key, label: key }))}
              placeholder={t('workflow.form.rawParamsPlaceholder')}
              style={{ width: '100%' }}
              aria-label={t('workflow.form.rawParams')}
            />
          </Form.Item>
          <Alert type="warning" showIcon message={t('workflow.form.riskHint')} style={{ marginBottom: 12 }} />
          {rawNeedsMedium && (
            <Alert
              type="error"
              showIcon
              message={t('workflow.form.rawNeedsMedium')}
              style={{ marginBottom: 12 }}
            />
          )}
        </>
      )}

      {nodeType === 'condition' && (
        <>
          <Form.Item
            name="expression"
            label={t('workflow.form.expression')}
            rules={[{ required: true, message: t('workflow.form.expressionRequired') }]}
            tooltip={t('workflow.form.expressionHint')}
            extra={t('workflow.form.expressionSyntax')}
          >
            <VariableInput
              groups={groups}
              mode="expression"
              ariaLabel={t('workflow.form.expression')}
              placeholder="nodes.n1.output.count > 0"
              monospace
            />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            message={t('workflow.form.conditionBranchHint')}
            description={t('workflow.form.conditionBranchDesc')}
            style={{ marginBottom: 12 }}
          />
        </>
      )}

      {nodeType === 'notify' && (
        <>
          <Form.Item
            label={t('workflow.form.channels')}
            required
            tooltip={t('workflow.form.channelsHint')}
            extra={channels.length === 0 ? t('workflow.form.channelsRequired') : undefined}
          >
            <ChannelsEditor value={channels} onChange={setChannels} />
          </Form.Item>
          <Form.Item
            name="messageTemplate"
            label={t('workflow.form.messageTemplate')}
            rules={[{ required: true, message: t('workflow.form.messageTemplateRequired') }]}
            tooltip={t('workflow.form.messageTemplateHint')}
          >
            <VariableInput
              groups={groups}
              mode="template"
              ariaLabel={t('workflow.form.messageTemplate')}
              multiline
              rows={5}
              monospace
            />
          </Form.Item>
          <Form.Item label={t('workflow.form.parameters')} tooltip={t('workflow.form.parametersHint')}>
            <ParametersEditor value={parameters} onChange={setParameters} ariaLabel={t('workflow.form.parameters')} groups={groups} />
          </Form.Item>
          <Form.Item name="level" label={t('workflow.form.level')} tooltip={t('workflow.form.levelHint')}>
            <Select
              options={[
                { value: 'info', label: 'info' },
                { value: 'warning', label: 'warning' },
                { value: 'error', label: 'error' },
              ]}
            />
          </Form.Item>
        </>
      )}

      {nodeType === 'approval' && (
        <>
          <Form.Item name="mode" label={t('workflow.form.approvalMode')} tooltip={t('workflow.form.approvalModeHint')}>
            <Select
              options={[
                { value: 'approval', label: t('workflow.form.approvalModeApproval') },
                { value: 'human-confirm', label: t('workflow.form.approvalModeHumanConfirm') },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="title"
            label={t('workflow.form.approvalTitle')}
            rules={[{ required: true, message: t('workflow.form.approvalTitleRequired') }]}
            tooltip={t('workflow.form.approvalTitleHint')}
          >
            <Input />
          </Form.Item>
          <Form.Item name="detailTemplate" label={t('workflow.form.detailTemplate')} tooltip={t('workflow.form.detailTemplateHint')}>
            <VariableInput
              groups={groups}
              mode="template"
              ariaLabel={t('workflow.form.detailTemplate')}
              multiline
              rows={3}
              monospace
            />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="approverRole" label={t('workflow.form.approverRole')} tooltip={t('workflow.form.approverRoleHint')}>
                <Select
                  options={[
                    { value: 'ADMIN', label: 'ADMIN' },
                    { value: 'SUPER_ADMIN', label: 'SUPER_ADMIN' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="timeoutSec"
                label={t('workflow.form.approvalTimeout')}
                tooltip={t('workflow.form.approvalTimeoutHint')}
                rules={[{ required: true, message: t('workflow.form.approvalTimeoutRequired') }]}
              >
                <InputNumber min={60} max={604800} style={{ width: '100%' }} addonAfter={t('common.seconds')} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="onTimeout" label={t('workflow.form.onTimeout')} tooltip={t('workflow.form.onTimeoutHint')}>
            <Select
              options={[
                { value: 'reject', label: 'reject' },
                { value: 'approve', label: 'approve' },
                { value: 'fail', label: 'fail' },
              ]}
            />
          </Form.Item>
          <Form.Item label={t('workflow.form.parameters')} tooltip={t('workflow.form.parametersHint')}>
            <ParametersEditor value={parameters} onChange={setParameters} ariaLabel={t('workflow.form.parameters')} groups={groups} />
          </Form.Item>
        </>
      )}

      {nodeType === 'wait' && (
        <Form.Item
          name="durationSec"
          label={t('workflow.form.durationSec')}
          rules={[{ required: true, message: t('workflow.form.durationSecRequired') }]}
          tooltip={t('workflow.form.durationSecHint')}
        >
          <InputNumber min={1} max={3600} style={{ width: '100%' }} addonAfter={t('common.seconds')} />
        </Form.Item>
      )}

      {nodeType !== 'start' && nodeType !== 'end' && (
        <>
          <Divider style={{ marginTop: 0 }}>{t('workflow.form.advanced')}</Divider>

          <Form.Item name="outputVar" label={t('workflow.form.outputVar')} tooltip={t('workflow.form.outputVarHint')}>
            <Input placeholder="result" style={{ fontFamily: 'monospace' }} />
          </Form.Item>

      <Row gutter={12}>
        <Col span={12}>
          <Form.Item name="retryMax" label={t('workflow.form.retryMax')}>
            <InputNumber min={0} max={10} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="retryIntervalSec" label={t('workflow.form.retryInterval')}>
            <InputNumber min={1} max={3600} style={{ width: '100%' }} addonAfter="s" />
          </Form.Item>
        </Col>
      </Row>

      <Row gutter={12}>
        <Col span={12}>
          <Form.Item name="timeoutSecNode" label={t('workflow.form.timeout')} tooltip={t('workflow.form.timeoutHint')}>
            <InputNumber min={1} max={86400} style={{ width: '100%' }} addonAfter="s" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="onFailure" label={t('workflow.form.onFailure')}>
            <Select
              options={[
                { value: 'abort', label: 'abort' },
                { value: 'skip', label: 'skip' },
                { value: 'partial', label: 'partial' },
              ]}
            />
          </Form.Item>
        </Col>
      </Row>

      {nodeType === 'script-exec' && (
        <Row gutter={12}>
          <Col span={12}>
            <Form.Item name="onUnsafeValue" label={t('workflow.form.onUnsafeValue')} tooltip={t('workflow.form.onUnsafeValueHint')}>
              <Select
                options={[
                  { value: 'reject', label: 'reject' },
                  { value: 'raw', label: 'raw' },
                  { value: 'truncate', label: 'truncate' },
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="riskPolicy" label={t('workflow.form.riskPolicy')} tooltip={t('workflow.form.riskPolicyHint')}>
              <Select
                options={[
                  { value: 'block', label: 'block' },
                  { value: 'allow_medium', label: 'allow_medium' },
                  { value: 'strict', label: 'strict' },
                ]}
              />
            </Form.Item>
          </Col>
        </Row>
      )}

          <div style={{ color: token.colorTextTertiary, fontSize: 12 }}>
            {t('workflow.form.nodeShape', { width: NODE_WIDTH, height: nodeHeight(nodeType) })}
            {' · '}
            {t('workflow.form.handles', {
              in: 1,
              out: sourceHandles(nodeType).length,
            })}
          </div>
        </>
      )}

      {showFooter && (
        <div className="wf-inspector-footer">
          <Space>
            <Button icon={<SaveOutlined />} type="primary" onClick={() => void handleSubmit()}>
              {t('common.save')}
            </Button>
            <Tooltip title={fixed ? t('workflow.inspector.deleteDisabled') : undefined}>
              <Button danger icon={<DeleteOutlined />} disabled={fixed} onClick={() => onRequestDelete(node.key)}>
                {t('common.delete')}
              </Button>
            </Tooltip>
            {fixed && <Tag>{t('workflow.node.fixed')}</Tag>}
          </Space>
        </div>
      )}
    </Form>
  );
};

export default NodeFormPanel;
