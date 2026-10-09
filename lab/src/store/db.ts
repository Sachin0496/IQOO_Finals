/**
 * Clips live in IndexedDB on the device that recorded them. Nothing is uploaded.
 * Crops are stored separately so listing clips stays cheap.
 */
import type { Clip } from '../core/clip'

const DB = 'mouna-lab'
const VERSION = 2

type StoredClip = Omit<Clip, 'crops' | 'features'> & { features: ArrayBuffer[] }

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, VERSION)
    req.onupgradeneeded = (e) => {
      const db = req.result
      if (e.oldVersion < 1) {
        const clips = db.createObjectStore('clips', { keyPath: 'id' })
        clips.createIndex('byPs', ['participant', 'session'])
        db.createObjectStore('crops')
      }
      if (e.oldVersion < 2) db.createObjectStore('voicebank') // key: participant|lang|phrase -> audio Blob
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

const done = (tx: IDBTransaction) =>
  new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })

const request = <T>(r: IDBRequest<T>) =>
  new Promise<T>((resolve, reject) => {
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => reject(r.error)
  })

let dbp: Promise<IDBDatabase> | null = null
const db = () => (dbp ??= open())

export async function saveClip(clip: Clip): Promise<void> {
  const d = await db()
  const tx = d.transaction(['clips', 'crops'], 'readwrite')
  const { crops, features, ...rest } = clip
  const stored: StoredClip = { ...rest, features: features.map((f) => f.slice().buffer) }
  tx.objectStore('clips').put(stored)
  if (crops) tx.objectStore('crops').put(crops.slice().buffer, clip.id)
  await done(tx)
}

export async function deleteClip(id: string): Promise<void> {
  const d = await db()
  const tx = d.transaction(['clips', 'crops'], 'readwrite')
  tx.objectStore('clips').delete(id)
  tx.objectStore('crops').delete(id)
  await done(tx)
}

export async function listClips(participant?: string, session?: string): Promise<Clip[]> {
  const d = await db()
  const store = d.transaction('clips').objectStore('clips')
  const rows: StoredClip[] =
    participant && session ? await request(store.index('byPs').getAll([participant, session])) : await request(store.getAll())
  return rows
    .map((r) => ({ ...r, features: r.features.map((b) => new Float32Array(b)), crops: null }))
    .sort((a, b) => a.createdAt - b.createdAt)
}

export async function getCrops(id: string): Promise<Uint8Array | null> {
  const d = await db()
  const buf: ArrayBuffer | undefined = await request(d.transaction('crops').objectStore('crops').get(id))
  return buf ? new Uint8Array(buf) : null
}

const bankKey = (participant: string, lang: string, phrase: string) => `${participant}|${lang}|${phrase}`

export async function putBankedVoice(participant: string, lang: string, phrase: string, audio: Blob): Promise<void> {
  const d = await db()
  const tx = d.transaction('voicebank', 'readwrite')
  tx.objectStore('voicebank').put(audio, bankKey(participant, lang, phrase))
  await done(tx)
}

export async function getBankedVoice(participant: string, lang: string, phrase: string): Promise<Blob | null> {
  const d = await db()
  return (await request(d.transaction('voicebank').objectStore('voicebank').get(bankKey(participant, lang, phrase)))) ?? null
}

/** Phrases this person has banked, as "lang|phrase" keys. */
export async function bankedPhrases(participant: string): Promise<Set<string>> {
  const d = await db()
  const keys = (await request(d.transaction('voicebank').objectStore('voicebank').getAllKeys())) as string[]
  return new Set(keys.filter((k) => k.startsWith(`${participant}|`)).map((k) => k.slice(participant.length + 1)))
}

/** Consent screen promise: one button removes everything, voice recordings included. */
export async function deleteEverything(): Promise<void> {
  const d = await db()
  const tx = d.transaction(['clips', 'crops', 'voicebank'], 'readwrite')
  tx.objectStore('clips').clear()
  tx.objectStore('crops').clear()
  tx.objectStore('voicebank').clear()
  await done(tx)
}
