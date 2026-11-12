/**
 * DAG 分层深度算法（从 pages/ai/chat/DagPlanView.tsx:45-64 抽出，编辑画布自动布局与
 * 运行态只读画布共用同一份实现——不引 elkjs，设计 §4.1）。
 *
 * 保持与原实现逐字等价的行为：按依赖递归取最大深度 +1，无依赖者为 0。
 */

export interface DepthEdge {
  from: string;
  to: string;
}

/**
 * 计算每个 key 的分层深度（key = 图中节点 id，edge.from = 上游）。
 * 依赖环不会无限递归：进行中的节点按 0 计（服务端发布校验会拒环，这里只做展示兜底）。
 */
export function computeDepthMap(keys: string[], edges: DepthEdge[]): Map<string, number> {
  const depthMap = new Map<string, number>();
  const inProgress = new Set<string>();

  const calculateDepth = (key: string): number => {
    if (depthMap.has(key)) return depthMap.get(key)!;
    if (inProgress.has(key)) return 0;
    inProgress.add(key);
    const upstream = edges.filter((e) => e.to === key).map((e) => e.from);
    let depth = 0;
    if (upstream.length > 0) {
      const maxDep = Math.max(...upstream.map((u) => calculateDepth(u)));
      depth = maxDep + 1;
    }
    inProgress.delete(key);
    depthMap.set(key, depth);
    return depth;
  };

  keys.forEach((key) => calculateDepth(key));
  return depthMap;
}

/** 按深度分组，层内顺序保持 keys 的传入顺序（与原 DagPlanView 的 layers 行为一致） */
export function groupByDepth(keys: string[], depths: Map<string, number>): Map<number, string[]> {
  const layers = new Map<number, string[]>();
  keys.forEach((key) => {
    const depth = depths.get(key) || 0;
    if (!layers.has(depth)) layers.set(depth, []);
    layers.get(depth)!.push(key);
  });
  return layers;
}
