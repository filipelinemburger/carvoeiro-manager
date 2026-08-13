import { describe, it, expect } from 'vitest'
import { initialSelection, nextUnread } from './newsSelection'
import type { NewsFeedItem } from '../../api/types'

// Only `id` and `read` matter to these rules; the payload is irrelevant, so it is cast in.
const item = (id: string, read: boolean): NewsFeedItem =>
  ({ id, read, payload: {} as NewsFeedItem['payload'] })

// Newest first, matching the API's ordering.
const FEED = [item('n3', false), item('n2', false), item('n1', true)]

describe('initialSelection', () => {
  it('returns the oldest unread item', () => {
    expect(initialSelection(FEED)?.id).toBe('n2')
  })

  it('returns the newest item when everything is read', () => {
    expect(initialSelection([item('n3', true), item('n2', true)])?.id).toBe('n3')
  })

  it('returns null for an empty feed', () => {
    expect(initialSelection([])).toBeNull()
  })
})

describe('nextUnread', () => {
  it('returns the nearest unread item newer than the current selection', () => {
    const feed = [item('n4', false), item('n3', true), item('n2', false), item('n1', true)]
    expect(nextUnread(feed, 'n2')?.id).toBe('n4')
  })

  it('falls back to the oldest unread when nothing newer is unread', () => {
    const feed = [item('n4', true), item('n3', true), item('n2', false), item('n1', false)]
    expect(nextUnread(feed, 'n4')?.id).toBe('n1')
  })

  it('returns the oldest unread when there is no current selection', () => {
    expect(nextUnread(FEED, null)?.id).toBe('n2')
  })

  it('returns null when nothing is unread', () => {
    expect(nextUnread([item('n2', true), item('n1', true)], 'n2')).toBeNull()
  })

  it('returns null for an empty feed', () => {
    expect(nextUnread([], null)).toBeNull()
  })
})
