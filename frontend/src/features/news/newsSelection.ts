import type { NewsFeedItem } from '../../api/types'

/**
 * The item the inbox should land on: the OLDEST unread item, so the reader works forward in
 * time with "Ler próxima" instead of backwards. With nothing unread there is nothing to work
 * through, so the newest item is shown — which is also what lands after "Avançar" once its
 * fresh result has been read.
 *
 * `items` is newest-first, so the oldest unread is the LAST unread element.
 */
export function initialSelection(items: NewsFeedItem[]): NewsFeedItem | null {
  if (items.length === 0) return null
  const unread = items.filter(it => !it.read)
  return unread.length > 0 ? unread[unread.length - 1] : items[0]
}

/**
 * The item "Ler próxima" should move to: the nearest unread item NEWER than `currentId`
 * (a lower index, since `items` is newest-first). When nothing newer is unread the walk wraps
 * to the oldest unread anywhere in the list, so the button never dead-ends while unread items
 * remain. Null when everything loaded is read.
 */
export function nextUnread(items: NewsFeedItem[], currentId: string | null): NewsFeedItem | null {
  const unread = items.filter(it => !it.read)
  if (unread.length === 0) return null
  const currentIndex = currentId === null ? -1 : items.findIndex(it => it.id === currentId)
  for (let i = currentIndex - 1; i >= 0; i--) {
    if (!items[i].read) return items[i]
  }
  return unread[unread.length - 1]
}
