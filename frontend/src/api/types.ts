export interface AuthResponse {
  token: string
  username: string
  userId: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
}

// Career / Save game

export type CompetitionSummary = {
  id: string
  name: string
  type: string
  seasonYear: number
  totalRounds: number
}

export type SaveGameResponse = {
  id: string
  seasonYear: number
  competitionId: string
  competitionName: string
  managedClubId: string
  managedClubName: string
  currentRound: number
  totalRounds: number
  currentDate?: string | null
  competitions?: CompetitionSummary[]
  status?: 'IN_PROGRESS' | 'FINISHED'
  transferWindowOpen?: boolean
}

// Lightweight career summary for the "Carregar jogo salvo" list.
export type SaveGameSummary = {
  id: string
  managedClubName: string | null
  seasonYear: number
  currentRound: number
  currentDate: string | null
  status: 'IN_PROGRESS' | 'FINISHED'
  createdAt: string
}

export type Mentality = 'DEFENSIVE' | 'BALANCED' | 'ATTACKING'
export type Pressing = 'LOW' | 'MEDIUM' | 'HIGH'
export type Marking = 'ZONAL' | 'MAN_TIGHT'
export type PlayerRole =
  | 'GOALKEEPER'
  | 'STOPPER' | 'FULL_BACK' | 'BALL_PLAYING_DEF'
  | 'HOLDING_MID' | 'BOX_TO_BOX' | 'PLAYMAKER'
  | 'POACHER' | 'TARGET_MAN' | 'WINGER'

export type PlayerResponse = {
  id: string
  name: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  age: number
  overall: number
  pace: number
  finishing: number
  passing: number
  tackling: number
  goalkeeping: number
  stamina: number
  form: number
  yellowCards: number
  suspensionRemaining: number
  injuryMatches: number
  eligible: boolean
  role: PlayerRole | null
  contractYears: number
  wageDemand: number
  fitness: number
  injurySeverity: 'NONE' | 'KNOCK' | 'MINOR' | 'MAJOR'
}

export type LineupStarter = {
  playerId: string
  role: PlayerRole
}

export type SquadResponse = {
  players: PlayerResponse[]
}

export type SetLineupRequest = {
  formation: string
  starters: LineupStarter[]
  bench: LineupStarter[]
}

export type LineupResponse = {
  lineupId: string
  clubId: string
  formation: string
  starters: LineupStarter[]
  bench: LineupStarter[]
}

export type TacticsRequest = {
  mentality: Mentality
  pressing: Pressing
  marking: Marking
  penaltyTakerId: string | null
  freekickTakerId: string | null
  cornerTakerId: string | null
}

export type TacticsResponse = TacticsRequest

export type Booking = {
  playerId: string
  playerName: string
  type: 'YELLOW_CARD' | 'RED_CARD'
}

export type Suspension = {
  playerId: string
  playerName: string
  matches: number
}

export type StandingRow = {
  clubId: string
  clubName: string
  /** Common/popular name (e.g. "Criciúma"); falls back to clubName when absent. */
  commonName?: string
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
  goalDifference: number
  points: number
}

export type FixtureResultResponse = {
  fixtureId: string
  homeClubId: string
  homeClubName: string
  homeGoals: number
  awayClubId: string
  awayClubName: string
  awayGoals: number
}

export type FinanceRoundResponse = {
  gate: number
  matchCost: number
  sponsorship: number
  wages: number
  upkeep: number
  prize: number
  net: number
  balance: number
}

export type Injury = {
  playerId: string
  playerName: string
  matches: number
  severity: 'KNOCK' | 'MINOR' | 'MAJOR'
}

export type AdvanceResponse = {
  type: 'MATCH' | 'TRANSFER' | 'FINISHED'
  date: string | null
  competitionName: string | null
  round: number | null
  results: FixtureResultResponse[]
  bookings: Booking[]
  newSuspensions: Suspension[]
  newInjuries: Injury[]
  finance: FinanceRoundResponse | null
  bidsReceived: number
  seasonFinished: boolean
  /** Managed club's 0–10 grades for the user's own live-played match; absent for AI rounds and old items. */
  playerRatings?: PlayerRating[] | null
}

export type TopScorerResponse = {
  playerName: string
  clubName: string
  goals: number
}

export type LowFitnessStarter = {
  playerId: string
  playerName: string
  fitness: number
}

export type IneligibleStarter = {
  playerId: string
  playerName: string
  reason: 'SUSPENDED' | 'INJURED'
}

export type AdvancePreviewResponse = {
  nextEventType: 'MATCH' | 'TRANSFER' | 'FINISHED'
  managedClubPlaysNext: boolean
  ineligibleStarters: IneligibleStarter[]
  windowClosingAfterAdvance: boolean
  pendingTransfers: number
  defaultStandingsCompetitionId: string | null
  lowFitnessStarters: LowFitnessStarter[]
}

export type SquadChange = {
  playerName: string
  position: string
  age: number
}

export type RolloverResponse = {
  seasonYear: number
  currentRound: number
  retirements: SquadChange[]
  newcomers: SquadChange[]
  expiredContracts: SquadChange[]
}

export type SeasonSummary = {
  seasonYear: number
  competitionName: string
  championClubName: string
  managedClubPosition: number
  topScorerName: string | null
  topScorerGoals: number
}

export type SeasonStandingRow = {
  position: number
  clubName: string
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
  goalDifference: number
  points: number
}

export type SeasonDetail = {
  seasonYear: number
  competitionName: string
  championClubName: string
  table: SeasonStandingRow[]
  topScorers: TopScorerResponse[]
}

export type FinanceTransaction = {
  seasonYear: number
  round: number
  type: 'GATE' | 'MATCH_COST' | 'WAGES' | 'SPONSOR' | 'UPKEEP' | 'PRIZE' | 'TRANSFER_IN' | 'TRANSFER_OUT'
  amount: number
  description: string
  txDate?: string | null
}

export type FinancesResponse = {
  balance: number
  transactions: FinanceTransaction[]
}

export type MarketPlayer = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  clubId: string
  clubName: string
  value: number
  wage: number
  wageDemand: number
}

export type TransferOffer = {
  id: string
  playerId: string
  playerName: string
  fromClubId: string
  fromClubName: string
  toClubId: string
  toClubName: string
  direction: 'OUTGOING' | 'INCOMING'
  amount: number
  counterAmount: number | null
  status: 'PENDING' | 'COUNTERED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'
  round: number
  wage: number | null
  contractYears: number | null
}

export type FreeAgent = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  wageDemand: number
}

export type RenewContractRequest = { wage: number; contractYears: number }
export type SignFreeAgentRequest = { wage: number; contractYears: number }
export type PlayerContract = { playerId: string; name: string; wage: number; contractYears: number }

export type MakeOfferRequest = { playerId: string; amount: number; wage: number; contractYears: number }
export type RespondOfferRequest = { action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }

// Live match

export type LiveMatchStatus = 'IN_PROGRESS' | 'HALF_TIME' | 'PAUSED_INJURY' | 'PAUSED_RED' | 'FULL_TIME'

export type LiveTactics = {
  mentality: string
  pressing: string
  marking: string
  formation: string
}

export type LiveTacticsRequest = {
  mentality: string
  pressing: string
  marking: string
  formation: string
}

export type LiveEvent = {
  minute: number
  type: 'GOAL' | 'SET_PIECE_GOAL' | 'YELLOW_CARD' | 'RED_CARD' | 'CHANCE' | 'INJURY' | 'SUBSTITUTION' | 'TACTICS_CHANGE' | 'HALF_TIME'
  home: boolean
  playerId: string
  playerName: string
  playerInId: string | null
  playerInName: string | null
  severity: string | null
  text: string
}

export type LivePlayer = {
  playerId: string
  name: string
  position: string
  overall: number
  fitness: number
  hobbled: boolean
  onPitch: boolean
  used: boolean
  eligible: boolean
}

/** A managed player's 0–10 performance grade for a finished match. */
export type PlayerRating = {
  playerId: string
  name: string
  position: string
  minutesPlayed: number
  goals: number
  rating: number
}

export type LiveMatchState = {
  fixtureId: string
  competitionName: string | null
  round: number | null
  matchDate: string | null
  homeClubName: string
  awayClubName: string
  managedIsHome: boolean
  minute: number
  homeGoals: number
  awayGoals: number
  status: LiveMatchStatus
  events: LiveEvent[]
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  currentTactics: LiveTactics
  /** Managed players' 0–10 grades; populated only at full time, empty otherwise. */
  playerRatings: PlayerRating[]
}

export type SubstituteRequest = { outPlayerId: string; inPlayerId: string }
