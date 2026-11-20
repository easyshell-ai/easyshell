import { useState } from 'react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  useStore,
  type EdgeProps,
} from '@xyflow/react';
import { conditionBadgeTitle, edgePillOf } from './edgeStyle';
import type { WorkflowFlowEdge, WorkflowFlowNode } from '../../graph';

/**
 * 自定义边：IF/ELSE 胶囊常驻，普通边无标签（§3.5）。
 *
 * 源节点类型优先从 React Flow store 查（新建连线没有 data.sourceType），
 * 查不到时回落到 definitionToFlow 写入的 data.sourceType。
 */
export default function WorkflowEdge(props: EdgeProps<WorkflowFlowEdge>) {
  const {
    id,
    source,
    sourceHandleId,
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    markerEnd,
    style,
    interactionWidth,
    selected,
    data,
  } = props;

  const [hovered, setHovered] = useState(false);
  const sourceNodeType = useStore(
    (state) => (state.nodeLookup.get(source)?.data as WorkflowFlowNode['data'] | undefined)?.nodeType,
  );
  const sourceType = sourceNodeType ?? data?.sourceType;
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const pill = edgePillOf(sourceType, sourceHandleId, data?.condition);
  const showPill = pill.kind !== 'none';
  const showConditionBadge = !showPill && !!data?.condition && (hovered || !!selected);

  return (
    <>
      <g onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}>
        <BaseEdge
          id={id}
          path={edgePath}
          markerEnd={markerEnd}
          style={style}
          interactionWidth={interactionWidth ?? 24}
        />
      </g>
      {(showPill || showConditionBadge) && (
        <EdgeLabelRenderer>
          <div
            className="wf-edge-label nodrag nopan"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          >
            {showPill ? (
              <span className={`wf-edge-pill ${pill.className}`} title={pill.title}>
                {pill.text}
              </span>
            ) : (
              <span className="wf-edge-cond-badge" title={conditionBadgeTitle(data?.condition)}>
                条件
              </span>
            )}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
