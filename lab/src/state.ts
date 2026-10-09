import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import type { Clip, ClipKind } from './core/clip'
import type { ClipBuilder } from './core/clip'
import { checkQuality } from './core/gate'
import type { Lang } from './core/phrases'
import { FewShotDTW } from './core/recognizer'
import { toSequence } from './core/sequence'
import { Engine } from './engine'
import { getCrops, listClips } from './store/db'
import { PrototypeHead } from './core/prototype'
import { loadEncoder, type Encoder } from './vision/encoder'

export const engine = new Engine()

export const useSnapshot = () => useSyncExternalStore(engine.subscribe, engine.getSnapshot)

/** useState that survives reloads. localStorage can throw (private mode); then it is per-tab only. */
export function usePersisted<T>(key: string, initial: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(`mouna.${key}`)
      return raw === null ? initial : (JSON.parse(raw) as T)
    } catch {
      return initial
    }
  })
  const set = useCallback(
    (v: T) => {
      setValue(v)
      try {
        localStorage.setItem(`mouna.${key}`, JSON.stringify(v))
      } catch {
        /* per-tab only */
      }
    },
    [key],
  )
  return [value, set]
}

export interface Who {
  participant: string
  session: string
  spokenLang: Lang
}

export function finalizeClip(b: ClipBuilder, who: Who, kind: ClipKind, phrase: string | null, rep: number): Clip {
  const body = b.finish()
  return {
    ...body,
    id: crypto.randomUUID(),
    participant: who.participant,
    session: who.session,
    spokenLang: who.spokenLang,
    kind,
    phrase,
    rep,
    createdAt: Date.now(),
    issues: checkQuality(body.stats),
  }
}

/** All clips of one participant, newest last, with a reload handle. */
export function useClips(participant: string) {
  const [clips, setClips] = useState<Clip[]>([])
  const reload = useCallback(() => {
    listClips().then((all) => setClips(all.filter((c) => c.participant === participant)))
  }, [participant])
  useEffect(reload, [reload])
  return { clips, reload }
}

/** Plan B recogniser built from this person's clean Teach clips. */
export function useRecognizer(clips: Clip[]) {
  return useMemo(() => {
    const r = new FewShotDTW()
    for (const c of clips) if (c.kind === 'teach' && c.phrase && c.issues.length === 0) r.add(c.phrase, toSequence(c.t, c.features))
    r.calibrate()
    return r
  }, [clips])
}

export type EncoderState =
  | { status: 'off' | 'loading' | 'unavailable' }
  | { status: 'embedding'; done: number; total: number }
  | { status: 'ready'; head: PrototypeHead; encoder: Encoder }
  | { status: 'error'; message: string }

const embeddings = new Map<string, Float32Array>() // clip id -> embedding, for this page's lifetime

/** Plan A: the lip encoder + prototype head, built from this person's clean Teach clips (their crops). */
export function useEncoderRecognizer(clips: Clip[], enabled: boolean): EncoderState {
  const [state, setState] = useState<EncoderState>({ status: 'off' })
  useEffect(() => {
    if (!enabled) return setState({ status: 'off' })
    let cancelled = false
    ;(async () => {
      setState({ status: 'loading' })
      const encoder = await loadEncoder()
      if (!encoder) return !cancelled && setState({ status: 'unavailable' })
      const teach = clips.filter((c) => c.kind === 'teach' && c.phrase && c.issues.length === 0)
      const head = new PrototypeHead()
      for (const [i, c] of teach.entries()) {
        if (cancelled) return
        setState({ status: 'embedding', done: i, total: teach.length })
        let e = embeddings.get(c.id)
        if (!e) {
          const crops = await getCrops(c.id)
          if (!crops) continue
          e = (await encoder.embed(crops, c.t)).embedding
          embeddings.set(c.id, e)
        }
        head.add(c.phrase!, e)
      }
      head.calibrate()
      if (!cancelled) setState({ status: 'ready', head, encoder })
    })().catch((e) => !cancelled && setState({ status: 'error', message: e instanceof Error ? e.message : String(e) }))
    return () => {
      cancelled = true
    }
  }, [clips, enabled])
  return state
}
