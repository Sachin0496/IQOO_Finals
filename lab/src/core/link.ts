/**
 * Nurse-call link: a direct device-to-device channel on the same Wi-Fi, with no server and no internet.
 *
 * WebRTC data channel with no ICE servers, so only local (host) candidates are used. Pairing is two codes:
 * the patient's device shows an offer, the caregiver's phone answers. Codes are deflated and base64url-encoded
 * so they fit a QR code.
 */

export type LinkMessage =
  | {
      type: 'phrase'
      phrase: string
      urgent: boolean
      at: number
      /** custom phrases travel with their text: the caregiver's phone does not know them */
      custom?: { text: Record<string, string>; lang: string; given: string[] }
    }
  | { type: 'ack'; phrase: string; at: number }
  /** Ask mode on the patient's device: the question being asked now, or null when it ends */
  | { type: 'ask'; ask: Record<string, string> | null; at: number }

export type LinkStatus = 'idle' | 'waiting' | 'connected' | 'closed'

async function pack(obj: unknown): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify(obj))
  const deflated = await new Response(new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw'))).arrayBuffer()
  let bin = ''
  new Uint8Array(deflated).forEach((b) => (bin += String.fromCharCode(b)))
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function unpack<T>(code: string): Promise<T> {
  const b64 = code.trim().replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
  const text = await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).text()
  return JSON.parse(text) as T
}

/** Resolves once ICE gathering finishes (local candidates only, so this is quick), or after a short timeout. */
function gathered(pc: RTCPeerConnection, ms = 2500): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise((resolve) => {
    const done = () => pc.iceGatheringState === 'complete' && resolve()
    pc.addEventListener('icegatheringstatechange', done)
    setTimeout(resolve, ms)
  })
}

export class NurseLink {
  status: LinkStatus = 'idle'
  role: 'patient' | 'caregiver' | null = null
  private pc: RTCPeerConnection | null = null
  private channel: RTCDataChannel | null = null
  private listeners = new Set<() => void>()
  onMessage: ((m: LinkMessage) => void) | null = null

  subscribe = (fn: () => void) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }
  /** Notify the UI (also used when the role is chosen before any connection exists). */
  emit() {
    this.listeners.forEach((l) => l())
  }
  private setStatus(s: LinkStatus) {
    this.status = s
    this.emit()
  }

  private fresh(): RTCPeerConnection {
    this.close()
    const pc = new RTCPeerConnection({ iceServers: [] }) // local network only: no STUN, no TURN, no server
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') this.setStatus('closed')
    }
    this.pc = pc
    return pc
  }

  private attach(ch: RTCDataChannel) {
    this.channel = ch
    ch.onopen = () => this.setStatus('connected')
    ch.onclose = () => this.setStatus('closed')
    ch.onmessage = (e) => this.onMessage?.(JSON.parse(e.data) as LinkMessage)
  }

  /** Patient's device: returns the offer code to show. */
  async offer(): Promise<string> {
    const pc = this.fresh()
    this.role = 'patient'
    this.attach(pc.createDataChannel('mouna'))
    await pc.setLocalDescription(await pc.createOffer())
    await gathered(pc)
    this.setStatus('waiting')
    return pack({ k: 'o', sdp: pc.localDescription!.sdp })
  }

  /** Caregiver's phone: takes the patient's code, returns the answer code to show back. */
  async answer(offerCode: string): Promise<string> {
    const { sdp } = await unpack<{ sdp: string }>(offerCode)
    const pc = this.fresh()
    this.role = 'caregiver'
    pc.ondatachannel = (e) => this.attach(e.channel)
    await pc.setRemoteDescription({ type: 'offer', sdp })
    await pc.setLocalDescription(await pc.createAnswer())
    await gathered(pc)
    this.setStatus('waiting')
    return pack({ k: 'a', sdp: pc.localDescription!.sdp })
  }

  /** Patient's device: completes pairing with the caregiver's answer code. */
  async complete(answerCode: string): Promise<void> {
    const { sdp } = await unpack<{ sdp: string }>(answerCode)
    await this.pc!.setRemoteDescription({ type: 'answer', sdp })
  }

  send(m: LinkMessage): boolean {
    if (this.channel?.readyState !== 'open') return false
    this.channel.send(JSON.stringify(m))
    return true
  }

  close(): void {
    this.channel?.close()
    this.pc?.close()
    this.channel = null
    this.pc = null
    this.role = null
    this.setStatus('idle')
  }
}

/** One link per page. */
export const link = new NurseLink()
