import { useState, type ReactNode } from 'react'

/**
 * A titled, expand/collapse section. The header button toggles visibility of the body
 * and exposes aria-expanded for accessibility.
 */
export function Collapsible({ title, defaultOpen = true, children }: {
  title: string
  defaultOpen?: boolean
  children: ReactNode
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <section
      style={{
        border: '1px solid #e0e0e0',
        borderRadius: 8,
        marginBottom: '1rem',
        background: '#fff',
        overflow: 'hidden',
      }}
    >
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(o => !o)}
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0.75rem 1rem',
          background: 'none',
          border: 0,
          cursor: 'pointer',
          fontSize: '1.1rem',
          fontWeight: 700,
          color: 'var(--cec-black)',
        }}
      >
        <span>{title}</span>
        <span
          aria-hidden
          style={{ display: 'inline-block', transition: 'transform .15s', transform: open ? 'rotate(90deg)' : 'none' }}
        >
          ▸
        </span>
      </button>
      {open && <div style={{ padding: '0 1rem 1rem' }}>{children}</div>}
    </section>
  )
}
