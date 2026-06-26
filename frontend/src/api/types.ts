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
}

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
}

export type SquadResponse = {
  players: PlayerResponse[]
}

export type SetLineupRequest = {
  formation: string
  startingPlayerIds: string[]
}

export type LineupResponse = {
  lineupId: string
  clubId: string
  formation: string
  startingPlayerIds: string[]
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

export type SimulateRoundResponse = {
  round: number
  results: FixtureResultResponse[]
  seasonFinished: boolean
}
