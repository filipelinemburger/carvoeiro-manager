# Squad Screen Tabs + Táticas de Jogo Pitch View — Design

**Date:** 2026-07-07
**Status:** Approved

## Goal

Restructure the squad (escalação) screen into two tabs — "Plantel" (the current squad
list) and "Táticas de jogo" (a Championship-Manager-style view: expandable tactics
controls above the read-only pitch) — show friendly Portuguese names for the tactic
values everywhere, and offer all 16 formations in the in-match tactics dialog.

## Background (from the codebase)

- `frontend/src/features/squad/SquadPage.tsx` renders two `Collapsible` sections:
  - "Escalação": formation select, "Selecionar melhores"/"Limpar", helper line +
    validation alerts, a read-only `<Pitch>` inline, the player table (desktop) /
    cards (mobile), "Salvar escalação".
  - "Tática": `TacticsPanel` — Mentalidade/Pressão/Marcação `NativeSelect`s showing
    RAW enum values (`DEFENSIVE`, `BALANCED`, `ATTACKING`, `LOW`, `MEDIUM`, `HIGH`,
    `ZONAL`, `MAN_TIGHT`), cobradores (pênaltis/faltas/escanteios), "Salvar tática".
- Lineup state (`selected`, `bench`, `roles`, `formation`) lives in `SquadPage`, so a
  tab switch cannot lose unsaved edits as long as tabs are in-page.
- The shared `Pitch` component (`features/pitch/`) exists since the pitch-view phase 1
  (2026-07-06 design), draws the formation with kit colors and player surnames, and
  `pitchSlots.ts` exports `FORMATION_CODES` — the 16-formation catalogue. `SquadPage`
  already uses `FORMATION_CODES`; the backend `Formation` enum has all 16.
- `frontend/src/features/match/MatchActionsDialog.tsx` (in-match tactics tab) has a
  STALE hard-coded 5-formation list (`4-4-2, 4-3-3, 3-5-2, 4-2-3-1, 5-3-2`) and its
  own friendly-label constants (`Zonal`, `Por homem`) that drift from this design's
  chosen labels.

## Decisions (user-approved)

1. Tab 1 **"Plantel"** = current escalação content unchanged, **minus the inline
   pitch** (the pitch moves to tab 2; no duplication).
2. Tab 2 **"Táticas de jogo"** = expandable tactics controls **above** the pitch. The
   standalone "Tática" collapsible section disappears; `TacticsPanel` lives only in
   tab 2.
3. Friendly labels: Mentalidade **Defensiva / Equilibrada / Ofensiva**; Pressão
   **Baixa / Média / Alta**; Marcação **Por zona / Individual**.
4. Match screen (addendum): the in-match formation select offers **all 16 formations**
   via `FORMATION_CODES`, and its tactic labels come from the same shared label
   module as the squad screen.

## UI structure

```
SquadPage
├─ <Tabs value={tab}> [Plantel] [Táticas de jogo]   (MUI Tabs, in-page state)
├─ Tab "Plantel":
│    formation select · Selecionar melhores / Limpar · helper + alerts
│    player table (desktop) / cards (mobile) · Salvar escalação
└─ Tab "Táticas de jogo":
     Collapsible "Instruções da equipe" (default expanded)
       └─ TacticsPanel (friendly labels; unchanged behavior otherwise)
     <Pitch home={pitchTeam} />   (read-only; formation always fully drawn,
                                   selected starters shown with names + kit colors)
```

- `PlayerDetails` dialog, renew flow, and both save snackbars stay page-level
  (outside the tabs) exactly as today.
- Tab state is component state (`useState`), NOT router state — deep-linking was
  considered and rejected (YAGNI; in-page tabs preserve unsaved lineup edits).
- The helper line, validation alerts, auto-pick and clear buttons stay in "Plantel"
  only. The formation select stays in "Plantel" (it drives the squad-building caps);
  the pitch in tab 2 reflects whatever formation/XI is currently selected, saved or
  not.
- Mobile: same tabs; MUI `Tabs` handles narrow widths (2 tabs fit without scroll).

## Shared tactic labels

New module `frontend/src/features/squad/tacticsLabels.ts`:

- `MENTALITY_LABEL: Record<Mentality, string>` — DEFENSIVE→"Defensiva",
  BALANCED→"Equilibrada", ATTACKING→"Ofensiva"
- `PRESSING_LABEL: Record<Pressing, string>` — LOW→"Baixa", MEDIUM→"Média",
  HIGH→"Alta"
- `MARKING_LABEL: Record<Marking, string>` — ZONAL→"Por zona",
  MAN_TIGHT→"Individual"

Consumers:
- `TacticsPanel` — `<option>` text uses the label; the option **value** stays the
  enum (wire format unchanged; no API/backend change).
- `MatchActionsDialog` — its local `MENTALITIES`/`PRESSINGS`/`MARKINGS` constants are
  rebuilt from the shared maps (same `{value, label}` shape it already uses), and its
  local `FORMATIONS` constant is replaced by `FORMATION_CODES` from
  `features/pitch/pitchSlots`.

## Out of scope

- No interactive pitch editing (tap/drag) — that is pitch-view Phase 3, unchanged.
- No backend changes of any kind (labels are display-only; formations already exist
  in the backend enum).
- No changes to the live-match pitch rendering.

## Testing

- `SquadPage.test.tsx`:
  - Both tabs render; "Plantel" is the initial tab.
  - "Plantel" shows the player table and NO pitch; "Táticas de jogo" shows the
    tactics controls and the pitch, tactics above pitch.
  - Switching to "Táticas de jogo" and back preserves an unsaved selection (select a
    player, switch tabs twice, assert still selected).
  - "Salvar escalação" stays in "Plantel"; "Salvar tática" lives in tab 2.
- `TacticsPanel.test.tsx`: options display friendly labels; the submitted payload
  still carries enum values (existing save test extended).
- `MatchActionsDialog` tests: the formation select offers all 16 `FORMATION_CODES`;
  mentality/pressing/marking labels equal the shared maps' values.
- Full frontend suite green.
