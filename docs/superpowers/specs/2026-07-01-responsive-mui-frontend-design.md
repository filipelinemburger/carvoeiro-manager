# Responsive Frontend + Material UI Conversion — Design

**Date:** 2026-07-01
**Status:** Approved (design)
**Scope:** Frontend only (`frontend/`). No backend, routing, or feature changes.

## Problem

The app is not responsive and most screens are built with raw HTML + inline
styles + hand-rolled `cec-*` CSS classes. On small screens:

- The header nav crams 8 links + "Avançar" + back arrow + gear into a
  `flex-wrap: nowrap; overflow: hidden` bar, so links are clipped/cut off.
- Data-dense tables (Squad has 10 columns; standings and finances tables)
  overflow narrow viewports.

MUI (`@mui/material` v9) is already installed and wired up via `ThemeProvider`
in `main.tsx`, and one page — `features/transfer/TransferMarketPage.tsx` — is
already fully MUI and responsive. That page is the reference pattern for the
rest of this work.

## Goals

1. Every screen renders and is usable on phone, tablet, and desktop widths.
2. All raw-HTML pages/components are rebuilt with MUI components, matching the
   existing `TransferMarketPage` conventions.
3. Existing test suite stays green (accessible names preserved).

## Non-Goals

- No backend, API, or route changes.
- No new features or screens.
- No brand/palette redesign — keep the Criciúma yellow/black/green identity and
  the stadium body background.

## Decisions (from brainstorming)

- **Coverage:** full conversion — all ~14 raw-HTML files, plus the shared
  Layout and Collapsible.
- **Mobile nav:** hamburger `IconButton` → temporary `Drawer` on `< md`; full
  inline nav on `>= md`.
- **Tables on mobile:** stack each row into an MUI `Card` below `md`; keep the
  `Table` on `>= md`.
- **Icons:** add `@mui/icons-material` (standard MUI companion) for
  `MenuIcon`, `SettingsIcon`, `ArrowBackIcon`.
- **Collapsible:** convert to MUI `Accordion` (preserves the `aria-expanded`
  toggle button + title text the tests rely on).

## Approach

**Foundation-first, then page-by-page.** Build shared primitives once, then
convert each page reusing them. This keeps styling consistent and lets each
page be verified independently.

### Foundation

1. **Dependency:** add `@mui/icons-material` to `frontend/package.json`.
2. **Theme** (`muiTheme.ts`): keep the palette; add responsive component
   defaults (`Card`, `Paper`, `Table`, `Container`). Keep **no root
   `CssBaseline`** so the stadium `body` background in `theme.css` survives.
3. **`theme.css`:** keep the `body` background + `#root` flex rules; retire each
   `.cec-*` class as its last consumer is converted.
4. **`useIsMobile()` hook:** wraps `useMediaQuery(theme.breakpoints.down('md'))`.
   In jsdom `window.matchMedia` is undefined, so MUI returns `false` → tests
   render the desktop `Table` view and stay green. No test-time mock needed.

### Layout (core responsiveness fix)

Rewrite `components/Layout.tsx` as an MUI `AppBar` / `Toolbar`:

- **Desktop (`>= md`):** full inline nav — section links (`NavLink` styled as
  MUI `Button`s) + "Avançar".
- **Mobile (`< md`):** hamburger `IconButton` opens a temporary `Drawer`
  listing all sections; "Avançar" remains reachable in the toolbar.
- Gear menu → MUI `Menu` + `MenuItem` (replaces the hand-rolled outside-click
  dropdown; MUI handles dismissal).
- Back arrow → `IconButton` with `ArrowBackIcon`.
- Advance-block banner → MUI `Alert` (keeps `role="status"` and the adjust/see
  links).
- Advance error toast → MUI `Snackbar` + `Alert` (keeps `role="alert"`).
- Content region → responsive `Container maxWidth="md"` + `Paper` (replaces
  `.cec-content-panel`).

Preserve all existing logic verbatim: save-game resolution, advance gating
(`advanceDisabled` / `advanceBlockReason`), New Career confirm, logout.

### Pages

All converted to MUI, matching `TransferMarketPage` conventions. Mobile card
views render only under `useIsMobile()`.

| File | Conversion |
|---|---|
| `auth/LoginPage.tsx` | `Container` + `Paper` + `TextField` + `Button` + `Alert` + `Link` |
| `auth/RegisterPage.tsx` | same as Login |
| `features/career/DashboardPage.tsx` | `Typography` + `Stack` + `Card` stat blocks |
| `components/Collapsible.tsx` | MUI `Accordion` / `AccordionSummary` / `AccordionDetails` |
| `features/squad/SquadPage.tsx` | desktop `Table`; mobile stacked `Card` per player (checkbox, role `Select`, renew `Button`, suspension/injury `Chip`s); validation → `Alert`; formation → `Select` |
| `features/squad/TacticsPanel.tsx` | MUI form controls (`Select` / `Slider` / `FormControl`) |
| `features/competition/TablePage.tsx` | responsive standings: desktop `Table`, mobile `Card`s |
| `features/finance/FinancesPage.tsx` | responsive finances: desktop `Table`, mobile `Card`s |
| `features/news/NewsPage.tsx` | `List` / `Card` |
| `features/season/SeasonSummaryPage.tsx` | `Card` / `Typography` / `Stack` |
| `features/season/SeasonHistoryPage.tsx` | `Card` / `Table` (responsive) |
| `features/contract/FreeAgentsPage.tsx` | `Card` list, like `TransferMarketPage` |
| `features/match/AdvanceEventCard.tsx` | MUI `Card` / `Dialog` |

`App.tsx` is a thin routing shell; adjust only if needed for the new Layout
container.

## Responsive Strategy Details

- **Breakpoint:** single `md` split between "compact" (phone/small tablet) and
  "full" (desktop) layouts, via theme breakpoints and `sx` responsive props
  (e.g. `direction={{ xs: 'column', md: 'row' }}`), matching `TransferMarketPage`.
- **Table → cards:** driven by `useIsMobile()` (JS), not dual-rendered CSS, so
  the DOM never contains duplicate controls (avoids "multiple elements with same
  role/label" test failures) and jsdom defaults to the desktop table.
- **Touch targets:** MUI default sizing (`size="small"` where density matters,
  matching the reference page).

## Testing

- Run `npm test` after the foundation and after each page/area.
- Accessible names — labels, roles, `aria-label`s, visible text — preserved
  throughout so existing queries keep matching.
- Mobile card branches are gated behind `useIsMobile()`, which is `false` in
  jsdom, so the current suite continues to exercise the desktop layout. No new
  tests are strictly required; add targeted responsive tests only if a
  conversion changes accessible structure.

## Risks

- **Accordion vs. Collapsible test coupling:** `Accordion` renders the title in
  a button with `aria-expanded` — matches current queries. Verify the Squad and
  season tests that toggle sections still pass.
- **Gear `Menu` focus/portal:** MUI `Menu` portals to `body`; ensure the
  "Configurações" button name and "Sair" / "Nova Carreira" items keep their
  accessible text for the Layout tests.
- **`@mui/icons-material` install:** adds a dependency + install step; if the
  user prefers zero new deps, fall back to emoji glyphs inside `IconButton`s.

## Rollout

Foundation → Layout → auth → Dashboard/Collapsible → Squad/Tactics →
Table/Finances → News/Season/FreeAgents/AdvanceEventCard. Verify tests at each
step; each area is independently reviewable.
