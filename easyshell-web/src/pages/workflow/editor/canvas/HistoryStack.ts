/**
 * 撤销/重做命令栈（选项 3，业主点名项）。
 *
 * 存 before/after 快照对而不是逆操作函数：definition 只有"节点 + 边 + 位置 + config"，
 * 快照足够小（100 节点 ≈ 几十 KB），且任何变更（增删节点/连线/移动/配置）都能用同一套
 * 机制覆盖，不需要为每种变更写 inverse。
 *
 * 合并（coalesce）：同一 label 的连续变更在窗口期内合并成一条命令——表单里逐字敲
 * config 不会把 100 步undo 栈冲掉，undo 一次回到这次编辑开始前的状态。
 */

export interface HistoryEntry<T> {
  label: string;
  before: T;
  after: T;
  at: number;
}

export interface HistoryStackOptions {
  limit?: number;
  coalesceWindowMs?: number;
  now?: () => number;
}

export class HistoryStack<T> {
  private past: HistoryEntry<T>[] = [];
  private future: HistoryEntry<T>[] = [];
  private readonly limit: number;
  private readonly coalesceWindowMs: number;
  private readonly now: () => number;

  constructor(options: HistoryStackOptions = {}) {
    this.limit = options.limit ?? 100;
    this.coalesceWindowMs = options.coalesceWindowMs ?? 800;
    this.now = options.now ?? (() => Date.now());
  }

  get canUndo(): boolean {
    return this.past.length > 0;
  }

  get canRedo(): boolean {
    return this.future.length > 0;
  }

  get undoDepth(): number {
    return this.past.length;
  }

  push(label: string, before: T, after: T): HistoryEntry<T> {
    const last = this.past[this.past.length - 1];
    if (last && last.label === label && this.now() - last.at <= this.coalesceWindowMs) {
      last.after = after;
      last.at = this.now();
      this.future = [];
      return last;
    }
    const entry: HistoryEntry<T> = { label, before, after, at: this.now() };
    this.past.push(entry);
    if (this.past.length > this.limit) this.past.shift();
    this.future = [];
    return entry;
  }

  /** 返回需要回退到的快照；栈空返回 null */
  undo(): T | null {
    const entry = this.past.pop();
    if (!entry) return null;
    this.future.push(entry);
    return entry.before;
  }

  /** 返回需要重做到的快照；栈空返回 null */
  redo(): T | null {
    const entry = this.future.pop();
    if (!entry) return null;
    this.past.push(entry);
    return entry.after;
  }

  clear(): void {
    this.past = [];
    this.future = [];
  }
}
