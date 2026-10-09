/**
 * Speech output through the browser's voices. The Android build will use pre-rendered clips;
 * here we also report which of the four languages have a voice on this device (spike test S5).
 */
import { LANGS, type Lang } from '../core/phrases'

export function voiceFor(lang: Lang): SpeechSynthesisVoice | null {
  if (!('speechSynthesis' in window)) return null
  const tag = LANGS[lang].bcp47.toLowerCase()
  const prefix = tag.split('-')[0]
  const voices = speechSynthesis.getVoices()
  return (
    voices.find((v) => v.lang.toLowerCase().replace('_', '-') === tag && v.localService) ??
    voices.find((v) => v.lang.toLowerCase().replace('_', '-') === tag) ??
    voices.find((v) => v.lang.toLowerCase().startsWith(prefix)) ??
    null
  )
}

export function voiceReport(): Record<Lang, { available: boolean; offline: boolean; name: string | null }> {
  const out = {} as Record<Lang, { available: boolean; offline: boolean; name: string | null }>
  for (const lang of Object.keys(LANGS) as Lang[]) {
    const v = voiceFor(lang)
    out[lang] = { available: !!v, offline: !!v?.localService, name: v?.name ?? null }
  }
  return out
}

/** Voices load asynchronously in Chrome; resolve once they are there (or after a short wait). */
export function voicesReady(): Promise<void> {
  if (!('speechSynthesis' in window) || speechSynthesis.getVoices().length) return Promise.resolve()
  return new Promise((resolve) => {
    const finish = () => resolve()
    speechSynthesis.addEventListener('voiceschanged', finish, { once: true })
    setTimeout(finish, 1500)
  })
}

export function speak(text: string, lang: Lang): void {
  if (!('speechSynthesis' in window)) return
  speechSynthesis.cancel()
  const u = new SpeechSynthesisUtterance(text)
  u.lang = LANGS[lang].bcp47
  const v = voiceFor(lang)
  if (v) u.voice = v
  u.rate = 0.95
  speechSynthesis.speak(u)
}

/** Two-tone alarm for urgent phrases, synthesised so it needs no asset. */
export function alarm(): void {
  const ctx = new AudioContext()
  const now = ctx.currentTime
  for (let i = 0; i < 3; i++) {
    const o = ctx.createOscillator()
    const g = ctx.createGain()
    o.frequency.value = i % 2 ? 660 : 880
    g.gain.setValueAtTime(0.0001, now + i * 0.22)
    g.gain.exponentialRampToValueAtTime(0.25, now + i * 0.22 + 0.02)
    g.gain.exponentialRampToValueAtTime(0.0001, now + i * 0.22 + 0.2)
    o.connect(g).connect(ctx.destination)
    o.start(now + i * 0.22)
    o.stop(now + i * 0.22 + 0.21)
  }
  setTimeout(() => ctx.close(), 1000)
}

/* ---------- pre-rendered voice pack (voices/render.py) ---------- */

export interface VoiceEntry {
  id: string
  label: string
  engine: string
  clips: Partial<Record<Lang, Record<string, string>>>
}

const BASE = import.meta.env.BASE_URL
let manifest: Promise<VoiceEntry[]> | null = null

/** Voices shipped with the app. Empty when none were rendered; then the device voice is used. */
export function voicePack(): Promise<VoiceEntry[]> {
  manifest ??= fetch(`${BASE}voices/manifest.json`)
    .then((r) => (r.ok ? r.json() : { voices: [] }))
    .then((m: { voices: VoiceEntry[] }) => m.voices)
    .catch(() => [])
  return manifest
}

/** Speaks a phrase with the chosen shipped voice, falling back to the device's voice. */
export async function sayPhrase(phraseId: string, text: string, lang: Lang, voiceId: string): Promise<void> {
  const rel = (await voicePack()).find((v) => v.id === voiceId)?.clips[lang]?.[phraseId]
  if (!rel) return speak(text, lang)
  speechSynthesis?.cancel()
  const audio = new Audio(`${BASE}voices/${rel}`)
  try {
    await audio.play()
  } catch {
    speak(text, lang)
  }
}
