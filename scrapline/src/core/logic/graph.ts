/**
 * The wiring graph behind the visual programming system.
 *
 * Everything lives in fixed-size typed arrays allocated once (about 70 KB),
 * so wiring up a base never allocates and never grows the heap.
 *
 * Evaluation is double buffered: nodes read last tick's outputs and write next
 * tick's, so evaluation order is irrelevant, feedback loops are well defined,
 * and each logic node adds exactly one tick of delay. Pure logic nodes are
 * evaluated only when one of their inputs changed (or they asked to run again),
 * so an idle circuit costs nothing.
 */

export const MAX_NODES = 1024;
export const MAX_WIRES = 4096;
/** Input ports and output ports per node. */
export const PORTS = 4;
/** Logic ticks per second; the sim steps the graph on every second 20 Hz tick. */
export const LOGIC_HZ = 10;

export const NodeType = {
  Free: 0,
  /** Backed by a world entity; the host reads sensors into it and drives actuators from it. */
  Device: 1,
  And: 2,
  Or: 3,
  Not: 4,
  /** Output follows the input after `param0` ticks; shorter blips are swallowed. */
  Delay: 5,
  /** Square wave with a period of `param0` ticks while `run` is high (or unwired). */
  Timer: 6,
} as const;

export interface LogicDef {
  name: string;
  ins: readonly string[];
  outs: readonly string[];
  /** Label and range of the one adjustable setting, if any. */
  param?: { label: string; min: number; max: number; step: number; initial: number; unit: string };
}

export const LOGIC_DEFS: readonly LogicDef[] = [
  { name: '', ins: [], outs: [] },
  { name: 'Device', ins: [], outs: [] },
  { name: 'AND', ins: ['a', 'b'], outs: ['out'] },
  { name: 'OR', ins: ['a', 'b'], outs: ['out'] },
  { name: 'NOT', ins: ['in'], outs: ['out'] },
  { name: 'Delay', ins: ['in'], outs: ['out'], param: { label: 'Wait', min: 2, max: 100, step: 2, initial: 10, unit: 'ticks' } },
  { name: 'Timer', ins: ['run'], outs: ['pulse'], param: { label: 'Period', min: 2, max: 200, step: 2, initial: 20, unit: 'ticks' } },
];

/** Reads sensors and drives actuators for Device nodes. Implemented by the sim. */
export interface LogicHost {
  /** Write the entity's current output values to out[base .. base + PORTS). */
  sense(entity: number, node: number, out: Int16Array, base: number): void;
  /** Apply the entity's input values; read them with `inputValue` / `inputWired`. */
  drive(entity: number, node: number): void;
}

export interface Graph {
  /** One past the highest node id ever used. Node 0 is never used. */
  high: number;
  type: Uint8Array;
  entity: Uint16Array;
  /** Two settings per node. */
  param: Int16Array;
  /** Two words of private state per node. */
  state: Int16Array;
  /** Position on the circuit board. */
  bx: Int16Array;
  by: Int16Array;
  /** Output values visible this tick / being written for next tick, PORTS per node. */
  outCur: Int16Array;
  outNext: Int16Array;
  /** Packed wires: srcNode | srcPort << 10 | dstNode << 12 | dstPort << 22. */
  wires: Uint32Array;
  wireCount: number;
  /** Adjacency in CSR form, rebuilt only after an edit. */
  inStart: Uint16Array;
  inList: Uint16Array;
  outStart: Uint16Array;
  outList: Uint16Array;
  csrStale: boolean;
  /** Nodes to evaluate this tick and next tick. */
  queue: Uint16Array;
  queueLen: number;
  nextQueue: Uint16Array;
  nextLen: number;
  queued: Uint8Array;
  /** Pure logic nodes evaluated in the last step; exposed for tests and the debug overlay. */
  lastEvals: number;
  /** Bumped on every structural edit so the UI knows to redraw. */
  version: number;
}

export function createGraph(): Graph {
  return {
    high: 1,
    type: new Uint8Array(MAX_NODES),
    entity: new Uint16Array(MAX_NODES),
    param: new Int16Array(MAX_NODES * 2),
    state: new Int16Array(MAX_NODES * 2),
    bx: new Int16Array(MAX_NODES),
    by: new Int16Array(MAX_NODES),
    outCur: new Int16Array(MAX_NODES * PORTS),
    outNext: new Int16Array(MAX_NODES * PORTS),
    wires: new Uint32Array(MAX_WIRES),
    wireCount: 0,
    inStart: new Uint16Array(MAX_NODES * PORTS + 1),
    inList: new Uint16Array(MAX_WIRES),
    outStart: new Uint16Array(MAX_NODES * PORTS + 1),
    outList: new Uint16Array(MAX_WIRES),
    csrStale: true,
    queue: new Uint16Array(MAX_NODES),
    queueLen: 0,
    nextQueue: new Uint16Array(MAX_NODES),
    nextLen: 0,
    queued: new Uint8Array(MAX_NODES),
    lastEvals: 0,
    version: 0,
  };
}

export const wireSrcNode = (w: number) => w & 0x3ff;
export const wireSrcPort = (w: number) => (w >>> 10) & 3;
export const wireDstNode = (w: number) => (w >>> 12) & 0x3ff;
export const wireDstPort = (w: number) => (w >>> 22) & 3;

function pack(sn: number, sp: number, dn: number, dp: number): number {
  return (sn | (sp << 10) | (dn << 12) | (dp << 22)) >>> 0;
}

/** Schedules a node for evaluation on the next logic tick. */
export function markDirty(g: Graph, node: number): void {
  if (g.queued[node] || g.type[node] <= NodeType.Device) return;
  g.queued[node] = 1;
  g.nextQueue[g.nextLen++] = node;
}

/** Adds a node. Returns its id, or 0 if the graph is full. */
export function addNode(g: Graph, type: number, entity = 0, bx = 0, by = 0): number {
  let id = 0;
  for (let i = 1; i < g.high; i++) {
    if (g.type[i] === NodeType.Free) {
      id = i;
      break;
    }
  }
  if (id === 0) {
    if (g.high >= MAX_NODES) return 0;
    id = g.high++;
  }
  g.type[id] = type;
  g.entity[id] = entity;
  g.param[id * 2] = LOGIC_DEFS[type].param?.initial ?? 0;
  g.param[id * 2 + 1] = 0;
  g.state[id * 2] = 0;
  g.state[id * 2 + 1] = 0;
  g.bx[id] = bx;
  g.by[id] = by;
  for (let p = 0; p < PORTS; p++) {
    g.outCur[id * PORTS + p] = 0;
    g.outNext[id * PORTS + p] = 0;
  }
  g.queued[id] = 0;
  markDirty(g, id);
  g.version++;
  return id;
}

/** Removes a node and every wire attached to it. */
export function removeNode(g: Graph, id: number): void {
  if (id <= 0 || g.type[id] === NodeType.Free) return;
  for (let i = g.wireCount - 1; i >= 0; i--) {
    const w = g.wires[i];
    if (wireSrcNode(w) === id || wireDstNode(w) === id) removeWireAt(g, i);
  }
  g.type[id] = NodeType.Free;
  g.entity[id] = 0;
  g.version++;
}

export function findWire(g: Graph, sn: number, sp: number, dn: number, dp: number): number {
  const w = pack(sn, sp, dn, dp);
  for (let i = 0; i < g.wireCount; i++) if (g.wires[i] === w) return i;
  return -1;
}

/** Connects an output port to an input port. Returns false if it already exists or cannot be made. */
export function addWire(g: Graph, sn: number, sp: number, dn: number, dp: number): boolean {
  if (g.wireCount >= MAX_WIRES || sn === dn) return false;
  if (g.type[sn] === NodeType.Free || g.type[dn] === NodeType.Free) return false;
  if (findWire(g, sn, sp, dn, dp) !== -1) return false;
  g.wires[g.wireCount++] = pack(sn, sp, dn, dp);
  g.csrStale = true;
  markDirty(g, dn);
  g.version++;
  return true;
}

export function removeWireAt(g: Graph, index: number): void {
  if (index < 0 || index >= g.wireCount) return;
  const dn = wireDstNode(g.wires[index]);
  g.wires[index] = g.wires[--g.wireCount];
  g.csrStale = true;
  markDirty(g, dn);
  g.version++;
}

function rebuildCsr(g: Graph): void {
  const slots = MAX_NODES * PORTS;
  // Pass 1: count wires per port, then turn the counts into end offsets.
  g.inStart.fill(0);
  g.outStart.fill(0);
  for (let i = 0; i < g.wireCount; i++) {
    const w = g.wires[i];
    g.inStart[wireDstNode(w) * PORTS + wireDstPort(w)]++;
    g.outStart[wireSrcNode(w) * PORTS + wireSrcPort(w)]++;
  }
  let inSum = 0;
  let outSum = 0;
  for (let s = 0; s < slots; s++) {
    inSum += g.inStart[s];
    outSum += g.outStart[s];
    g.inStart[s] = inSum;
    g.outStart[s] = outSum;
  }
  // Pass 2: place each wire by walking its port's end offset down. When done,
  // every entry has become that port's start offset.
  for (let i = 0; i < g.wireCount; i++) {
    const w = g.wires[i];
    g.inList[--g.inStart[wireDstNode(w) * PORTS + wireDstPort(w)]] = (wireSrcNode(w) << 2) | wireSrcPort(w);
    g.outList[--g.outStart[wireSrcNode(w) * PORTS + wireSrcPort(w)]] = (wireDstNode(w) << 2) | wireDstPort(w);
  }
  g.inStart[slots] = g.wireCount;
  g.outStart[slots] = g.wireCount;
  g.csrStale = false;
}

/** Whether anything is wired to an input port. */
export function inputWired(g: Graph, node: number, port: number): boolean {
  if (g.csrStale) rebuildCsr(g);
  const s = node * PORTS + port;
  return g.inStart[s + 1] > g.inStart[s];
}

/** Value arriving at an input port: the highest value among everything wired to it, 0 if unwired. */
export function inputValue(g: Graph, node: number, port: number): number {
  if (g.csrStale) rebuildCsr(g);
  const s = node * PORTS + port;
  let v = 0;
  for (let i = g.inStart[s]; i < g.inStart[s + 1]; i++) {
    const src = g.inList[i];
    const x = g.outCur[(src >> 2) * PORTS + (src & 3)];
    if (x > v) v = x;
  }
  return v;
}

function evalLogic(g: Graph, n: number): void {
  const o = n * PORTS;
  switch (g.type[n]) {
    case NodeType.And:
      g.outNext[o] = inputValue(g, n, 0) > 0 && inputValue(g, n, 1) > 0 ? 1 : 0;
      break;
    case NodeType.Or:
      g.outNext[o] = inputValue(g, n, 0) > 0 || inputValue(g, n, 1) > 0 ? 1 : 0;
      break;
    case NodeType.Not:
      g.outNext[o] = inputValue(g, n, 0) > 0 ? 0 : 1;
      break;
    case NodeType.Delay: {
      // state0 = ticks left before the pending value is released, state1 = pending value.
      const v = inputValue(g, n, 0) > 0 ? 1 : 0;
      if (v !== g.state[n * 2 + 1]) {
        g.state[n * 2 + 1] = v;
        g.state[n * 2] = Math.max(1, g.param[n * 2]);
      }
      if (g.state[n * 2] > 0) {
        if (--g.state[n * 2] === 0) g.outNext[o] = g.state[n * 2 + 1];
        else markDirty(g, n);
      }
      break;
    }
    case NodeType.Timer: {
      const run = !inputWired(g, n, 0) || inputValue(g, n, 0) > 0;
      if (run) {
        const period = Math.max(2, g.param[n * 2]);
        g.outNext[o] = g.state[n * 2] < period / 2 ? 1 : 0;
        g.state[n * 2] = (g.state[n * 2] + 1) % period;
        markDirty(g, n);
      } else {
        g.state[n * 2] = 0;
        g.outNext[o] = 0;
      }
      break;
    }
  }
}

/** Publishes a node's new outputs and schedules whatever is wired to the ones that changed. */
function commit(g: Graph, n: number): void {
  const o = n * PORTS;
  for (let p = 0; p < PORTS; p++) {
    const v = g.outNext[o + p];
    if (v === g.outCur[o + p]) continue;
    g.outCur[o + p] = v;
    for (let i = g.outStart[o + p]; i < g.outStart[o + p + 1]; i++) markDirty(g, g.outList[i] >> 2);
  }
}

/** Advances the whole graph by one logic tick. Does not allocate. */
export function stepGraph(g: Graph, host: LogicHost): void {
  if (g.csrStale) rebuildCsr(g);

  // Take this tick's work list; nodes re-queued during evaluation go to the next one.
  const work = g.nextQueue;
  const workLen = g.nextLen;
  g.nextQueue = g.queue;
  g.nextLen = 0;
  g.queue = work;
  g.queueLen = workLen;
  for (let i = 0; i < workLen; i++) g.queued[work[i]] = 0;

  // 1. Devices publish what they sense.
  for (let n = 1; n < g.high; n++) {
    if (g.type[n] === NodeType.Device) host.sense(g.entity[n], n, g.outNext, n * PORTS);
  }
  // 2. Logic nodes whose inputs changed compute their next outputs from current values.
  for (let i = 0; i < workLen; i++) {
    if (g.type[work[i]] > NodeType.Device) evalLogic(g, work[i]);
  }
  g.lastEvals = workLen;
  // 3. Everything flips over at once.
  for (let n = 1; n < g.high; n++) {
    if (g.type[n] === NodeType.Device) commit(g, n);
  }
  for (let i = 0; i < workLen; i++) commit(g, work[i]);
  // 4. Devices act on the values now visible at their inputs.
  for (let n = 1; n < g.high; n++) {
    if (g.type[n] === NodeType.Device) host.drive(g.entity[n], n);
  }
}
