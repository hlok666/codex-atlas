const DEFAULT_PAGE_CHARS = 520
const MAX_STREAMING_REPLY_CHARS = 64 * 1024

/**
 * Keeps the response readable in the CRT while bounding pathological output
 * from a tool or a malformed rollout record.
 */
export function normalizeFloatingReply(value: string, maxChars = MAX_STREAMING_REPLY_CHARS): string {
  return value
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .trim()
    .slice(0, maxChars)
}

/**
 * Joins an app-server text delta onto the reply being rendered for a turn.
 * Codex normally sends true deltas, but reconnects can replay a cumulative
 * prefix. Treat that prefix as a replacement so the CRT never doubles text.
 */
export function appendFloatingReply(previous = '', incoming = '', maxChars = MAX_STREAMING_REPLY_CHARS): string {
  const prior = previous.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const next = incoming.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  if (!next) return prior.slice(0, maxChars)
  if (!prior || next === prior || prior.endsWith(next)) return (prior || next).slice(0, maxChars)
  if (next.startsWith(prior) || prior.startsWith(next)) return (next.length >= prior.length ? next : prior).slice(0, maxChars)
  return `${prior}${next}`.slice(0, maxChars)
}

/**
 * Splits a complete assistant reply into stable pages for the idle carousel.
 * Paragraphs stay together when possible, while long code or prose lines wrap
 * at a word boundary before falling back to a hard character boundary.
 */
export function splitFloatingReply(value: string, pageChars = DEFAULT_PAGE_CHARS): string[] {
  const normalized = normalizeFloatingReply(value)
  const limit = Math.max(120, Math.floor(pageChars))
  if (!normalized) return []
  if (normalized.length <= limit) return [normalized]

  const pages: string[] = []
  let page = ''
  const pushPage = () => {
    const next = page.trim()
    if (next) pages.push(next)
    page = ''
  }
  const splitLongChunk = (chunk: string) => {
    const pieces: string[] = []
    let rest = chunk.trim()
    while (rest.length > limit) {
      let cut = rest.lastIndexOf(' ', limit)
      if (cut < Math.floor(limit * 0.55)) cut = limit
      pieces.push(rest.slice(0, cut).trim())
      rest = rest.slice(cut).trim()
    }
    if (rest) pieces.push(rest)
    return pieces
  }
  const appendChunk = (chunk: string) => {
    const value = chunk.trim()
    if (!value) return
    for (const piece of value.length > limit ? splitLongChunk(value) : [value]) {
      if (!page) {
        page = piece
        continue
      }
      if (page.length + 2 + piece.length <= limit) {
        page += `\n\n${piece}`
        continue
      }
      pushPage()
      page = piece
    }
  }

  for (const paragraph of normalized.split(/\n{2,}/)) {
    appendChunk(paragraph)
  }
  pushPage()
  return pages.length ? pages : [normalized]
}
