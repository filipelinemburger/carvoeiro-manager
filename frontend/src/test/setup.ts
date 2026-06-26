import '@testing-library/jest-dom'
import { beforeEach } from 'vitest'

// Node 26 / jsdom: mock localStorage since it requires --localstorage-file flag
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => { store[key] = value },
    removeItem: (key: string) => { delete store[key] },
    clear: () => { store = {} },
    get length() { return Object.keys(store).length },
    key: (index: number) => Object.keys(store)[index] ?? null,
  }
})()

Object.defineProperty(globalThis, 'localStorage', {
  value: localStorageMock,
  writable: true,
})

// Reset localStorage before each test to prevent state leakage
beforeEach(() => {
  localStorage.clear()
})
