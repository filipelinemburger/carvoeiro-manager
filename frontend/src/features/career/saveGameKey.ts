// Scope the stored save-game id per user so one account never inherits another's
// career id (which would 404 against the ownership guard). Shared by the dashboard,
// the layout, and the "Carregar jogo salvo" dialog so all sites agree on the key.
export const saveGameKeyFor = (userId: string | undefined) =>
  `carvoeiro_manager_save_game_id_${userId ?? 'anon'}`
