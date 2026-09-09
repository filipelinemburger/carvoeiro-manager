import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { MatchActionsDialog } from './MatchActionsDialog'
import { FORMATION_CODES } from '../pitch/pitchSlots'
import { MENTALITY_LABEL, GAME_STYLE_LABEL, ATTACK_ZONE_LABEL, PRESSING_LABEL, MARKING_LABEL } from '../squad/tacticsLabels'
import type { LiveEvent, LivePlayer } from '../../api/types'

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
      currentTactics={{ mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' }}
    />,
  )
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
    fireEvent.click(screen.getByRole('button', { name: /continuar/i }))

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
    fireEvent.click(screen.getByRole('button', { name: /continuar/i }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith({
      subs: [],
      tactics: expect.objectContaining({ attackZone: 'CENTRO', mentality: 'BALANCED' }),
    })
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
    fireEvent.click(screen.getByRole('button', { name: /continuar/i }))

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
})
