import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { MatchActionsDialog } from './MatchActionsDialog'
import { FORMATION_CODES } from '../pitch/pitchSlots'
import { MENTALITY_LABEL, GAME_STYLE_LABEL, ATTACK_ZONE_LABEL, PRESSING_LABEL, MARKING_LABEL } from '../squad/tacticsLabels'
import type { LiveEvent, LivePlayer, LiveTactics } from '../../api/types'

const DEFAULT_TACTICS: LiveTactics = { mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' }

function renderTacticsTab(onApply: (payload: unknown) => void = () => {}) {
  render(
    <MatchActionsDialog
      open
      onClose={() => {}}
      initialTab={1}
      onPitch={[]}
      bench={[]}
      events={[]}
      minute={0}
      subsRemaining={3}
      pending={false}
      onApply={onApply}
      currentTactics={DEFAULT_TACTICS}
    />,
  )
}

// Renders the dialog with both tabs reachable, for tests exercising the shared footer
// (summary line / apply-button label) which depends on state from both tabs at once.
function renderDialog({
  initialTab = 0,
  onPitch = [],
  bench = [],
  events = [],
  minute = 0,
  subsRemaining = 3,
  onApply = vi.fn(),
  currentTactics = DEFAULT_TACTICS,
}: Partial<{
  initialTab: 0 | 1
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  events: LiveEvent[]
  minute: number
  subsRemaining: number
  onApply: (payload: unknown) => void
  currentTactics: LiveTactics
}> = {}) {
  render(
    <MatchActionsDialog
      open
      onClose={() => {}}
      initialTab={initialTab}
      onPitch={onPitch}
      bench={bench}
      events={events}
      minute={minute}
      subsRemaining={subsRemaining}
      pending={false}
      onApply={onApply}
      currentTactics={currentTactics}
    />,
  )
  return { onApply }
}

describe('MatchActionsDialog tactics tab', () => {
  it('offers every formation from the shared catalogue', () => {
    renderTacticsTab()
    const formation = screen.getByLabelText('Formação') as HTMLSelectElement
    expect(Array.from(formation.options).map(o => o.value)).toEqual(FORMATION_CODES)
    expect(FORMATION_CODES).toHaveLength(16)
  })

  it('labels tactic options with the shared friendly names', () => {
    renderTacticsTab()
    const mentality = screen.getByLabelText('Mentalidade') as HTMLSelectElement
    expect(Array.from(mentality.options).map(o => o.text)).toEqual(Object.values(MENTALITY_LABEL))
    const gameStyle = screen.getByLabelText('Estilo de jogo') as HTMLSelectElement
    expect(Array.from(gameStyle.options).map(o => o.text)).toEqual(Object.values(GAME_STYLE_LABEL))
    const attackZone = screen.getByLabelText('Onde forçar o jogo') as HTMLSelectElement
    expect(Array.from(attackZone.options).map(o => o.text)).toEqual(Object.values(ATTACK_ZONE_LABEL))
    const pressing = screen.getByLabelText('Pressão') as HTMLSelectElement
    expect(Array.from(pressing.options).map(o => o.text)).toEqual(Object.values(PRESSING_LABEL))
    const marking = screen.getByLabelText('Marcação') as HTMLSelectElement
    expect(Array.from(marking.options).map(o => o.text)).toEqual(Object.values(MARKING_LABEL))
  })

  it('treats a game-style-only change as a tactics change and includes gameStyle in the applied payload', () => {
    const onApply = vi.fn()
    renderTacticsTab(onApply)

    fireEvent.change(screen.getByLabelText('Estilo de jogo'), { target: { value: 'RETRANCA' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar 1 alteração' }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith({
      subs: [],
      tactics: expect.objectContaining({ gameStyle: 'RETRANCA', mentality: 'BALANCED' }),
    })
  })

  it('treats an attack-zone-only change as a tactics change and includes attackZone in the applied payload', () => {
    const onApply = vi.fn()
    renderTacticsTab(onApply)

    fireEvent.change(screen.getByLabelText('Onde forçar o jogo'), { target: { value: 'CENTRO' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar 1 alteração' }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith({
      subs: [],
      tactics: expect.objectContaining({ attackZone: 'CENTRO', mentality: 'BALANCED' }),
    })
  })

  it('changing a tactic field shows era: <friendly label of the previous value>', () => {
    renderTacticsTab()

    fireEvent.change(screen.getByLabelText('Estilo de jogo'), { target: { value: 'RETRANCA' } })

    expect(screen.getByText(`era: ${GAME_STYLE_LABEL.EQUILIBRADO}`)).toBeInTheDocument()
  })

  it("clicking that field's undo restores the original value and hides the indicator", () => {
    renderTacticsTab()

    fireEvent.change(screen.getByLabelText('Estilo de jogo'), { target: { value: 'RETRANCA' } })
    expect(screen.getByText(`era: ${GAME_STYLE_LABEL.EQUILIBRADO}`)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Desfazer alteração em Estilo de jogo' }))

    expect(screen.queryByText(`era: ${GAME_STYLE_LABEL.EQUILIBRADO}`)).not.toBeInTheDocument()
    expect((screen.getByLabelText('Estilo de jogo') as HTMLSelectElement).value).toBe('EQUILIBRADO')
  })
})

// ---------------------------------------------------------------------------
// Substitution tab
// ---------------------------------------------------------------------------

// Minimal builders so each test only states the fields it cares about — mirrors
// livePlayerContext.test.ts's fixtures.
function player(overrides: Partial<LivePlayer> & Pick<LivePlayer, 'playerId'>): LivePlayer {
  return {
    name: 'Jogador', position: 'MID', overall: 70, fitness: 100,
    hobbled: false, onPitch: true, used: false, eligible: true, role: 'MIDFIELDER',
    ...overrides,
  }
}

function event(overrides: Partial<LiveEvent> & Pick<LiveEvent, 'minute' | 'type' | 'home'>): LiveEvent {
  return {
    playerId: 'p1', playerName: 'Jogador', playerInId: null, playerInName: null,
    severity: null, text: '',
    ...overrides,
  }
}

function renderSubTab({
  onPitch = [],
  bench = [],
  events = [],
  minute = 0,
  subsRemaining = 3,
  onApply = vi.fn(),
}: Partial<{
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  events: LiveEvent[]
  minute: number
  subsRemaining: number
  onApply: (payload: unknown) => void
}> = {}) {
  render(
    <MatchActionsDialog
      open
      onClose={() => {}}
      initialTab={0}
      onPitch={onPitch}
      bench={bench}
      events={events}
      minute={minute}
      subsRemaining={subsRemaining}
      pending={false}
      onApply={onApply}
      currentTactics={{ mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' }}
    />,
  )
  return { onApply }
}

// The two selection sections and the pending-queue section each repeat player names (a
// pending selection shows up both in its own list, marked selected, and in the pending row)
// so tests scope queries to one section via these helpers rather than screen.getByText.
function outSection(): HTMLElement {
  return screen.getByText('Selecionar quem sai do campo').parentElement as HTMLElement
}
function inSection(): HTMLElement {
  return screen.getByText('Selecionar quem entra no jogo').parentElement as HTMLElement
}

describe('MatchActionsDialog substitution tab', () => {
  it('a bench player clicked first becomes the pending IN, and clicking an on-pitch player then completes the pair with the same payload as the out-first order', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo' })
    const inP = player({ playerId: 'in1', name: 'Entrando' })
    const onApply = vi.fn()
    renderSubTab({ onPitch: [outP], bench: [inP], onApply })

    fireEvent.click(within(inSection()).getByText('Entrando'))
    fireEvent.click(within(outSection()).getByText('Saindo'))
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar 1 alteração' }))

    expect(onApply).toHaveBeenCalledWith({
      subs: [{ out: outP, in: inP }],
      tactics: null,
    })
  })

  it("a yellow-carded on-pitch player's card shows the yellow card marker", () => {
    const p = player({ playerId: 'p1', name: 'Amarelado' })
    const events: LiveEvent[] = [
      event({ minute: 10, type: 'YELLOW_CARD', home: true, playerId: 'p1', playerName: 'Amarelado' }),
    ]
    renderSubTab({ onPitch: [p], events, minute: 20 })

    expect(screen.getByTitle('Cartão amarelo')).toBeInTheDocument()
  })

  it('a player under 40 fitness shows Exausto', () => {
    const p = player({ playerId: 'p1', name: 'Cansado', fitness: 30 })
    renderSubTab({ onPitch: [p] })

    expect(screen.getByText('Exausto')).toBeInTheDocument()
  })

  it("a starter's card shows the current minute as minutes played", () => {
    const p = player({ playerId: 'p1', name: 'Titular' })
    renderSubTab({ onPitch: [p], minute: 57 })

    expect(screen.getByText("57'")).toBeInTheDocument()
  })

  it('with a pending OUT, the bench lists same-position candidates first and marks them sugerido', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo', position: 'DEF' })
    const benchOther = player({ playerId: 'in1', name: 'ReservaATT', position: 'ATT' })
    const benchSame = player({ playerId: 'in2', name: 'ReservaDEF', position: 'DEF' })
    renderSubTab({ onPitch: [outP], bench: [benchOther, benchSame] })

    fireEvent.click(within(outSection()).getByText('Saindo'))

    const names = within(inSection()).getAllByText(/^Reserva/)
    expect(names[0]).toHaveTextContent('ReservaDEF')

    const sameRow = within(inSection()).getByText('ReservaDEF').closest('div') as HTMLElement
    expect(within(sameRow).getByText('sugerido')).toBeInTheDocument()

    const otherRow = within(inSection()).getByText('ReservaATT').closest('div') as HTMLElement
    expect(within(otherRow).queryByText('sugerido')).not.toBeInTheDocument()
  })

  it('with a pending OUT, the "quem entra" section precedes "quem sai" in the DOM', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo' })
    const inP = player({ playerId: 'in1', name: 'Entrando' })
    renderSubTab({ onPitch: [outP], bench: [inP] })

    fireEvent.click(within(outSection()).getByText('Saindo'))

    const headings = screen.getAllByText(/^Selecionar quem/)
    expect(headings[0]).toHaveTextContent('Selecionar quem entra no jogo')
    expect(headings[1]).toHaveTextContent('Selecionar quem sai do campo')
  })

  it('the remaining-subs counter reflects queued pairs, with singular wording at exactly one remaining', () => {
    const out1 = player({ playerId: 'out1', name: 'Saindo1' })
    const out2 = player({ playerId: 'out2', name: 'Saindo2' })
    const in1 = player({ playerId: 'in1', name: 'Entrando1' })
    const in2 = player({ playerId: 'in2', name: 'Entrando2' })
    renderSubTab({ onPitch: [out1, out2], bench: [in1, in2], subsRemaining: 3 })

    expect(screen.getByText('Você ainda pode fazer 3 substituições.')).toBeInTheDocument()

    fireEvent.click(within(outSection()).getByText('Saindo1'))
    fireEvent.click(within(inSection()).getByText('Entrando1'))
    fireEvent.click(within(outSection()).getByText('Saindo2'))
    fireEvent.click(within(inSection()).getByText('Entrando2'))

    expect(screen.getByText('Você ainda pode fazer 1 substituição.')).toBeInTheDocument()
  })

  it('clicking a pending selection again clears it', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo' })
    renderSubTab({ onPitch: [outP], bench: [] })

    fireEvent.click(within(outSection()).getByText('Saindo'))
    expect(screen.getByLabelText('Cancelar seleção')).toBeInTheDocument()

    fireEvent.click(within(outSection()).getByText('Saindo'))
    expect(screen.queryByLabelText('Cancelar seleção')).not.toBeInTheDocument()
  })

  it('clicking a pending IN selection again clears it (mirrors the pendingOut toggle-off)', () => {
    const inP = player({ playerId: 'in1', name: 'Entrando', onPitch: false })
    renderSubTab({ onPitch: [], bench: [inP] })

    fireEvent.click(within(inSection()).getByText('Entrando'))
    expect(screen.getByLabelText('Cancelar seleção')).toBeInTheDocument()

    fireEvent.click(within(inSection()).getByText('Entrando'))
    expect(screen.queryByLabelText('Cancelar seleção')).not.toBeInTheDocument()
  })

  it('an unused bench player does not show the match clock as its minutes played', () => {
    const benchP = player({ playerId: 'bench1', name: 'ReservaNuncaEntrou', onPitch: false, used: false })
    renderSubTab({ onPitch: [], bench: [benchP], minute: 70 })

    expect(within(inSection()).queryByText("70'")).not.toBeInTheDocument()
  })

  it("a yellow-carded player's card marker exposes an accessible name via aria-label", () => {
    const p = player({ playerId: 'p1', name: 'Amarelado' })
    const events: LiveEvent[] = [
      event({ minute: 10, type: 'YELLOW_CARD', home: true, playerId: 'p1', playerName: 'Amarelado' }),
    ]
    renderSubTab({ onPitch: [p], events, minute: 20 })

    // getByTitle must keep working (existing tests rely on it) alongside the new aria-label.
    expect(screen.getByTitle('Cartão amarelo')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Cartão amarelo' })).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// Shared footer — summary line and apply-button label, driven by state from
// both tabs at once (subs queued on one tab, tactics changed on the other).
// ---------------------------------------------------------------------------

describe('MatchActionsDialog footer', () => {
  it('with no changes, the apply button reads Aplicar and is disabled', () => {
    renderDialog()

    expect(screen.getByRole('button', { name: 'Aplicar' })).toBeDisabled()
  })

  it('one queued substitution makes it read Aplicar 1 alteração', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo' })
    const inP = player({ playerId: 'in1', name: 'Entrando' })
    renderDialog({ onPitch: [outP], bench: [inP] })

    fireEvent.click(within(outSection()).getByText('Saindo'))
    fireEvent.click(within(inSection()).getByText('Entrando'))

    expect(screen.getByRole('button', { name: 'Aplicar 1 alteração' })).toBeInTheDocument()
  })

  it('one queued substitution plus one changed tactic field makes it read Aplicar 2 alterações', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo' })
    const inP = player({ playerId: 'in1', name: 'Entrando' })
    renderDialog({ onPitch: [outP], bench: [inP] })

    fireEvent.click(within(outSection()).getByText('Saindo'))
    fireEvent.click(within(inSection()).getByText('Entrando'))

    fireEvent.click(screen.getByRole('tab', { name: /instruções táticas/i }))
    fireEvent.change(screen.getByLabelText('Estilo de jogo'), { target: { value: 'RETRANCA' } })

    expect(screen.getByRole('button', { name: 'Aplicar 2 alterações' })).toBeInTheDocument()
  })

  it('the summary line reads Nenhuma alteração pendente. when nothing changed', () => {
    renderDialog()

    expect(screen.getByText('Nenhuma alteração pendente.')).toBeInTheDocument()
  })

  it('one queued substitution plus one changed tactic field renders the composed summary text', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo' })
    const inP = player({ playerId: 'in1', name: 'Entrando' })
    renderDialog({ onPitch: [outP], bench: [inP] })

    fireEvent.click(within(outSection()).getByText('Saindo'))
    fireEvent.click(within(inSection()).getByText('Entrando'))

    fireEvent.click(screen.getByRole('tab', { name: /instruções táticas/i }))
    fireEvent.change(screen.getByLabelText('Estilo de jogo'), { target: { value: 'RETRANCA' } })

    expect(screen.getByText('1 substituição · 1 ajuste tático')).toBeInTheDocument()
  })

  it('with a half-built swap (OUT chosen, no IN yet), the footer hints at finishing it instead of claiming nothing is pending', () => {
    const outP = player({ playerId: 'out1', name: 'Saindo' })
    renderDialog({ onPitch: [outP], bench: [] })

    fireEvent.click(within(outSection()).getByText('Saindo'))

    expect(screen.getByText('Escolha quem entra para concluir a troca.')).toBeInTheDocument()
    expect(screen.queryByText('Nenhuma alteração pendente.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aplicar' })).toBeDisabled()
  })

  it('with a half-built swap (IN chosen, no OUT yet), the footer hints at finishing it instead of claiming nothing is pending', () => {
    const inP = player({ playerId: 'in1', name: 'Entrando', onPitch: false })
    renderDialog({ onPitch: [], bench: [inP] })

    fireEvent.click(within(inSection()).getByText('Entrando'))

    expect(screen.getByText('Escolha quem sai para concluir a troca.')).toBeInTheDocument()
    expect(screen.queryByText('Nenhuma alteração pendente.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aplicar' })).toBeDisabled()
  })
})
