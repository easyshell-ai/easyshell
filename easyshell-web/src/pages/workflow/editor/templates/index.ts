/**
 * 3 个示例模板（设计 §3.4）：磁盘巡检与告警 / 服务重启审批 / 批量健康检查。
 *
 * 纯 WorkflowDefinition 构造器：结构满足 start 无入边、end 无出边、condition 双出各一条，
 * 变量引用只指向祖先；应用时统一走 editor.commit('template', snapshotOf(def))。
 * 名称/描述在 UI 层按 template.<id> 本地化，这里只保留 key/结构。
 */

import { createWorkflowNode } from '../../graph';
import type { WorkflowDefinition, WorkflowNode, WorkflowNodeType } from '../../types';

export type WorkflowTemplateId = 'disk' | 'restart' | 'health';

export interface WorkflowTemplate {
  id: WorkflowTemplateId;
  build: () => WorkflowDefinition;
}

function node(
  type: WorkflowNodeType,
  key: string,
  name: string,
  x: number,
  y: number,
  config: Record<string, unknown> = {},
  extra: Partial<WorkflowNode> = {}
): WorkflowNode {
  const base = createWorkflowNode(type, key, { x, y }, name);
  return { ...base, config: { ...base.config, ...config }, ...extra };
}

function edge(from: string, to: string, sourceHandle?: string, condition?: string) {
  return {
    id: `${from}:${sourceHandle || 'out'}->${to}`,
    from,
    to,
    ...(sourceHandle ? { sourceHandle } : {}),
    ...(condition ? { condition } : {}),
  };
}

const END_OUTPUTS = (summary: string, extra?: { name: string; value: string }[]) => [
  { name: 'summary', value: summary },
  ...(extra || []),
];

/** ① 磁盘巡检：start → script(df -h) → transform(阈值/摘要) → condition(超阈值) → notify/end */
function diskTemplate(): WorkflowDefinition {
  const nodes: WorkflowNode[] = [
    node('start', 'start', 'Start', 0, 140),
    node(
      'script-exec',
      'check',
      'Disk check',
      300,
      140,
      {
        // 示例数据保证开箱即跑；接入真实主机时替换成 df/awk 采集命令
        script: 'echo \'{"usage":87,"mount":"/"}\'',
        parameters: {},
        targets: { mode: 'variable', variableRef: 'run.input.targets' },
        rawParams: [],
        riskPolicy: 'block',
        onUnsafeValue: 'reject',
      },
      { outputVar: 'disk', timeoutSec: 600 }
    ),
    node('transform', 'metrics', 'Normalize metrics', 600, 140, {
      mappings: [
        { name: 'summary', value: '${run.input.mount} usage: ${nodes.check.output.usage}%' },
        { name: 'usage', value: '${nodes.check.output.usage}' },
        { name: 'threshold', value: '${run.input.threshold}' },
      ],
    }),
    node('condition', 'over', 'Over threshold?', 900, 140, {
      expression: 'nodes.metrics.output.usage > nodes.metrics.output.threshold',
    }),
    node('notify', 'alert', 'Alert', 1200, 40, {
      channels: ['telegram'],
      messageTemplate: 'Disk alert: ${nodes.metrics.output.summary}',
      parameters: {},
      level: 'warning',
    }),
    node('end', 'end', 'End', 1500, 140, {
      outputs: END_OUTPUTS('${nodes.metrics.output.summary}', [
        { name: 'breached', value: '${nodes.over.output.result}' },
      ]),
    }),
  ];

  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: 'skip',
    onRejection: 'abort',
    variables: {
      targets: { type: 'json', required: true, description: 'Agent id list (e.g. ["agent-1"])' },
      mount: { type: 'string', required: true, default: '/', description: 'Mount point to inspect' },
      threshold: { type: 'number', required: true, default: 90, description: 'Usage percent threshold' },
    },
    nodes,
    edges: [
      edge('start', 'check'),
      edge('check', 'metrics'),
      edge('metrics', 'over'),
      edge('over', 'alert', 'true'),
      edge('alert', 'end'),
      edge('over', 'end', 'false'),
    ],
  };
}

/** ② 服务重启审批：start → approval → script → transform → notify → end */
function restartTemplate(): WorkflowDefinition {
  const nodes: WorkflowNode[] = [
    node('start', 'start', 'Start', 0, 140),
    node('approval', 'approve', 'Approve restart', 300, 140, {
      mode: 'approval',
      title: 'Approve restart of ${run.input.service}',
      detailTemplate: 'Target service: ${run.input.service}\nRequested by workflow run.',
      approverRole: 'ADMIN',
      timeoutSec: 3600,
      onTimeout: 'reject',
      parameters: {},
    }),
    node(
      'script-exec',
      'restart',
      'Restart service',
      600,
      140,
      {
        script: 'systemctl restart {{SERVICE}}',
        parameters: { SERVICE: '${run.input.service}' },
        targets: { mode: 'variable', variableRef: 'run.input.targets' },
        rawParams: [],
        riskPolicy: 'allow_medium',
        onUnsafeValue: 'reject',
      },
      { timeoutSec: 600 }
    ),
    node('transform', 'result', 'Build result', 900, 140, {
      mappings: [
        { name: 'service', value: '${run.input.service}' },
        { name: 'approved', value: '${nodes.approve.output.decision}' },
      ],
    }),
    node('notify', 'notify', 'Notify result', 1200, 140, {
      channels: ['telegram'],
      messageTemplate: 'Service ${nodes.result.output.service} restart: ${nodes.result.output.approved}',
      parameters: {},
      level: 'info',
    }),
    node('end', 'end', 'End', 1500, 140, {
      outputs: [
        { name: 'service', value: '${nodes.result.output.service}' },
        { name: 'approved', value: '${nodes.result.output.approved}' },
      ],
    }),
  ];

  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: 'abort',
    onRejection: 'abort',
    variables: {
      targets: { type: 'json', required: true, description: 'Agent id list (e.g. ["agent-1"])' },
      service: { type: 'string', required: true, default: 'nginx', description: 'Service name to restart' },
    },
    nodes,
    edges: [
      edge('start', 'approve'),
      edge('approve', 'restart'),
      edge('restart', 'result'),
      edge('result', 'notify'),
      edge('notify', 'end'),
    ],
  };
}

/** ③ 批量健康检查：start → script(uptime) → transform → condition → notify/end */
function healthTemplate(): WorkflowDefinition {
  const nodes: WorkflowNode[] = [
    node('start', 'start', 'Start', 0, 140),
    node(
      'script-exec',
      'probe',
      'Health probe',
      300,
      140,
      {
        script: 'uptime; df -h /',
        parameters: {},
        targets: { mode: 'variable', variableRef: 'run.input.targets' },
        rawParams: [],
        riskPolicy: 'block',
        onUnsafeValue: 'reject',
      },
      { timeoutSec: 300 }
    ),
    node('transform', 'stats', 'Summarize', 600, 140, {
      mappings: [
        { name: 'summary', value: '${nodes.probe.output}' },
        { name: 'targetCount', value: '1' },
      ],
    }),
    node('condition', 'healthy', 'Any output?', 900, 140, {
      expression: 'nodes.stats.output.targetCount > 0',
    }),
    node('notify', 'report', 'Report', 1200, 40, {
      channels: ['telegram'],
      messageTemplate: 'Health check: ${nodes.stats.output.summary}',
      parameters: {},
      level: 'info',
    }),
    node('wait', 'pause', 'Cooldown', 1200, 220, { durationSec: 5 }),
    node('end', 'end', 'End', 1500, 140, {
      outputs: END_OUTPUTS('${nodes.stats.output.summary}'),
    }),
  ];

  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: 'skip',
    onRejection: 'abort',
    variables: {
      targets: { type: 'json', required: true, description: 'Agent id list (e.g. ["agent-1"])' },
    },
    nodes,
    edges: [
      edge('start', 'probe'),
      edge('probe', 'stats'),
      edge('stats', 'healthy'),
      edge('healthy', 'report', 'true'),
      edge('healthy', 'pause', 'false'),
      edge('report', 'end'),
      edge('pause', 'end'),
    ],
  };
}

export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [
  { id: 'disk', build: diskTemplate },
  { id: 'restart', build: restartTemplate },
  { id: 'health', build: healthTemplate },
];

export function templateById(id: string | undefined | null): WorkflowTemplate | undefined {
  return WORKFLOW_TEMPLATES.find((template) => template.id === id);
}
