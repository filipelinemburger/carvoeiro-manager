import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { RoleContextMenu } from './RoleContextMenu'

describe('RoleContextMenu', () => {
  it('renders nothing while closed', () => {
    render(
      <RoleContextMenu
        anchor={null}
        playerName="Fellipe Mateus"
        funcaoControl={<select aria-label="Função" />}
        focoControl={<select aria-label="Foco" />}
        onClose={() => {}}
      />,
    )
    expect(screen.queryByText('Fellipe Mateus')).not.toBeInTheDocument()
  })

  it('shows the player name and both controls when anchored', () => {
    render(
      <RoleContextMenu
        anchor={{ playerId: 'p1', x: 120, y: 240 }}
        playerName="Fellipe Mateus"
        funcaoControl={<select aria-label="Função" />}
        focoControl={<select aria-label="Foco" />}
        onClose={() => {}}
      />,
    )
    expect(screen.getByText('Fellipe Mateus')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Função' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Foco' })).toBeInTheDocument()
  })
})
