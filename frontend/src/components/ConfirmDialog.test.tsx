import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

function renderDialog(overrides: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  const onConfirm = vi.fn()
  const onClose = vi.fn()
  render(
    <ConfirmDialog
      open
      title="Ir para o jogo"
      message="Começar a partida contra o próximo adversário?"
      confirmLabel="Começar partida"
      onConfirm={onConfirm}
      onClose={onClose}
      {...overrides}
    />,
  )
  return { onConfirm, onClose }
}

describe('ConfirmDialog', () => {
  it('renders the title and message when open', () => {
    renderDialog()
    expect(screen.getByRole('heading', { name: 'Ir para o jogo' })).toBeInTheDocument()
    expect(screen.getByText('Começar a partida contra o próximo adversário?')).toBeInTheDocument()
  })

  it('does not render dialog content when closed', () => {
    renderDialog({ open: false })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows the provided confirm label and defaults the cancel label to Cancelar', () => {
    renderDialog()
    expect(screen.getByRole('button', { name: 'Começar partida' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument()
  })

  it('supports a custom cancel label', () => {
    renderDialog({ cancelLabel: 'Voltar' })
    expect(screen.getByRole('button', { name: 'Voltar' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar' })).not.toBeInTheDocument()
  })

  it('calls onConfirm when the confirm button is clicked', () => {
    const { onConfirm, onClose } = renderDialog()
    fireEvent.click(screen.getByRole('button', { name: 'Começar partida' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()
  })

  it('calls onClose when the cancel button is clicked', () => {
    const { onConfirm, onClose } = renderDialog()
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
  })
})
