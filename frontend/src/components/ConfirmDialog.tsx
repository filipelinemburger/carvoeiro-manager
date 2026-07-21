import { Dialog, DialogTitle, DialogContent, DialogContentText, DialogActions, Button } from '@mui/material'

export interface ConfirmDialogProps {
  open: boolean
  title: string
  message: string
  confirmLabel: string
  cancelLabel?: string
  onConfirm: () => void
  onClose: () => void
  // Disables the confirm button (e.g. while the confirmed action's mutation is in
  // flight) so a double-click/held-Enter before the dialog unmounts can't fire twice.
  confirmDisabled?: boolean
}

/** Thin MUI Dialog wrapper for a generic "are you sure?" confirmation step. */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  cancelLabel = 'Cancelar',
  onConfirm,
  onClose,
  confirmDisabled = false,
}: ConfirmDialogProps) {
  return (
    <Dialog open={open} onClose={onClose}>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <DialogContentText>{message}</DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{cancelLabel}</Button>
        <Button variant="contained" onClick={onConfirm} disabled={confirmDisabled}>{confirmLabel}</Button>
      </DialogActions>
    </Dialog>
  )
}
