/**
 * 两个模拟条件演示、一个服务重启审批模板和一个真实 AI 审阅模板。
 *
 * 纯 WorkflowDefinition 构造器：结构满足 start 无入边、end 无出边、condition 双出各一条，
 * 变量引用只指向祖先；应用时统一走 editor.commit('template', snapshotOf(def))。
 * 名称/描述在 UI 层按 template.<id> 本地化，这里只保留 key/结构。
 */

import { createWorkflowNode } from '../../graph';
import type { WorkflowDefinition, WorkflowNode, WorkflowNodeType } from '../../types';
import { buildAiReviewTemplate } from './aiReview';

export type WorkflowTemplateId = 'disk' | 'restart' | 'health' | 'aiReview';

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

/** 固定数值的纯条件演示，不采集主机数据或发送通知。 */
function diskTemplate(): WorkflowDefinition {
  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: 'skip',
    onRejection: 'abort',
    variables: {
      threshold: { type: 'number', required: true, default: 90, description: 'Demo threshold' },
    },
    nodes: [
      node('start', 'start', 'Start', 0, 140),
      node('transform', 'metrics', 'Simulated metrics', 300, 140, {
        mappings: [
          { name: 'usage', value: '87' },
          { name: 'threshold', value: '${run.input.threshold}' },
        ],
      }),
      node('condition', 'over', 'Demo threshold?', 600, 140, {
        expression: 'nodes.metrics.output.usage > nodes.metrics.output.threshold',
      }),
      node('transform', 'alternative', 'Other branch', 900, 240, {
        mappings: [{ name: 'note', value: 'Simulated data only' }],
      }),
      node('end', 'end', 'End', 1200, 140, {
        outputs: END_OUTPUTS('Simulated data only', [
          { name: 'breached', value: '${nodes.over.output.result}' },
        ]),
      }),
    ],
    edges: [
      edge('start', 'metrics'),
      edge('metrics', 'over'),
      edge('over', 'end', 'true'),
      edge('over', 'alternative', 'false'),
      edge('alternative', 'end'),
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
      messageTemplate: 'Restart command completed for ${nodes.result.output.service}; approval: ${nodes.result.output.approved}. Service health was not verified.',
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

/** 固定数量的纯条件演示，不代表健康检查结果。 */
function healthTemplate(): WorkflowDefinition {
  return {
    schemaVersion: 1,
    defaultOnUpstreamFailure: 'skip',
    onRejection: 'abort',
    variables: {
      minimum: { type: 'number', required: true, default: 1, description: 'Demo minimum' },
    },
    nodes: [
      node('start', 'start', 'Start', 0, 140),
      node('transform', 'stats', 'Simulated count', 300, 140, {
        mappings: [
          { name: 'targetCount', value: '1' },
          { name: 'minimum', value: '${run.input.minimum}' },
        ],
      }),
      node('condition', 'healthy', 'Demo comparison?', 600, 140, {
        expression: 'nodes.stats.output.targetCount >= nodes.stats.output.minimum',
      }),
      node('transform', 'alternative', 'Other branch', 900, 240, {
        mappings: [{ name: 'note', value: 'Simulated data only' }],
      }),
      node('end', 'end', 'End', 1200, 140, {
        outputs: END_OUTPUTS('Simulated data only', [
          { name: 'comparison', value: '${nodes.healthy.output.result}' },
        ]),
      }),
    ],
    edges: [
      edge('start', 'stats'),
      edge('stats', 'healthy'),
      edge('healthy', 'end', 'true'),
      edge('healthy', 'alternative', 'false'),
      edge('alternative', 'end'),
    ],
  };
}

export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [
  { id: 'disk', build: diskTemplate },
  { id: 'restart', build: restartTemplate },
  { id: 'health', build: healthTemplate },
  { id: 'aiReview', build: buildAiReviewTemplate },
];

export function templateById(id: string | undefined | null): WorkflowTemplate | undefined {
  return WORKFLOW_TEMPLATES.find((template) => template.id === id);
}
