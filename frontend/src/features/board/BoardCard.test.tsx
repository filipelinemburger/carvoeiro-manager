import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { AuthProvider } from '../../auth/AuthContext'
import { BoardCard } from './BoardCard'
import { getBoard } from '../../api/board'
import type { BoardResponse } from '../../api/types'

vi.mock('../../api/board', () => ({ getBoard: vi.fn() }))

function board(over: Partial<BoardResponse> = {}): BoardResponse {
  return {
    confidence: 60,
    ultimatumActive: false,
    sackingEnabled: true,
    graceSeason: false,
    objectives: [
      { kind: 'LEAGUE', competitionName: 'Série B', tier: 2, targetPosition: 8, targetStage: null,
        currentPosition: 11, progress: 'AT_RISK', outcome: 'PENDING' },
      { kind: 'STATE', competitionName: 'Campeonato Catarinense', tier: null, targetPosition: null,
        targetStage: 'CHAMPION', currentPosition: null, progress: null, outcome: 'MET' },
      { kind: 'FINANCE', competitionName: null, tier: null, targetPosition: null, targetStage: null,
        currentPosition: null, progress: 'ON_TRACK', outcome: 'PENDING' },
    ],
    recentChanges: [
      { date: '2026-06-14', reason: 'MATCH_RESULT', delta: -3, payload: { opponent: 'Avaí', score: '0–2', home: true } },
    ],
    ...over,
  }
}

function renderCard() {
  localStorage.setItem('carvoeiro_manager_token', 'test-token')
  localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'asd', userId: 'U1' }))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <BoardCard saveGameId="save-1" />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BoardCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it.each([
    [85, 'Muito satisfeita'], [60, 'Satisfeita'], [50, 'Neutra'], [35, 'Preocupada'],
    [20, 'Insatisfeita'], [10, 'Cargo por um fio'],
  ])('shows the mood for confidence %i', async (confidence, label) => {
    ;(getBoard as Mock).mockResolvedValue(board({ confidence }))
    renderCard()
    expect(await screen.findByText(label)).toBeInTheDocument()
    expect(screen.getByText(`${confidence}/100`)).toBeInTheDocument()
  })

  it('lists the objectives with their live position and status', async () => {
    ;(getBoard as Mock).mockResolvedValue(board())
    renderCard()
    expect(await screen.findByText('Terminar entre os 8 primeiros')).toBeInTheDocument()
    expect(screen.getByText('atualmente 11º')).toBeInTheDocument()
    expect(screen.getByText('em risco')).toBeInTheDocument()
    expect(screen.getByText('cumprido')).toBeInTheDocument()
    expect(screen.getByText('Terminar a temporada com saldo positivo')).toBeInTheDocument()
  })

  it('explains the latest change', async () => {
    ;(getBoard as Mock).mockResolvedValue(board())
    renderCard()
    expect(await screen.findByText('−3 · Derrota para Avaí (0–2)')).toBeInTheDocument()
  })

  it('warns with the ultimatum banner', async () => {
    ;(getBoard as Mock).mockResolvedValue(board({ confidence: 28, ultimatumActive: true }))
    renderCard()
    expect(await screen.findByText(/Ultimato da diretoria/)).toBeInTheDocument()
  })

  it('notes the grace season and a career without sacking', async () => {
    ;(getBoard as Mock).mockResolvedValue(board({ graceSeason: true, sackingEnabled: false }))
    renderCard()
    expect(await screen.findByText(/Temporada de adaptação/)).toBeInTheDocument()
    expect(screen.getByText('Demissão desativada nesta carreira.')).toBeInTheDocument()
  })
})
