import { Box, Button, Card, CardContent, Stack, Typography } from '@mui/material'
import type { LoanOfferResult } from '../../api/types'

/**
 * A countered loan offer's path back once the dialog that started it has closed (or the page was
 * refreshed) — `SquadPage` and `TransferMarketPage` both mount this from the same
 * `GET /loans` listing, so the manager can accept or withdraw one he didn't act on immediately.
 *
 * Deliberately not a second inbox: every `LoanOffer` row is already something the manager himself
 * proposed (there is no AI-initiated loan bid the way transfers get unsolicited offers), so there
 * is nothing here to triage — only accept or drop. One flat list, no tabs, no direction split by
 * page/tab. Renders nothing when there is nothing open.
 *
 * The wage-share percentage IS direction-aware, though — it has to be. `ownerWageShare` /
 * `counterOwnerWageShare` are always "the owner's share", and the same number means opposite
 * things depending on `offer.managedClubIsOwner`: on a loan-out (true) it is money out of the
 * manager's own budget, the same as `LoanOutDialog`'s "Percentual do salário que seu clube
 * continua pagando"; on a loan-in (false) it is the AI owner's money, the same as
 * `LoanInDialog`'s "...que o clube dono continua pagando". A neutral "70% de salário" with no
 * direction would let the manager execute an irreversible squad move without knowing which of
 * those two it is.
 *
 * The transfer window can close between the counter and the accept (it is re-checked server-side
 * at accept time) — `windowOpen` disables Aceitar and explains why rather than hiding the pending
 * counter, so a manager who missed the window still sees it and can withdraw instead.
 */
export function PendingLoanOffers({ offers, windowOpen, busy, onAccept, onWithdraw }: {
  offers: LoanOfferResult[]
  windowOpen: boolean
  busy: boolean
  onAccept: (offerId: string) => void
  onWithdraw: (offerId: string) => void
}) {
  if (offers.length === 0) return null

  return (
    <Box sx={{ mb: 2 }}>
      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        Empréstimos com contraproposta pendente
      </Typography>
      <Stack component="ul" spacing={1} sx={{ listStyle: 'none', p: 0, m: 0 }}>
        {offers.map(offer => {
          // Same distinction LoanOutDialog/LoanInDialog draw: "seu clube" when the managed club is
          // the owner (a loan-out — this money comes out of its own budget), "o clube dono"
          // otherwise (a loan-in — it's the AI owner's money, not the manager's).
          const paidBy = offer.managedClubIsOwner ? 'pago por seu clube' : 'pago pelo clube dono'
          const faceMessage = offer.managedClubIsOwner
            ? (offer.counterMayFaceOwner ? 'pode enfrentar seu clube' : 'não pode enfrentar seu clube')
            : (offer.counterMayFaceOwner ? 'pode enfrentar o clube dono' : 'não pode enfrentar o clube dono')
          return (
            <Card key={offer.offerId} component="li" variant="outlined" sx={{ listStyle: 'none' }}>
              <CardContent sx={{ py: 1.25, '&:last-child': { pb: 1.25 } }}>
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={1.5}
                  sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
                >
                  <Box sx={{ minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 700 }}>{offer.playerName}</Typography>
                    <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                      Contraproposta: {offer.counterOwnerWageShare ?? '—'}% de salário {paidBy}
                      {' · '}
                      {faceMessage}
                    </Typography>
                    {!windowOpen && (
                      <Typography variant="caption" color="error">
                        Janela de transferências fechada — não é possível aceitar agora.
                      </Typography>
                    )}
                  </Box>
                  <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                    <Button
                      size="small"
                      color="secondary"
                      variant="contained"
                      disabled={!windowOpen || busy}
                      onClick={() => onAccept(offer.offerId)}
                    >
                      Aceitar
                    </Button>
                    <Button
                      size="small"
                      color="inherit"
                      variant="outlined"
                      disabled={busy}
                      onClick={() => onWithdraw(offer.offerId)}
                    >
                      Desistir
                    </Button>
                  </Stack>
                </Stack>
              </CardContent>
            </Card>
          )
        })}
      </Stack>
    </Box>
  )
}
