import { SaveData, deserialize, serialize } from '../core/save';
import { Sim } from '../core/sim';

const SAVE_KEY = 'scrapline.save.v1';
const PREFS_KEY = 'scrapline.prefs.v1';

/**
 * Browser persistence. localStorage is used on purpose: it is synchronous, so
 * a save made while the app is being backgrounded is guaranteed to finish.
 * Every access is guarded because storage can be unavailable or full.
 */
export function loadGame(): Sim | null {
  try {
    const raw = localStorage.getItem(SAVE_KEY);
    return raw ? deserialize(JSON.parse(raw) as SaveData) : null;
  } catch {
    return null;
  }
}

export function saveGame(sim: Sim): boolean {
  try {
    localStorage.setItem(SAVE_KEY, JSON.stringify(serialize(sim)));
    return true;
  } catch {
    return false;
  }
}

export function clearGame(): void {
  try {
    localStorage.removeItem(SAVE_KEY);
  } catch {
    /* nothing to clear */
  }
}

export interface Prefs {
  sensitivity: number;
}

export function loadPrefs(): Prefs {
  const prefs: Prefs = { sensitivity: 1 };
  try {
    const raw = JSON.parse(localStorage.getItem(PREFS_KEY) ?? 'null') as Partial<Prefs> | null;
    if (raw && typeof raw.sensitivity === 'number' && raw.sensitivity >= 0.4 && raw.sensitivity <= 2) {
      prefs.sensitivity = raw.sensitivity;
    }
  } catch {
    /* defaults */
  }
  return prefs;
}

export function savePrefs(prefs: Prefs): void {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  } catch {
    /* not persisted */
  }
}
