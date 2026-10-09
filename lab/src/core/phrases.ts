/**
 * Starter phrase pack. A phrase is a concept with fixed, human-checked labels per language.
 * No machine translation at runtime. The data lives in phrase-pack.json, shared with the voice renderer
 * (voices/render.py) and the Android build. Native-speaker review is still pending.
 */
import pack from './phrase-pack.json'

export type Lang = 'en' | 'ta' | 'kn' | 'hi'

export const LANGS: Record<Lang, { name: string; native: string; bcp47: string }> = {
  en: { name: 'English', native: 'English', bcp47: 'en-IN' },
  ta: { name: 'Tamil', native: 'தமிழ்', bcp47: 'ta-IN' },
  kn: { name: 'Kannada', native: 'ಕನ್ನಡ', bcp47: 'kn-IN' },
  hi: { name: 'Hindi', native: 'हिन्दी', bcp47: 'hi-IN' },
}

export interface Phrase {
  id: string
  urgent: boolean
  /** Every language is filled; for a custom phrase, missing translations repeat the original text. */
  text: Record<Lang, string>
  /** Present on phrases the person created: the language it was written in, and the languages actually given. */
  custom?: { lang: Lang; given: Lang[] }
}

/** A phrase the person created: their own words, with any translations they typed. No machine translation. */
export interface CustomPhrase {
  id: string
  urgent: boolean
  lang: Lang
  text: Partial<Record<Lang, string>>
}

export const PHRASES: Phrase[] = pack.phrases

/** Five phrases of distinct lengths for the live demo. */
export const DEMO_SET: string[] = pack.demo

/** Eight phrases for the spike protocol (docs/protocol.md). */
export const PROTOCOL_SET: string[] = pack.protocol

/** Three long, visually distinct phrases: the fastest way for a first-time user to see it work. */
export const QUICK_SET = ['water', 'nurse', 'thank_you']

let CUSTOM: Phrase[] = []

/** Registers this person's own phrases (called by the app whenever the list changes). */
export function setCustomPhrases(list: CustomPhrase[]): void {
  CUSTOM = list.map((c) => {
    const original = c.text[c.lang]?.trim() ?? ''
    const given = (Object.keys(LANGS) as Lang[]).filter((l) => c.text[l]?.trim())
    const text = Object.fromEntries((Object.keys(LANGS) as Lang[]).map((l) => [l, c.text[l]?.trim() || original])) as Record<Lang, string>
    return { id: c.id, urgent: c.urgent, text, custom: { lang: c.lang, given } }
  })
}

export const customPhraseIds = (): string[] => CUSTOM.map((p) => p.id)

/** Phrases reached another way than lips, such as Ask mode answers; known by id like any other. */
const EXTRA = new Map<string, Phrase>()
export const registerPhrase = (p: Phrase): void => void EXTRA.set(p.id, p)

export const phraseById = (id: string): Phrase => {
  const p = PHRASES.find((x) => x.id === id) ?? CUSTOM.find((x) => x.id === id) ?? EXTRA.get(id)
  // a deleted custom phrase can still appear in old clips or history
  return p ?? { id, urgent: false, text: { en: '(deleted phrase)', ta: '(deleted phrase)', kn: '(deleted phrase)', hi: '(deleted phrase)' } }
}

/**
 * What to say for `lang`, and in which voice language. A custom phrase without a translation for `lang` is spoken
 * in the language it was written in, so a voice never reads out another script.
 */
export function speakable(p: Phrase, lang: Lang): { text: string; lang: Lang } {
  if (p.custom && !p.custom.given.includes(lang)) return { text: p.text[p.custom.lang], lang: p.custom.lang }
  return { text: p.text[lang], lang }
}
