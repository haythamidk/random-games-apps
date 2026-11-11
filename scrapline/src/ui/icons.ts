import { Item, ItemId } from '../core/items';

/** Inner SVG markup per item, drawn on a 24×24 grid. No image assets are loaded. */
const ICONS: Record<number, string> = {
  [Item.Wood]:
    '<rect x="3" y="8" width="18" height="8" rx="4" fill="#8a6a4c"/><ellipse cx="18" cy="12" rx="3" ry="4" fill="#c9a77c"/><path d="M6 11h7M7 14h5" stroke="#5f4732" stroke-width="1.2" stroke-linecap="round"/>',
  [Item.Stone]:
    '<path d="M4 15l3-8 7-2 6 6-2 7-10 1z" fill="#8d979c"/><path d="M7 7l4 5 9-1M11 12l-1 7" stroke="#5c666c" stroke-width="1.1" fill="none"/>',
  [Item.IronOre]:
    '<path d="M4 15l3-8 7-2 6 6-2 7-10 1z" fill="#55606a"/><circle cx="9" cy="11" r="1.8" fill="#c97b4f"/><circle cx="14" cy="15" r="1.5" fill="#c97b4f"/><circle cx="15" cy="9" r="1.2" fill="#c97b4f"/>',
  [Item.Coal]:
    '<path d="M4 15l3-8 7-2 6 6-2 7-10 1z" fill="#1d2226"/><path d="M8 9l4 3 6-1" stroke="#4a545c" stroke-width="1.2" fill="none"/>',
  [Item.IronIngot]:
    '<path d="M6 9h12l3 8H3z" fill="#aab6bd"/><path d="M6 9h12l-1.2 3H7.2z" fill="#d5dde1"/>',
  [Item.Gear]:
    '<circle cx="12" cy="12" r="7" fill="none" stroke="#aab6bd" stroke-width="4" stroke-dasharray="3.2 2.3"/><circle cx="12" cy="12" r="5" fill="#aab6bd"/><circle cx="12" cy="12" r="2" fill="#26333a"/>',
  [Item.Circuit]:
    '<rect x="4" y="5" width="16" height="14" rx="2" fill="#2f7d6d"/><path d="M7 9h5v4h5M7 15h4" stroke="#b8f0d8" stroke-width="1.3" fill="none"/><circle cx="17" cy="13" r="1.3" fill="#f2a93b"/>',
  [Item.Axe]:
    '<path d="M6 20L16 6" stroke="#8a6a4c" stroke-width="2.4" stroke-linecap="round"/><path d="M13 4l6 2 1 6-5-1z" fill="#aab6bd"/>',
  [Item.Pick]:
    '<path d="M6 20L15 8" stroke="#8a6a4c" stroke-width="2.4" stroke-linecap="round"/><path d="M7 6c5-3 10-2 13 3-4-2-8-2-12 0z" fill="#aab6bd"/>',
  [Item.Ration]:
    '<path d="M7 5h10l1 3-1 11H7L6 8z" fill="#d9c7a3"/><path d="M6.4 8h11.2" stroke="#9b8660" stroke-width="1.2"/><path d="M12 11v5M9.5 13.5h5" stroke="#ef6f5e" stroke-width="1.8"/>',
  [Item.Cell]:
    '<rect x="7" y="6" width="10" height="14" rx="2" fill="#26333a" stroke="#aab6bd" stroke-width="1.4"/><rect x="10" y="3.5" width="4" height="2.5" fill="#aab6bd"/><path d="M13 9l-3 4.5h4L11 18" stroke="#5fd0c5" stroke-width="1.6" fill="none" stroke-linejoin="round"/>',
  [Item.KitWorkbench]:
    '<path d="M3 9h18v3H3z" fill="#bfae90"/><path d="M5 12v8M19 12v8" stroke="#55646d" stroke-width="2.2"/><path d="M5 16h14" stroke="#7f8d95" stroke-width="1.6"/><rect x="14" y="5.5" width="4" height="3.5" fill="#f2a93b"/>',
  [Item.KitCrate]:
    '<rect x="4" y="9" width="16" height="11" rx="1.2" fill="#3f525c"/><rect x="3" y="6" width="18" height="4" rx="1" fill="#4f9c93"/><rect x="10" y="12.5" width="4" height="2" fill="#d6dbd0"/>',
  [Item.KitWall]:
    '<rect x="5" y="4" width="14" height="16" fill="#5d6b73"/><rect x="5" y="3" width="14" height="2.5" fill="#d6dbd0"/><rect x="5" y="18" width="14" height="3" fill="#36424d"/><path d="M12 6v12" stroke="#4b5860" stroke-width="1"/>',
  [Item.KitCampfire]:
    '<path d="M12 4c1 3 4 4.5 4 8a4 4 0 01-8 0c0-1.8 1-3 2-4 .2 1.3.8 2 1.4 2.3C11.2 8.5 11.3 6 12 4z" fill="#f2a93b"/><path d="M5 19l14-3M5 16l14 3" stroke="#8a6a4c" stroke-width="2.2" stroke-linecap="round"/>',
};

export function iconSvg(id: ItemId): string {
  const inner = ICONS[id] ?? '<rect x="5" y="5" width="14" height="14" rx="2" fill="#6b7780"/>';
  return `<svg viewBox="0 0 24 24" aria-hidden="true">${inner}</svg>`;
}
