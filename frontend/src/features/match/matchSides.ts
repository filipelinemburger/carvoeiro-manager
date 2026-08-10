import type { LiveKitColors, LiveMatchState, LivePlayer, PlayerRating } from '../../api/types'

export type SideView = {
  shortName: string
  formation: string
  players: LivePlayer[]
  ratings: PlayerRating[]
  kit: LiveKitColors
  isOwnSide: boolean
}

type ManagedTriple = Pick<SideView, 'formation' | 'players' | 'ratings'>

/**
 * The single home/away <-> managed/opponent mapping for the whole match screen. `LiveMatchState`
 * describes one side as the managed one (`onPitch`/`currentTactics`/`playerRatings`) and the
 * other as the opponent (`opponentOnPitch`/`opponentTactics`/`opponentRatings`), with
 * `managedIsHome` deciding which is which. Centralising the mapping here (instead of duplicating
 * it per-consumer) makes the historical inversion bug structurally impossible.
 *
 * `kit` comes straight from `homeClubColors`/`awayClubColors`, which are already home/away
 * oriented — only the managed/opponent triples are flipped by `managedIsHome`, never the kits.
 */
export function sidesOf(state: LiveMatchState): { home: SideView; away: SideView } {
  const managed: ManagedTriple = {
    formation: state.currentTactics.formation,
    players: state.onPitch,
    ratings: state.playerRatings,
  }
  const opponent: ManagedTriple = {
    formation: state.opponentTactics.formation,
    players: state.opponentOnPitch,
    ratings: state.opponentRatings,
  }

  const home: SideView = {
    shortName: state.homeClubShortName,
    kit: state.homeClubColors,
    isOwnSide: state.managedIsHome,
    ...(state.managedIsHome ? managed : opponent),
  }
  const away: SideView = {
    shortName: state.awayClubShortName,
    kit: state.awayClubColors,
    isOwnSide: !state.managedIsHome,
    ...(state.managedIsHome ? opponent : managed),
  }

  return { home, away }
}
