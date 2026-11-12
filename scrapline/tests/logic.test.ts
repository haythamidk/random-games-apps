import { describe, expect, it } from 'vitest';
import {
  Graph, LogicHost, MAX_WIRES, NodeType, PORTS, addNode, addWire, createGraph, findWire, inputValue, inputWired,
  removeNode, removeWireAt, stepGraph,
} from '../src/core/logic/graph';

/** Test host: device outputs come from `sensors`, and device inputs are recorded in `driven`. */
function makeHost(g: Graph) {
  const sensors = new Map<number, number[]>();
  const driven = new Map<number, number>();
  const host: LogicHost = {
    sense(entity, _node, out, base) {
      const v = sensors.get(entity) ?? [0, 0, 0, 0];
      for (let p = 0; p < PORTS; p++) out[base + p] = v[p] ?? 0;
    },
    drive(entity, node) {
      driven.set(entity, inputWired(g, node, 0) ? inputValue(g, node, 0) : -1);
    },
  };
  return { host, sensors, driven };
}

const out = (g: Graph, n: number, p = 0) => g.outCur[n * PORTS + p];

describe('graph structure', () => {
  it('adds, finds and removes wires, refusing duplicates and self-wires', () => {
    const g = createGraph();
    const a = addNode(g, NodeType.Or);
    const b = addNode(g, NodeType.Not);
    expect(addWire(g, a, 0, b, 0)).toBe(true);
    expect(addWire(g, a, 0, b, 0)).toBe(false);
    expect(addWire(g, a, 0, a, 1)).toBe(false);
    expect(findWire(g, a, 0, b, 0)).toBe(0);
    expect(inputWired(g, b, 0)).toBe(true);
    removeWireAt(g, 0);
    expect(g.wireCount).toBe(0);
    expect(inputWired(g, b, 0)).toBe(false);
  });

  it('reuses freed node ids and drops a removed node\'s wires', () => {
    const g = createGraph();
    const a = addNode(g, NodeType.Or);
    const b = addNode(g, NodeType.Not);
    const c = addNode(g, NodeType.And);
    addWire(g, a, 0, b, 0);
    addWire(g, b, 0, c, 0);
    addWire(g, a, 0, c, 1);
    removeNode(g, b);
    expect(g.wireCount).toBe(1);
    expect(findWire(g, a, 0, c, 1)).toBe(0);
    expect(addNode(g, NodeType.Delay)).toBe(b);
  });

  it('keeps adjacency correct with fan-out and fan-in across many ports', () => {
    const g = createGraph();
    const { host, sensors } = makeHost(g);
    const s1 = addNode(g, NodeType.Device, 1);
    const s2 = addNode(g, NodeType.Device, 2);
    const or = addNode(g, NodeType.Or);
    const and = addNode(g, NodeType.And);
    addWire(g, s1, 0, or, 0);
    addWire(g, s2, 1, or, 0); // fan-in on one port
    addWire(g, s1, 0, and, 0); // fan-out from one port
    addWire(g, s2, 1, and, 1);
    sensors.set(1, [0]);
    sensors.set(2, [0, 7]);
    stepGraph(g, host);
    expect(inputValue(g, or, 0)).toBe(7); // highest source wins
    stepGraph(g, host);
    expect(out(g, or)).toBe(1);
    expect(out(g, and)).toBe(0);
    sensors.set(1, [1]);
    stepGraph(g, host);
    stepGraph(g, host);
    expect(out(g, and)).toBe(1);
  });

  it('refuses wires past capacity', () => {
    const g = createGraph();
    const a = addNode(g, NodeType.Or);
    const b = addNode(g, NodeType.Or);
    g.wireCount = MAX_WIRES;
    expect(addWire(g, a, 0, b, 0)).toBe(false);
  });
});

describe('logic nodes', () => {
  function gate(type: number, a: number, b: number): number {
    const g = createGraph();
    const { host, sensors } = makeHost(g);
    const sa = addNode(g, NodeType.Device, 1);
    const sb = addNode(g, NodeType.Device, 2);
    const n = addNode(g, type);
    addWire(g, sa, 0, n, 0);
    addWire(g, sb, 0, n, 1);
    sensors.set(1, [a]);
    sensors.set(2, [b]);
    stepGraph(g, host);
    stepGraph(g, host);
    return out(g, n);
  }

  it('AND and OR follow their truth tables', () => {
    expect([gate(NodeType.And, 0, 0), gate(NodeType.And, 1, 0), gate(NodeType.And, 0, 1), gate(NodeType.And, 1, 1)]).toEqual([0, 0, 0, 1]);
    expect([gate(NodeType.Or, 0, 0), gate(NodeType.Or, 1, 0), gate(NodeType.Or, 0, 1), gate(NodeType.Or, 1, 1)]).toEqual([0, 1, 1, 1]);
  });

  it('NOT inverts, and outputs 1 with nothing wired', () => {
    const g = createGraph();
    const { host, sensors } = makeHost(g);
    const s = addNode(g, NodeType.Device, 1);
    const n = addNode(g, NodeType.Not);
    stepGraph(g, host);
    expect(out(g, n)).toBe(1);
    addWire(g, s, 0, n, 0);
    sensors.set(1, [1]);
    stepGraph(g, host);
    stepGraph(g, host);
    expect(out(g, n)).toBe(0);
  });

  it('a sensor wired straight to an actuator acts in the same tick; each logic node adds one tick', () => {
    const g = createGraph();
    const { host, sensors, driven } = makeHost(g);
    const s = addNode(g, NodeType.Device, 1);
    const direct = addNode(g, NodeType.Device, 2);
    const not1 = addNode(g, NodeType.Not);
    const not2 = addNode(g, NodeType.Not);
    const viaTwo = addNode(g, NodeType.Device, 3);
    addWire(g, s, 0, direct, 0);
    addWire(g, s, 0, not1, 0);
    addWire(g, not1, 0, not2, 0);
    addWire(g, not2, 0, viaTwo, 0);
    for (let i = 0; i < 4; i++) stepGraph(g, host);
    expect(driven.get(3)).toBe(0);
    sensors.set(1, [1]);
    stepGraph(g, host);
    expect(driven.get(2)).toBe(1);
    expect(driven.get(3)).toBe(0);
    stepGraph(g, host);
    expect(driven.get(3)).toBe(0);
    stepGraph(g, host);
    expect(driven.get(3)).toBe(1);
  });

  it('Delay releases a change after its wait and swallows shorter blips', () => {
    const g = createGraph();
    const { host, sensors } = makeHost(g);
    const s = addNode(g, NodeType.Device, 1);
    const d = addNode(g, NodeType.Delay);
    g.param[d * 2] = 4;
    addWire(g, s, 0, d, 0);
    stepGraph(g, host);
    sensors.set(1, [1]);
    const seen: number[] = [];
    for (let i = 0; i < 7; i++) {
      stepGraph(g, host);
      seen.push(out(g, d));
    }
    // Step 1 publishes the sensor; the delay then counts 4 ticks before its output rises.
    expect(seen).toEqual([0, 0, 0, 0, 1, 1, 1]);

    sensors.set(1, [0]);
    stepGraph(g, host);
    stepGraph(g, host);
    sensors.set(1, [1]); // back up before the wait elapsed
    for (let i = 0; i < 8; i++) {
      stepGraph(g, host);
      expect(out(g, d)).toBe(1);
    }
  });

  it('Timer makes a square wave, and stops while its run input is low', () => {
    const g = createGraph();
    const { host, sensors } = makeHost(g);
    const t = addNode(g, NodeType.Timer);
    g.param[t * 2] = 4;
    const wave: number[] = [];
    for (let i = 0; i < 8; i++) {
      stepGraph(g, host);
      wave.push(out(g, t));
    }
    expect(wave).toEqual([1, 1, 0, 0, 1, 1, 0, 0]);

    const s = addNode(g, NodeType.Device, 1);
    addWire(g, s, 0, t, 0);
    sensors.set(1, [0]);
    for (let i = 0; i < 6; i++) stepGraph(g, host);
    expect(out(g, t)).toBe(0);
    expect(g.lastEvals).toBe(0);
  });

  it('a feedback loop latches: OR fed by itself stays on after the trigger goes away', () => {
    const g = createGraph();
    const { host, sensors } = makeHost(g);
    const s = addNode(g, NodeType.Device, 1);
    const or = addNode(g, NodeType.Or);
    const hold = addNode(g, NodeType.Or);
    addWire(g, s, 0, or, 0);
    addWire(g, or, 0, hold, 0);
    addWire(g, hold, 0, or, 1);
    for (let i = 0; i < 3; i++) stepGraph(g, host);
    expect(out(g, or)).toBe(0);
    sensors.set(1, [1]);
    for (let i = 0; i < 4; i++) stepGraph(g, host);
    sensors.set(1, [0]);
    for (let i = 0; i < 10; i++) stepGraph(g, host);
    expect(out(g, or)).toBe(1);
  });

  it('evaluates nothing while inputs are steady', () => {
    const g = createGraph();
    const { host, sensors } = makeHost(g);
    const s = addNode(g, NodeType.Device, 1);
    let prev = s;
    for (let i = 0; i < 20; i++) {
      const n = addNode(g, NodeType.Not);
      addWire(g, prev, 0, n, 0);
      prev = n;
    }
    for (let i = 0; i < 30; i++) stepGraph(g, host);
    expect(g.lastEvals).toBe(0);
    sensors.set(1, [1]);
    stepGraph(g, host);
    stepGraph(g, host);
    expect(g.lastEvals).toBe(1); // only the node whose input changed
    for (let i = 0; i < 30; i++) stepGraph(g, host);
    expect(g.lastEvals).toBe(0);
  });
});
