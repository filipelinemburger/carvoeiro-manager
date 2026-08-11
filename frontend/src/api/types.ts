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

/** A 1-based page of results, mirroring the backend's PageResponse<T>. */
export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
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
  managerName?: string
  managerNationality?: string
}

export type CreateSaveGameRequest = {
  managerName: string
  managerNationality: string
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
  lastPlayedAt: string | null
}

export type Mentality = 'DEFENSIVE' | 'BALANCED' | 'ATTACKING'
export type GameStyle = 'EQUILIBRADO' | 'JOGO_DE_PASSES' | 'CONTRA_ATAQUE' | 'JOGO_DIRETO' | 'PRESSAO_ALTA' | 'RETRANCA'
export type AttackZone = 'LATERAIS' | 'CENTRO' | 'MISTO'
export type Pressing = 'LOW' | 'MEDIUM' | 'HIGH'
export type Marking = 'ZONAL' | 'MAN_TIGHT'
/**
 * The tactical archetype (EAFC-style role) a player is assigned, valid for a subset of
 * {@link DetailedPosition}s; mirrors the backend `RoleArchetype` enum exactly. The wire field
 * carrying this stays named `role` (backend-compatible), but the type itself is no longer the
 * retired `PlayerRole` — that 10-value union has been superseded end-to-end by this 30-value one.
 */
export type RoleArchetype =
  | 'GOALKEEPER' | 'STOPPER' | 'FULLBACK' | 'BALL_PLAYING_DEFENDER'
  | 'HOLDING' | 'BOX_TO_BOX' | 'PLAYMAKER'
  | 'POACHER' | 'TARGET_FORWARD' | 'WINGER'
  | 'SWEEPER_KEEPER' | 'BALL_PLAYING_KEEPER'
  | 'WINGBACK' | 'FALSEBACK' | 'ATTACKING_WINGBACK' | 'INVERTED_WINGBACK'
  | 'DEFENDER' | 'WIDE_BACK'
  | 'DEEP_LYING_PLAYMAKER' | 'CENTRE_HALF' | 'WIDE_HALF' | 'BOX_CRASHER'
  | 'HALF_WINGER' | 'SHADOW_STRIKER' | 'CLASSIC_10'
  | 'WIDE_PLAYMAKER' | 'WIDE_MIDFIELDER' | 'INSIDE_FORWARD'
  | 'ADVANCED_FORWARD' | 'FALSE_9'

/** The focus dimension of a role (a tactical "lean" on top of the archetype); mirrors `RoleFocus`. */
export type RoleFocus =
  | 'DEFEND' | 'BALANCED' | 'ATTACK' | 'BUILD_UP' | 'SUPPORT'
  | 'ROAMING' | 'VERSATILE' | 'AGGRESSIVE' | 'BALL_WINNING' | 'WIDE'

/** Optional per-player "instrução pessoal" layered on top of the tactical role. */
export type PlayerInstruction =
  | 'INFILTRAR_NA_AREA' | 'CHUTAR_DE_LONGE' | 'CRUZAR_MAIS'
  | 'SEGURAR_POSICAO' | 'MARCACAO_DURA' | 'DRIBLAR_MAIS'

/** Fine-grained playing position, refining the coarse `position`; mirrors the backend enum. */
export type DetailedPosition =
  | 'GOLEIRO'
  | 'ZAGUEIRO'
  | 'LATERAL_DIREITO'
  | 'LATERAL_ESQUERDO'
  | 'VOLANTE'
  | 'MEIA_CENTRAL'
  | 'MEIA_ARMADOR'
  | 'MEIA_DIREITA'
  | 'MEIA_ESQUERDA'
  | 'PONTA_DIREITA'
  | 'PONTA_ESQUERDA'
  | 'ATACANTE'

export type PlayerResponse = {
  id: string
  name: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  /** Fine-grained position sent by the API; null on legacy payloads persisted before it existed. */
  detailedPosition: DetailedPosition | null
  /** PT-BR label for `detailedPosition` (e.g. "Lateral esquerdo"); null when the enum is null. */
  detailedPositionLabel: string | null
  age: number
  overall: number
  pace: number
  finishing: number
  passing: number
  tackling: number
  goalkeeping: number
  stamina: number
  form: number
  morale: number
  yellowCards: number
  suspensionRemaining: number
  injuryMatches: number
  eligible: boolean
  role: RoleArchetype | null
  /** The role's focus (RoleFocus name); null means the archetype's canonical default focus.
   * Optional here only to keep older test fixtures compiling. */
  focus?: RoleFocus | null
  /** 0–2 alternate detailed positions the player can also fill. Optional here only to keep
   * older test fixtures compiling. */
  altPositions?: DetailedPosition[]
  /** Generation-time familiar archetypes (e.g. "WINGER++", "FALSEBACK+"). Optional here only to
   * keep older test fixtures compiling. */
  familiarRoles?: string[]
  contractYears: number
  /** Market value (fee guidance) computed by the backend from overall + age. */
  value: number
  wageDemand: number
  fitness: number
  injurySeverity: 'NONE' | 'KNOCK' | 'MINOR' | 'MAJOR'
  /** Full 28-attribute board, in canonical order (always sent by the API). */
  attributes?: AttributeView[]
  /** Always sent by the API; optional here only to keep older test fixtures compiling. */
  transferListed?: boolean
  listedReason?: ListedReason | null
  /** One of the 6 trainable anchor attributes, or null if the player isn't currently training. */
  trainingFocus?: TrainableAttribute | null
  trainingIntensity?: TrainingIntensity
}

/** Why a player is currently transfer-listed — AI triggers, or OWNER for a manual listing. */
export type ListedReason = 'PLAYER_REQUEST' | 'UNUSED' | 'FINANCIAL' | 'SURPLUS' | 'OWNER'

/** One attribute as sent by the API: enum key, pt-BR label, group key, value. */
export type AttributeView = {
  key: string
  label: string
  group: 'TECNICO' | 'MENTAL' | 'FISICO' | 'GOLEIRO'
  value: number
}

export type LineupStarter = {
  playerId: string
  role: RoleArchetype
  /** Optional focus for the role; null (or omitted) means the archetype's canonical default.
   * Optional here only to keep older test fixtures compiling. */
  focus?: RoleFocus | null
  /** Optional per-player instruction; null (or omitted) means none. */
  instruction: PlayerInstruction | null
  /**
   * Explicit formation slot (index into pitchSlots(formation); 0 = GK). Null/omitted on bench
   * entries and legacy lineups — the engine then assigns greedily. Optional here only to keep
   * older test fixtures compiling.
   */
  slotIndex?: number | null
}

export type SquadResponse = {
  players: PlayerResponse[]
  primaryColor: string
  secondaryColor: string
}

export type TrainableAttribute =
  | 'VELOCIDADE' | 'FINALIZACAO' | 'PASSE' | 'DESARME' | 'REFLEXOS' | 'FOLEGO'
  | 'CABECEIO' | 'CRUZAMENTO' | 'MARCACAO' | 'DRIBLE' | 'VISAO' | 'FORCA'
export type TrainingIntensity = 'LEVE' | 'NORMAL' | 'INTENSA'

export type TrainingAssignment = {
  playerId: string
  focus: TrainableAttribute | null
  intensity: TrainingIntensity
}

export type SetTrainingRequest = {
  assignments: TrainingAssignment[]
}

export type SeasonStatsLine = {
  seasonYear: number
  competitionName: string
  matchesPlayed: number
  minutesPlayed: number
  goals: number
  assists: number
  fouls: number
  yellowCards: number
  redCards: number
}

export type PlayerStatsResponse = {
  seasons: SeasonStatsLine[]
  totals: SeasonStatsLine
}

/** One club in the club-browsing list, sorted by reputation (highest first). */
export type ClubSummary = {
  id: string
  name: string
  commonName: string | null
  shortName: string
  primaryColor: string | null
  secondaryColor: string | null
  reputation: number
  squadSize: number
  /** Geography; absent on payloads persisted before these fields existed. */
  city?: string | null
  state?: string | null
  stadiumName?: string | null
  stadiumCapacity?: number
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
  gameStyle: GameStyle
  attackZone: AttackZone
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

/**
 * The competition-format enum name (not a display label) — mirrors the backend `PhaseKind`
 * enum exactly. Switch on this, never on `Phase.name` (which is the pt-BR label).
 */
export type PhaseKind = 'ROUND_ROBIN' | 'KNOCKOUT'

/** One stage of a competition's format (e.g. league phase, or a knockout round), in bracket order. */
export type Phase = {
  id: string
  ordinal: number
  /** pt-BR display label (e.g. "Primeira Fase", "Quartas de final") — never switch on this. */
  name: string
  /** The enum name — switch on this, not `name`. */
  kind: PhaseKind
  legs: number
  rounds: number
  qualifiers: number | null
  complete: boolean
}

/**
 * One knockout tie (both legs) in a phase's seeded bracket. All goal/penalty fields and
 * `winnerClubId` are null until played — a tie can be fetched mid-season with either leg,
 * or the whole tie, unplayed.
 */
export type KnockoutTie = {
  tieId: string
  tieOrder: number
  higherSeedClubId: string
  /** Null when the club row could not be resolved — KnockoutTieService sends null, not "". */
  higherSeedClubName: string | null
  lowerSeedClubId: string
  lowerSeedClubName: string | null
  firstLegHomeGoals: number | null
  firstLegAwayGoals: number | null
  secondLegHomeGoals: number | null
  secondLegAwayGoals: number | null
  penaltyHome: number | null
  penaltyAway: number | null
  winnerClubId: string | null
}

/** One fixture on the managed club's season calendar; goals are oriented to the managed club
 * (goalsFor/goalsAgainst), and both are null until the fixture is played. */
export type FixtureEntry = {
  fixtureId: string
  round: number
  matchDate: string
  home: boolean
  opponentClubId: string
  opponentName: string
  opponentShortName: string
  stadiumName: string
  played: boolean
  goalsFor: number | null
  goalsAgainst: number | null
  /** Null until the eve of the match (see the backend's reveal rule); absent on payloads persisted before these fields existed. */
  weather?: string | null
  temperatureC?: number | null
  /** Null until the fixture has been played. */
  attendance?: number | null
  /**
   * Shootout score in the same for/against orientation as goalsFor/goalsAgainst, present only
   * when a knockout tie went to penalties. The goals stay the 120-minute score, so without
   * these a tie won on penalties shows as a plain draw in the calendar.
   */
  penaltiesFor?: number | null
  penaltiesAgainst?: number | null
}

export type FixtureResultResponse = {
  fixtureId: string
  homeClubId: string
  homeClubName: string
  homeClubShortName?: string
  homeGoals: number
  awayClubId: string
  awayClubName: string
  awayClubShortName?: string
  awayGoals: number
  /** Home side's stadium (real name, or a generic fallback); absent on payloads persisted before this field existed. */
  stadium?: string | null
  attendance?: number | null
  weather?: string | null
  temperatureC?: number | null
  /**
   * Shootout score, present only when a knockout tie was decided on penalties. homeGoals/awayGoals
   * remain the 120-minute score, so without rendering these a final won on penalties reads as a
   * draw. Optional: absent on payloads persisted before the field existed.
   */
  penaltyHome?: number | null
  penaltyAway?: number | null
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

export type CompletedTransferResponse = {
  playerName: string
  fromClubName: string
  toClubName: string
  fee: number
}

export type TrainingReportEntry = {
  playerName: string
  message: string
}

export type TrainingReportResponse = {
  staffName: string
  quality: StaffQuality
  entries: TrainingReportEntry[]
}

export type PreviewStandingSnapshot = {
  position: number
  points: number
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
}

export type MatchPreviewData = {
  fixtureId: string
  round: number
  matchDate: string
  home: boolean
  stadiumName: string | null
  /** Null when the fixture's forecast is not yet revealed (see the backend's reveal rule). */
  weather?: string | null
  temperatureC?: number | null
  /** A pt-BR sentence of advice; null when the weather is unremarkable. */
  weatherAdvice?: string | null
  opponentClubId: string
  opponentName: string
  opponentShortName: string | null
  assistantName: string
  opponentStanding: PreviewStandingSnapshot | null
  managedStanding: PreviewStandingSnapshot | null
  form: string[]
  bestPlayers: { name: string; position: string; overall: number }[]
  topScorer: { name: string; goals: number } | null
  tacticalRead: { formation: string; mentality: string; pressing: string; marking: string } | null
  insights: string[] | null
}

export type AdvanceResponse = {
  // The four phase-transition types are written by PhaseResolver. Omitting them here does not
  // fail the type-check -- newsTitle/newsIcon just fall through to their default branches and
  // the card renders empty, which is how they shipped invisible.
  type:
    | 'MATCH' | 'TRANSFER' | 'FINISHED' | 'TRAINING_REPORT' | 'PRECONTRACT' | 'WATCHLIST'
    | 'MATCH_PREVIEW'
    | 'PHASE_QUALIFIED' | 'PHASE_ELIMINATED' | 'KNOCKOUT_DRAW' | 'CHAMPION'
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
  /** AI-to-AI trades completed during a TRANSFER event; null (not just empty) on news payloads
   * persisted before this field existed — always null-guard before reading it. */
  transfers?: CompletedTransferResponse[] | null
  /** The Preparador Físico's weekly digest; only on TRAINING_REPORT items, null everywhere
   * else and on payloads persisted before the feature existed — always null-guard it. */
  trainingReport?: TrainingReportResponse | null
  /** Pre-contract agreements/renewals/pokes resolved on this TRANSFER day; only on PRECONTRACT
   * items, null everywhere else and on payloads persisted before the feature existed —
   * always null-guard it. */
  preContractEvents?: PreContractEvent[] | null
  /** Watchlist alerts (sold/listed/free-agent/offer-received); only on WATCHLIST items,
   * null everywhere else and on payloads persisted before the feature existed —
   * always null-guard it. */
  playerAlerts?: PlayerAlert[] | null
  /** The Auxiliar Técnico's opponent analysis; only on MATCH_PREVIEW items, null everywhere
   * else and on payloads persisted before the feature existed — always null-guard it. */
  matchPreview?: MatchPreviewData | null
}

/** One news-feed entry: the persisted item's id + read flag plus its full payload. */
export type NewsFeedItem = {
  id: string
  read: boolean
  payload: AdvanceResponse
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
  expiredContracts: SquadChange[]
  youthIntake: SquadChange[]
  youthDepartures: SquadChange[]
  preContractArrivals: SquadChange[]
  preContractDepartures: SquadChange[]
  watchlistEvents: PlayerAlert[]
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

export type YouthProspectResponse = {
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
  potentialStars: number
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
  /** Always sent by the API (the market only ever returns listed players); optional here only
   * to keep older test fixtures compiling. */
  listedReason?: ListedReason | null
}

/** Optional narrowing criteria for the market list — every field absent means "no constraint". */
export type MarketFilters = {
  clubId?: string
  position?: string
  ageMin?: number
  ageMax?: number
  overallMin?: number
  overallMax?: number
  valueMin?: number
  valueMax?: number
}

export type TransferListing = {
  playerId: string
  transferListed: boolean
  listedReason: ListedReason | null
}

export type TransferOffer = {
  id: string
  playerId: string
  playerName: string
  fromClubId: string
  fromClubName: string
  toClubId: string
  toClubName: string
  /** AI is an AI-to-AI trade — completed, never returned by /offers, only surfaced via news transfers. */
  direction: 'OUTGOING' | 'INCOMING' | 'AI'
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

export type PreContractEligible = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  clubName: string
  wageDemand: number
}

export type PreContractAgreement = {
  id: string
  playerId: string
  playerName: string
  position: string
  age: number
  overall: number
  fromClubName: string
  toClubName: string
  wage: number
  contractYears: number
  status: 'AGREED' | 'LOCKED' | 'CANCELLED'
  /** true when this is a threat: an AI club is taking one of YOUR players. */
  incoming: boolean
}

export type PreContractOfferResult = {
  status: 'AGREED' | 'COUNTERED'
  demandedWage: number | null
  agreement: PreContractAgreement | null
}

export type PreContractEvent = {
  kind: 'AGREED_OUT' | 'POACHED_IN' | 'LOCKED_OUT' | 'CANCELLED_OUT' | 'LOCKED_IN'
  playerName: string
  clubName: string
  wage: number | null
  contractYears: number | null
}

export type WatchlistPlayer = {
  playerId: string
  name: string
  position: string
  age: number
  overall: number
  clubName: string
  freeAgent: boolean
  transferListed: boolean
  preContractEligible: boolean
}

export type PlayerAlert = {
  kind: 'SOLD' | 'LISTED' | 'FREE_AGENT' | 'OFFER_RECEIVED'
  playerName: string
  clubName: string
  detail: string | null
}

export type StaffRole = 'TECNICO' | 'ASSISTENTE' | 'PREPARADOR_FISICO' | 'OLHEIRO' | 'FISIOTERAPEUTA'
export type StaffQuality = 'REGULAR' | 'BOM' | 'EXCELENTE'

export type StaffMember = {
  name: string
  /** Null only for the managed club's head-coach slot — that entry is the player, not a hire. */
  quality: StaffQuality | null
  wage: number | null
  primaryAttribute: number
  secondaryAttribute: number
}

export type StaffRoster = {
  tecnico: StaffMember | null
  assistente: StaffMember | null
  preparadorFisico: StaffMember | null
  olheiro: StaffMember | null
  fisioterapeuta: StaffMember | null
}

export type StaffCandidate = {
  index: number
  name: string
  quality: StaffQuality
  wage: number
  primaryAttribute: number
  secondaryAttribute: number
  /** Set when this candidate would have to be poached from another club. */
  currentClubName: string | null
}

export type MakeOfferRequest = { playerId: string; amount: number; wage: number; contractYears: number }
export type RespondOfferRequest = { action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }

// Live match

// EXTRA_TIME is emitted for every minute past 90 in a knockout decider that was level at
// full time. Omitting it here does not fail the type-check -- the value simply matches no
// branch at runtime, which is how the clock silently stopped at 90'.
export type LiveMatchStatus =
  | 'IN_PROGRESS'
  | 'EXTRA_TIME'
  | 'HALF_TIME'
  | 'PAUSED_INJURY'
  | 'PAUSED_RED'
  | 'FULL_TIME'

export type LiveTactics = {
  mentality: string
  gameStyle: string
  attackZone: string
  pressing: string
  marking: string
  formation: string
}

export type LiveTacticsRequest = {
  mentality: string
  gameStyle: string
  attackZone: string
  pressing: string
  marking: string
  formation: string
}

export type LiveEvent = {
  minute: number
  type: 'GOAL' | 'SET_PIECE_GOAL' | 'YELLOW_CARD' | 'RED_CARD' | 'CHANCE' | 'INJURY' | 'SUBSTITUTION' | 'TACTICS_CHANGE' | 'HALF_TIME' | 'PENALTY_GOAL' | 'FREE_KICK_GOAL' | 'CORNER_GOAL' | 'PENALTY_MISSED'
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
  role: string
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

export type LiveKitColors = { primary: string; secondary: string }

/** One other fixture's score in the same round, as it stands at the live match's committed minute. */
export type ParallelScore = {
  fixtureId: string
  /**
   * Null when the backend could not resolve the fixture row behind this snapshot entry (the score
   * is still shown; only the standings join is lost). The two ids are always null together — they
   * come from one `Fixture` or from nothing — so narrowing one narrows the pair in practice.
   */
  homeClubId: string | null
  awayClubId: string | null
  homeShort: string
  awayShort: string
  homeGoals: number
  awayGoals: number
}

export type TeamStats = {
  possession: number
  shots: number
  shotsOnTarget: number
  saves: number
  corners: number
  offsides: number
  fouls: number
  yellowCards: number
  redCards: number
}

/**
 * Mirrors the backend `MatchStats` record exactly: **home/away oriented, not
 * managed/opponent** — like `homeGoals`/`awayGoals` and `homeClubColors`/`awayClubColors`.
 * Nothing flips it based on `managedIsHome`.
 */
export type MatchStats = { home: TeamStats; away: TeamStats }

export type LiveMatchState = {
  fixtureId: string
  competitionName: string | null
  round: number | null
  matchDate: string | null
  stadiumName: string
  homeClubId: string
  homeClubName: string
  homeClubShortName: string
  awayClubId: string
  awayClubName: string
  awayClubShortName: string
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
  opponentOnPitch: LivePlayer[]
  opponentTactics: LiveTactics
  opponentRatings: PlayerRating[]
  homeClubColors: LiveKitColors
  awayClubColors: LiveKitColors
  competitionId: string
  parallelScores: ParallelScore[]
  weather: string | null
  temperatureC: number | null
  attendance: number | null
  stats: MatchStats
}

export type SubstituteRequest = { outPlayerId: string; inPlayerId: string }
export type SubstituteBatchRequest = { subs: SubstituteRequest[] }
