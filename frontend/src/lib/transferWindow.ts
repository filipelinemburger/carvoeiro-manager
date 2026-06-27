export function isTransferWindowOpen(currentRound: number, totalRounds: number): boolean {
  if (currentRound < 3) return true
  const midStart = Math.floor(totalRounds / 2)
  return currentRound >= midStart && currentRound < midStart + 3
}
