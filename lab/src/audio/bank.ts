/**
 * Voice banking: before surgery, the person says each phrase aloud while teaching; afterwards Mouna speaks with
 * their own recorded voice. Recordings stay in this browser (IndexedDB), like every other clip.
 */
import type { Lang } from '../core/phrases'
import { getBankedVoice, putBankedVoice } from '../store/db'

/** Records the microphone between start() and stop(). */
export class VoiceRecorder {
  private stream: MediaStream | null = null
  private rec: MediaRecorder | null = null
  private chunks: Blob[] = []

  async start(): Promise<void> {
    this.stream ??= await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
    this.chunks = []
    this.rec = new MediaRecorder(this.stream)
    this.rec.ondataavailable = (e) => e.data.size && this.chunks.push(e.data)
    this.rec.start()
  }

  stop(): Promise<Blob | null> {
    const rec = this.rec
    this.rec = null
    if (!rec || rec.state === 'inactive') return Promise.resolve(null)
    return new Promise((resolve) => {
      rec.onstop = () => resolve(this.chunks.length ? new Blob(this.chunks, { type: rec.mimeType }) : null)
      rec.stop()
    })
  }

  close(): void {
    this.stream?.getTracks().forEach((t) => t.stop())
    this.stream = null
  }
}

export const bankVoice = (participant: string, lang: Lang, phrase: string, audio: Blob) => putBankedVoice(participant, lang, phrase, audio)

/** Plays the person's own recording; returns false when there is none for this phrase and language. */
export async function playBankedVoice(participant: string, lang: Lang, phrase: string): Promise<boolean> {
  const blob = await getBankedVoice(participant, lang, phrase)
  if (!blob) return false
  const url = URL.createObjectURL(blob)
  const audio = new Audio(url)
  audio.onended = () => URL.revokeObjectURL(url)
  try {
    await audio.play()
    return true
  } catch {
    return false
  }
}
