# Scrapline

Android-first, first-person survival and automation game. Design and plan are in `docs/`.

```bash
npm install
```

```bash
npm run dev
```

```bash
npm test
```

```bash
npm run build && npm run smoke
```

`npm run smoke` opens the production build in a phone-sized headless browser, drives it with synthetic touches and writes screenshots to `smoke/shots/`.

Controls: left thumb anywhere on the left side moves, right thumb drags to look, the ringed button is the action button, the chevron jumps. Tap a hotbar slot to select it, swipe up on the hotbar or tap the bag button to open the bag. A three-finger tap shows the performance overlay.
