/**
 * The name the PNG is offered under.
 *
 * Built from the title the user typed, so a folder full of exports can be told apart
 * without opening them, plus the date for the same reason. Umlauts are kept -- every
 * current file system stores them, and mangling German words into ASCII would make the
 * name harder to read, not safer.
 */

/**
 * Characters a Windows or macOS file name cannot carry. Line breaks and tabs need no
 * entry of their own -- the whitespace pass below already turns them into separators.
 */
const ILLEGAL = /[\\/:*?"<>|]/g

const MAX_BASE_LENGTH = 80

function twoDigits(value: number): string {
  return String(value).padStart(2, '0')
}

/** Local date, not `toISOString`: an export at 23:30 belongs to that day, not the next. */
function isoDate(now: Date): string {
  return `${now.getFullYear()}-${twoDigits(now.getMonth() + 1)}-${twoDigits(now.getDate())}`
}

export function imageFilename(title: string, now = new Date()): string {
  const base = title
    .replace(ILLEGAL, '-')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .slice(0, MAX_BASE_LENGTH)
    // After the cut, not before: truncating a long name can leave the separator dangling.
    .replace(/^-|-$/g, '')
  // An empty or punctuation-only title still has to produce a usable name.
  const stem = base.length > 0 ? base : 'Karte'
  return `${stem}_${isoDate(now)}.png`
}
