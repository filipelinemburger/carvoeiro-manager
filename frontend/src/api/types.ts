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
  competitions?: string[]
  status?: 'IN_PROGRESS' | 'FINISHED'
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
}

export type LineupResponse = {
  lineupId: string
  clubId: string
  formation: string
  starters: LineupStarter[]
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
}

export type TopScorerResponse = {
  playerName: string
  clubName: string
  goals: number
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
  type: 'GATE' | 'WAGES' | 'SPONSOR' | 'UPKEEP' | 'PRIZE'
  amount: number
  description: string
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
