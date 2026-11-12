import { EntityKind } from '../core/entities';
import { count } from '../core/inventory';
import { Item } from '../core/items';
import { NodeType, wireDstNode } from '../core/logic/graph';
import { Sim } from '../core/sim';

interface Step {
  text: string;
  done(sim: Sim, has: (kind: number) => boolean): boolean;
}

/** The slice in order. Each step is judged from the world itself, so it survives saves and skipping ahead. */
const STEPS: Step[] = [
  {
    text: 'Aim at deadwood or stone and hold the ringed button to gather',
    done: (sim) => count(sim.inventory, Item.Wood) >= 3 && count(sim.inventory, Item.Stone) >= 3,
  },
  {
    text: 'Open the bag and craft an axe and a pick',
    done: (sim) => count(sim.inventory, Item.Axe) > 0 && count(sim.inventory, Item.Pick) > 0,
  },
  {
    text: 'Craft a campfire kit, select it and place it. Warmth only comes back near heat',
    done: (_sim, has) => has(EntityKind.Campfire),
  },
  {
    text: 'Craft and place a workbench, then press Use on it',
    done: (_sim, has) => has(EntityKind.Workbench),
  },
  {
    text: 'Find rust or black stained ground and pick ore and coal from the boulders around it',
    done: (sim) => count(sim.inventory, Item.IronOre) > 0 && count(sim.inventory, Item.Coal) > 0,
  },
  {
    text: 'Build a smelter at the bench, place it, and put ore and coal inside',
    done: (sim, has) => has(EntityKind.Smelter) && count(sim.inventory, Item.IronIngot) + count(sim.inventory, Item.Gear) + count(sim.inventory, Item.Circuit) > 0,
  },
  {
    text: 'Automate: a drill on stained ground, then belts leading away from it into a crate or the smelter',
    done: (_sim, has) => has(EntityKind.Drill) && has(EntityKind.Belt),
  },
  {
    text: 'Build a Linker, a sensor and a turret. Place both, then press the Linker on the sensor and then on the turret',
    done: (sim) => {
      const g = sim.graph;
      for (let i = 0; i < g.wireCount; i++) {
        const n = wireDstNode(g.wires[i]);
        if (g.type[n] === NodeType.Device && sim.entities.kind[g.entity[n]] === EntityKind.Turret) return true;
      }
      return false;
    },
  },
  {
    text: 'Stay near your turret and hold out until dawn',
    done: (sim) => sim.nights > 0,
  },
];

const FINISHED = 'You held a night. Keep building: try a NOT or a Timer on the circuit board';

/** One line of "what next", shown at the top of the screen. */
export class Guide {
  private el: HTMLElement;
  private shown = '';
  private lastCheck = -1;
  private present = new Uint8Array(32);

  constructor(root: HTMLElement) {
    this.el = document.createElement('div');
    this.el.className = 'guide';
    root.appendChild(this.el);
  }

  /** Re-evaluates about once a second; the checks are cheap but there is no need for more. */
  sync(sim: Sim): void {
    const second = Math.floor(sim.tick / 20);
    if (second === this.lastCheck) return;
    this.lastCheck = second;

    const es = sim.entities;
    this.present.fill(0);
    for (let id = 1; id < es.high; id++) this.present[es.kind[id]] = 1;
    const has = (kind: number) => this.present[kind] === 1;

    // The current step is the one after the furthest step already achieved.
    let next = 0;
    for (let i = 0; i < STEPS.length; i++) if (STEPS[i].done(sim, has)) next = i + 1;
    const text = next < STEPS.length ? STEPS[next].text : FINISHED;
    if (text !== this.shown) {
      this.el.textContent = text;
      this.shown = text;
    }
  }
}
