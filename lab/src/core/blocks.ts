/**
 * Blocks to sentences: teach a few single words once, then mouth two of them in a row to say one of many sentences.
 *
 * Each block is recognised on its own; the pair is then matched against a fixed, human-written sentence list by the
 * smallest summed distance, exactly as measured in harness/mouna_harness/openset.py (Kannada, encoder: two blocks
 * right 69% one by one, 89% with the list). The sentence is always shown and confirmed before it is spoken. A pair
 * that is on no list is offered as the plain words ("fan, off"), never as an invented sentence.
 */
import pack from './blocks.json'
import { registerPhrase, type Lang, type Phrase } from './phrases'
import type { Decision } from './recognizer'

const ALL: Lang[] = ['en', 'ta', 'kn', 'hi']
// Blocks, sentences and plain words are registered phrases in every language, so they need no custom marker.
export const blockId = (b: string) => `b_${b}`

export const BLOCKS: Phrase[] = pack.blocks.map((b) => ({ id: blockId(b.id), urgent: false, text: b.word }))
BLOCKS.forEach(registerPhrase)
export const BLOCK_IDS = BLOCKS.map((b) => b.id)

export interface Sentence {
  id: string
  blocks: string[] // block phrase ids
  phrase: Phrase
}

export const SENTENCES: Sentence[] = pack.sentences.map((s) => {
  const id = `s_${s.blocks.join('_')}`
  const phrase: Phrase = { id, urgent: false, text: s.text }
  registerPhrase(phrase)
  return { id, blocks: s.blocks.map(blockId), phrase }
})

export interface Reading {
  /** listed sentences of this length, best first, with the summed distance */
  ranked: { sentence: Sentence; cost: number }[]
  /** every block of the best sentence is within its recogniser's speak threshold */
  sure: boolean
  /** each block's own best guess, for the plain-words fallback */
  words: string[]
}

/** Reads a row of recognised blocks against the sentence list. */
export function readBlocks(row: Decision[]): Reading {
  const dist = row.map((d) => new Map(d.ranked.map((r) => [r.phrase, r.distance])))
  const ranked = SENTENCES.filter((s) => s.blocks.length === row.length)
    .map((sentence) => ({ sentence, cost: sentence.blocks.reduce((a, b, i) => a + (dist[i].get(b) ?? Infinity), 0) }))
    .filter((r) => Number.isFinite(r.cost))
    .sort((a, b) => a.cost - b.cost)
  const best = ranked[0]?.sentence
  const sure = !!best && best.blocks.every((b, i) => (dist[i].get(b) ?? Infinity) <= row[i].threshold)
  const words = row.map((d) => d.ranked.find((r) => BLOCK_IDS.includes(r.phrase))?.phrase ?? d.ranked[0]?.phrase ?? '')
  return { ranked, sure, words }
}

/** The plain-words fallback as one phrase: "fan, off", in every language. */
export function plainWords(words: string[]): Phrase {
  const block = (id: string) => BLOCKS.find((b) => b.id === id)
  const text = Object.fromEntries(ALL.map((l) => [l, words.map((w) => block(w)?.text[l] ?? w).join(', ')])) as Record<Lang, string>
  const p: Phrase = { id: `w_${words.join('_')}`, urgent: false, text }
  registerPhrase(p)
  return p
}
