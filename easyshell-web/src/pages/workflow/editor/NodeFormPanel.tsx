import React, { useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
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
  AiOutputMode,
  ApprovalConfig,
  ApprovalMode,
  ClassifierClass,
  ClassifierConfig,
  CodeConfig,
  CodeLanguage,
  ConditionConfig,
  EndOutput,
  LlmConfig,
  NotifyConfig,
  ScriptExecConfig,
  ScriptLibrarySource,
  TargetsConfig,
  TransformMapping,
  VariableSpec,
  WaitConfig,
  WorkflowDefinition,
  WorkflowNode,
  WorkflowNodeType,
} from '../types';
import {
  MAX_CLASSIFIER_CLASSES,
  classifierClassNamesOf,
  nodeHeight,
  NODE_WIDTH,
  sourceHandlesForNode,
} from '../graph';
import { availableVariablesForNode } from '../variables';
import type { WorkflowVariableGroup } from '../variables';
import { ChannelsEditor, ParametersEditor, TargetsEditor, type TargetOptions } from './NodeConfigFields';
import { EMPTY_TARGETS, NOTIFY_CHANNELS, TARGETS_MODES, type TargetsMode, type TargetsValue } from './nodeConfig';
import { getHostList } from '../../../api/host';
import { getExecuteTargets } from '../../../api/myHostPermissions';
import { getSessionEpoch, subscribeSessionEpoch } from '../../../api/request';
import { getClusterList } from '../../../api/cluster';
import { getTagList } from '../../../api/tag';
import { getScriptList, getScriptParameters } from '../../../api/script';
import type { Script } from '../../../types';
import { VariableInput } from './inspector/VariablePicker';
import { AiAnalyzeFields } from './inspector/AiAnalyzeFields';
import { LlmFields } from './inspector/LlmFields';
import { ClassifierFields } from './inspector/ClassifierFields';
import { CodeFields } from './inspector/CodeFields';
import { ConditionBuilder } from './inspector/ConditionBuilder';
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
  workflowId?: number;
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
  aiOutputMode?: AiOutputMode;
  aiJsonSchema?: string;
  llmProvider?: string | null;
  llmModel?: string | null;
  llmSystemPrompt?: string;
  llmUserPrompt?: string;
  llmOutputMode?: AiOutputMode;
  llmJsonSchema?: string;
  llmTemperature?: number;
  llmMaxInputChars?: number;
  classifierInput?: string;
  classifierInstructions?: string;
  classifierProvider?: string | null;
  classifierModel?: string | null;
  codeLanguage?: CodeLanguage;
  code?: string;
  codeTimeoutSec?: number;
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
  workflowId,
  open = true,
  definition = EMPTY_DEFINITION,
  onSave,
  onSaveMeta,
  onRequestDelete,
  remoteOptions = false,
  showFooter = true,
  submitRef,
}) => {
  const { t, i18n } = useTranslation();
  const sourceText = (key: string, en: string, zh: string) =>
    t(`workflow.scriptSource.${key}`, { defaultValue: i18n.language.toLowerCase().startsWith('zh') ? zh : en });
  const { token } = theme.useToken();
  const [form] = Form.useForm<FormValues>();
  const nodeType: WorkflowNodeType | undefined = node?.type;
  const renderEpoch = useSyncExternalStore(subscribeSessionEpoch, getSessionEpoch, getSessionEpoch);
  const [initialEpoch] = useState(renderEpoch);
  const sessionValid = initialEpoch === renderEpoch;

  // 结构字段（对象/数组）由面板自持：antd Form 托管不了 targets 的按 mode 动态显隐，
  // 而 parameters/channels 的增删行也不适合走 Form store
  const [targets, setTargets] = useState<TargetsValue>(EMPTY_TARGETS);
  const [parameters, setParameters] = useState<Record<string, string>>({});
  const [rawParams, setRawParams] = useState<string[]>([]);
  const [channels, setChannels] = useState<string[]>([...NOTIFY_CHANNELS.slice(0, 1)]);
  const [startRows, setStartRows] = useState<StartVariableRow[]>([]);
  const [endRows, setEndRows] = useState<EndOutput[]>([]);
  const [transformRows, setTransformRows] = useState<TransformMapping[]>([]);
  const [classifierClasses, setClassifierClasses] = useState<ClassifierClass[]>([]);
  const [codeInputs, setCodeInputs] = useState<Record<string, string>>({});
  const [targetOptions, setTargetOptions] = useState<TargetOptions | undefined>(undefined);
  const [hostAttempt, setHostAttempt] = useState(0);
  const [scriptSource, setScriptSource] = useState<ScriptLibrarySource | null>(null);
  const [sourceMode, setSourceMode] = useState<'manual' | 'library'>('manual');
  const [scripts, setScripts] = useState<Script[]>([]);
  const [sourceLoading, setSourceLoading] = useState(false);
  const [listLoading, setListLoading] = useState(false);
  const [listAttempt, setListAttempt] = useState(0);
  const [sourceError, setSourceError] = useState('');
  const sourceRequest = useRef(0);
  const [cacheEpoch, setCacheEpoch] = useState(renderEpoch);
  const editRevision = useRef(0);
  const submitting = useRef(false);
  const context = useMemo(() => ({ node, open, workflowId, variables: definition.variables, targets, parameters, rawParams, channels, startRows, endRows, transformRows, classifierClasses, codeInputs, scriptSource, sourceMode, sourceLoading }), [node, open, workflowId, definition.variables, targets, parameters, rawParams, channels, startRows, endRows, transformRows, classifierClasses, codeInputs, scriptSource, sourceMode, sourceLoading]);
  const committedContext = useRef<object | null>(null);
  useLayoutEffect(() => {
    committedContext.current = context;
    return () => { committedContext.current = null; };
  }, [context]);

  useEffect(() => {
    if (cacheEpoch === renderEpoch) return;
    setCacheEpoch(renderEpoch);
    sourceRequest.current += 1;
    setTargetOptions(undefined);
    setScripts([]);
    setSourceLoading(false);
    setListLoading(false);
    setSourceError('');
  }, [renderEpoch, cacheEpoch]);

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
      aiOutputMode: str(config.outputMode, 'text'),
      aiJsonSchema: str(config.jsonSchema),
      llmProvider: typeof config.provider === 'string' ? config.provider : undefined,
      llmModel: typeof config.model === 'string' ? config.model : undefined,
      llmSystemPrompt: str(config.systemPrompt),
      llmUserPrompt: str(config.userPrompt),
      llmOutputMode: str(config.outputMode, 'text'),
      llmJsonSchema: str(config.jsonSchema),
      llmTemperature: num(config.temperature, 0.3),
      llmMaxInputChars: num(config.maxInputChars, 8000),
      classifierInput: str(config.input),
      classifierInstructions: str(config.instructions),
      classifierProvider: typeof config.provider === 'string' ? config.provider : undefined,
      classifierModel: typeof config.model === 'string' ? config.model : undefined,
      codeLanguage: config.language === 'python' ? 'python' : 'javascript',
      code: str(config.code),
      codeTimeoutSec: num(config.timeoutSec, 5),
    };
  }, [node]);

  // 换节点/重开面板时把结构字段同步成该节点的 config（否则会带着上一个节点的值）
  useEffect(() => {
    if (!open || !node || !initialValues) return;
    const config = node.config || {};
    sourceRequest.current += 1;
    const source = config.scriptSource as ScriptLibrarySource | undefined;
    setScriptSource(source?.kind === 'library-v1' ? source : null);
    setSourceMode(source?.kind === 'library-v1' ? 'library' : 'manual');
    setSourceLoading(false);
    setSourceError('');
    setTargets(targetsOf(config));
    setParameters(strMap(config.parameters));
    setRawParams(strList(config.rawParams));
    setChannels(strList(config.channels).length > 0 ? strList(config.channels) : ['telegram']);
    setStartRows(variablesToRows(definition.variables));
    setEndRows(configOutputs(config.outputs));
    setTransformRows(mappingRows(config.mappings));
    setClassifierClasses(
      Array.isArray(config.classes)
        ? config.classes.map((item) => {
            const row = (item || {}) as Record<string, unknown>;
            return { name: str(row.name), description: str(row.description) };
          })
        : []
    );
    setCodeInputs(strMap(config.inputs));
    form.setFieldsValue(initialValues);
  }, [open, node, initialValues, form, definition.variables]);

  useEffect(() => {
    if (!sessionValid || !open || nodeType !== 'script-exec' || sourceMode !== 'library') return;
    let cancelled = false;
    const epoch = renderEpoch;
    const current = () => !cancelled && epoch === getSessionEpoch();
    setListLoading(true);
    setSourceError('');
    getScriptList().then((res) => {
      if (!current()) return;
      if (res.code !== 200 || !Array.isArray(res.data)) throw new Error('list');
      setScripts(res.data.filter((item) => item.scriptType === 'shell'));
    }).catch(() => {
      if (current()) setSourceError('list');
    }).finally(() => {
      if (current()) setListLoading(false);
    });
    return () => { cancelled = true; };
  }, [open, nodeType, sourceMode, listAttempt, renderEpoch, sessionValid]);

  const selectLibraryScript = async (id: number) => {
    const request = ++sourceRequest.current;
    const epoch = renderEpoch;
    const current = () => request === sourceRequest.current && epoch === getSessionEpoch();
    if (epoch !== getSessionEpoch() || cacheEpoch !== epoch) return;
    const script = scripts.find((item) => item.id === id);
    setScriptSource(null);
    setSourceLoading(true);
    setSourceError('');
    try {
      if (!script || !Number.isSafeInteger(script.id) || script.id <= 0 ||
          !Number.isSafeInteger(script.version) || script.version <= 0 || typeof script.content !== 'string') throw new Error('changed');
      const [response, digest] = await Promise.all([
        getScriptParameters(id),
        crypto.subtle.digest('SHA-256', new TextEncoder().encode(script.content)),
      ]);
      if (response.code !== 200 || !Array.isArray(response.data)) throw new Error('parameters');
      if (!current()) return;
      const sha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
      const names = new Set(response.data.filter((name) => /^[A-Z][A-Z0-9_]*$/.test(name)));
      const slots = [...script.content.matchAll(/\{\{\s*([A-Z][A-Z0-9_]*)(?::(raw))?\s*}}/g)];
      const suggested = slots.filter((slot) => slot[2] === 'raw' || names.has(slot[1])).map((slot) => slot[1]);
      setParameters((previous) => {
        const next = { ...previous };
        suggested.forEach((name) => { if (!Object.prototype.hasOwnProperty.call(next, name)) next[name] = ''; });
        return next;
      });
      form.setFieldsValue({ script: script.content });
      setScriptSource({ kind: 'library-v1', scriptId: script.id, version: script.version, sha256 });
    } catch {
      if (current()) setSourceError('selection');
    } finally {
      if (current()) setSourceLoading(false);
    }
  };

  // 可执行 ID 是权威候选集；可见主机列表仅用于补充显示名称。
  useEffect(() => {
    if (!sessionValid || !remoteOptions || !open || nodeType !== 'script-exec') return;
    let cancelled = false;
    const epoch = getSessionEpoch();
    const current = () => !cancelled && epoch === getSessionEpoch();
    setTargetOptions((previous) => ({ ...previous, hosts: [], hostStatus: 'loading' }));
    const loadHosts = async () => {
      const visibleHosts = getHostList().then((response) => response.code === 200 && Array.isArray(response.data) ? response.data : []).catch(() => []);
      const ids = new Set<string>();
      const cursors = new Set<string>();
      let cursor: string | undefined;
      // A broken or malicious pagination response must not cause an unbounded loop.
      for (let page = 0; page < 1000; page++) {
        const response = await getExecuteTargets(cursor);
        if (!current()) return null;
        const data = response.data;
        if (response.code !== 200 || !data || !Array.isArray(data.agentIds) || data.agentIds.length > 100 ||
            data.agentIds.some((id) => typeof id !== 'string' || !id.trim() || id.length > 64) ||
            !(data.nextCursor === null || (typeof data.nextCursor === 'string' && data.nextCursor.trim() && data.nextCursor.length <= 64)) ||
            (data.nextCursor !== null && (data.agentIds.length === 0 || data.nextCursor !== data.agentIds[data.agentIds.length - 1]))) throw new Error('execute targets');
        data.agentIds.forEach((id) => ids.add(id));
        if (data.nextCursor === null) {
          const visible = await visibleHosts;
          if (!current()) return null;
          const labels = new Map(visible.map((host) => [host.id, `${host.hostname} · ${host.ip}`]));
          return Array.from(ids, (id) => ({ value: id, label: labels.get(id) || id }));
        }
        if (cursors.has(data.nextCursor)) throw new Error('repeated cursor');
        cursors.add(data.nextCursor);
        cursor = data.nextCursor;
      }
      throw new Error('too many pages');
    };
    loadHosts().then((hosts) => {
      if (current() && hosts) setTargetOptions((previous) => current() ? ({ ...previous, hosts, hostStatus: 'ready' }) : previous);
    }).catch(() => {
      if (current()) setTargetOptions((previous) => current() ? ({ ...previous, hosts: [], hostStatus: 'error' }) : previous);
    });
    return () => { cancelled = true; };
  }, [remoteOptions, open, nodeType, node?.key, hostAttempt, renderEpoch, sessionValid]);

  useEffect(() => {
    if (!sessionValid || !remoteOptions || !open || nodeType !== 'script-exec') return;
    let cancelled = false;
    const epoch = getSessionEpoch();
    const current = () => !cancelled && epoch === getSessionEpoch();
    setTargetOptions((previous) => ({ ...previous, clusters: undefined, tags: undefined, fallbackClusters: true, fallbackTags: true }));
    getClusterList().then((response) => {
      if (response.code !== 200 || !Array.isArray(response.data)) throw new Error('clusters');
      const clusters = response.data.map((cluster) => ({ value: String(cluster.id), label: cluster.name }));
      if (current()) setTargetOptions((previous) => current() ? ({ ...previous, clusters, fallbackClusters: clusters.length === 0 }) : previous);
    }).catch(() => {
      if (current()) setTargetOptions((previous) => current() ? ({ ...previous, clusters: [], fallbackClusters: true }) : previous);
    });
    getTagList().then((response) => {
      if (response.code !== 200 || !Array.isArray(response.data)) throw new Error('tags');
      const tags = response.data.map((tag) => ({ value: String(tag.id), label: tag.name }));
      if (current()) setTargetOptions((previous) => current() ? ({ ...previous, tags, fallbackTags: tags.length === 0 }) : previous);
    }).catch(() => {
      if (current()) setTargetOptions((previous) => current() ? ({ ...previous, tags: [], fallbackTags: true }) : previous);
    });
    return () => { cancelled = true; };
  }, [remoteOptions, open, nodeType, node?.key, hostAttempt, renderEpoch, sessionValid]);

  const handleSubmit = async () => {
    if (!node || !open || submitting.current || initialEpoch !== getSessionEpoch()) return;
    const epoch = renderEpoch;
    const revision = editRevision.current;
    const current = () => epoch === getSessionEpoch() && committedContext.current === context && revision === editRevision.current;
    if (!current()) return;
    submitting.current = true;
    try {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验失败：antd 已在字段上标红，不再静默提交
    }
    if (!current()) return;

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
      if (sourceMode === 'library' && (!scriptSource || sourceLoading)) {
        setSourceError('selection');
        return;
      }
      const scriptExec: ScriptExecConfig = {
        script: values.script || '',
        targets: targetsConfigOf(targets),
        parameters: compactParameters(parameters),
        rawParams,
        onUnsafeValue: values.onUnsafeValue || 'reject',
        riskPolicy: values.riskPolicy || 'block',
      };
      delete config.scriptId;
      delete config.scriptSource;
      if (sourceMode === 'library' && scriptSource) scriptExec.scriptSource = scriptSource;
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
        outputMode: values.aiOutputMode || 'text',
      };
      if (values.aiJsonSchema !== undefined && values.aiJsonSchema !== '') {
        ai.jsonSchema = values.aiJsonSchema;
      }
      delete config.jsonSchema;
      Object.assign(config, ai);
    }
    if (nodeType === 'llm') {
      const llm: LlmConfig = {
        provider: values.llmProvider && values.llmProvider.trim() ? values.llmProvider.trim() : null,
        model: values.llmModel && values.llmModel.trim() ? values.llmModel.trim() : null,
        systemPrompt: values.llmSystemPrompt || '',
        userPrompt: values.llmUserPrompt || '',
        outputMode: values.llmOutputMode || 'text',
        temperature: values.llmTemperature ?? 0.3,
        maxInputChars: values.llmMaxInputChars ?? 8000,
      };
      if (values.llmJsonSchema !== undefined && values.llmJsonSchema !== '') {
        llm.jsonSchema = values.llmJsonSchema;
      }
      delete config.jsonSchema;
      Object.assign(config, llm);
    }
    if (nodeType === 'classifier') {
      const classes = classifierClasses
        .filter((item) => item.name.trim())
        .map((item) => ({
          name: item.name.trim(),
          ...(item.description && item.description.trim() ? { description: item.description.trim() } : {}),
        }));
      const classNames = classifierClassNamesOf({ classes });
      const nonEmpty = classifierClasses.filter((item) => item.name.trim()).length;
      if (
        classes.length === 0 ||
        classNames.length !== nonEmpty ||
        classes.length > MAX_CLASSIFIER_CLASSES
      ) {
        message.error(t('workflow.classifier.invalidClassList'));
        return;
      }
      const classifier: ClassifierConfig = {
        input: values.classifierInput || '',
        classes,
        instructions: values.classifierInstructions || '',
        provider:
          values.classifierProvider && values.classifierProvider.trim()
            ? values.classifierProvider.trim()
            : null,
        model: values.classifierModel && values.classifierModel.trim() ? values.classifierModel.trim() : null,
      };
      Object.assign(config, classifier);
    }
    if (nodeType === 'code') {
      const code: CodeConfig = {
        language: values.codeLanguage === 'python' ? 'python' : 'javascript',
        code: values.code || '',
        inputs: compactParameters(codeInputs),
        timeoutSec: values.codeTimeoutSec ?? 5,
      };
      Object.assign(config, code);
    }

    const strict = (nodeType === 'llm' && values.llmOutputMode === 'json_schema_v1') ||
      (nodeType === 'ai-analyze' && values.aiOutputMode === 'json_schema_v1');
    onSave(node.key, {
      name: values.name,
      config,
      outputVar: values.outputVar || undefined,
      retryMax: strict ? 0 : values.retryMax ?? 0,
      retryIntervalSec: values.retryIntervalSec ?? 5,
      timeoutSec: values.timeoutSecNode ?? null,
      onFailure: strict ? 'abort' : values.onFailure,
    });
    message.success(t('workflow.inspector.saved'));
    } finally {
      submitting.current = false;
    }
  };

  // 固定 footer 由 NodeInspector 渲染时，把提交函数交给父组件（ref 只在 effect 里写）
  useEffect(() => {
    if (submitRef) {
      submitRef.current = sessionValid && open && node ? handleSubmit : null;
      return () => { if (submitRef.current === handleSubmit) submitRef.current = null; };
    }
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

  if (!sessionValid || !open || !node || !nodeType) {
    return <Text type="secondary">{t('workflow.form.empty')}</Text>;
  }

  return (
    <Form form={form} layout="vertical" initialValues={initialValues} requiredMark="optional" className="wf-node-form"
      onValuesChange={(changed) => {
        editRevision.current += 1;
        if (changed.llmOutputMode === 'json_schema_v1' || changed.aiOutputMode === 'json_schema_v1') {
          form.setFieldsValue({ retryMax: 0, onFailure: 'abort' });
        }
      }}>
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

      {nodeType === 'llm' && <LlmFields groups={groups} />}

      {nodeType === 'classifier' && (
        <ClassifierFields
          groups={groups}
          classes={classifierClasses}
          onClassesChange={setClassifierClasses}
        />
      )}

      {nodeType === 'code' && (
        <CodeFields key={`${workflowId ?? ''}:${node.key}`} workflowId={workflowId} groups={groups} inputs={codeInputs} onInputsChange={setCodeInputs} />
      )}

      {nodeType === 'script-exec' && (
        <>
          <Form.Item label={sourceText('label', 'Script source', '脚本来源')}>
            <Select aria-label={sourceText('label', 'Script source', '脚本来源')} value={sourceMode} onChange={(mode: 'manual' | 'library') => {
              sourceRequest.current += 1;
              setSourceMode(mode);
              setScriptSource(null);
              setSourceLoading(false);
              setSourceError('');
            }} options={[
              { value: 'manual', label: sourceText('manual', 'Manual', '手写') },
              { value: 'library', label: sourceText('library', 'Script library', '脚本库') },
            ]} />
          </Form.Item>
          {sourceMode === 'library' && <>
            <Form.Item label={sourceText('select', 'Library script', '选择脚本')}>
              <Select key={renderEpoch} aria-label={sourceText('select', 'Library script', '选择脚本')} allowClear loading={sourceLoading || listLoading}
                value={scriptSource?.scriptId} options={(cacheEpoch === renderEpoch ? scripts : []).map((script) => ({ value: script.id, label: script.name }))}
                onChange={(id: number | undefined) => {
                  if (id === undefined) { sourceRequest.current += 1; setScriptSource(null); setSourceLoading(false); setSourceError(''); }
                  else void selectLibraryScript(id);
                }} />
            </Form.Item>
            <Alert type="info" showIcon message={sourceText('snapshotHint', 'Preview is read-only; publication uses the verified server snapshot.', '预览只读；发布以服务端校验后的快照为准。')} />
            {sourceError && <Alert type="error" showIcon message={sourceError === 'list'
              ? sourceText('listError', 'Unable to load scripts. Check your access and try again.', '脚本列表加载失败，请检查权限后重试。')
              : sourceText('selectionError', 'Unable to select the script. It may have changed or access was denied; try again.', '无法选择脚本，脚本可能已变更或无权限，请重试。')} />}
            {sourceError === 'list' && <Button onClick={() => setListAttempt((value) => value + 1)}>{sourceText('retry', 'Retry', '重试')}</Button>}
          </>}
          <Form.Item
            name="script"
            label={t('workflow.form.script')}
            rules={[{ required: true, message: t('workflow.form.scriptRequired') }]}
            tooltip={t('workflow.form.scriptHint')}
          >
            <Input.TextArea readOnly={sourceMode === 'library'} rows={8} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>
          <Form.Item
            label={t('workflow.form.targets')}
            required
            tooltip={t('workflow.form.targetsHint')}
            extra={targetIdsEmpty ? t('workflow.form.targetsEmpty') : undefined}
          >
            <TargetsEditor value={targets} onChange={setTargets} options={cacheEpoch === renderEpoch ? targetOptions : undefined} groups={groups} onRetryHosts={remoteOptions ? () => setHostAttempt((attempt) => attempt + 1) : undefined} />
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
            <ConditionBuilder groups={groups} />
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
              out: sourceHandlesForNode(node).length,
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
