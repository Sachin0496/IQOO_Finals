/**
 * Ask mode: reaching a message nobody taught, with yes and no alone.
 *
 * Partner-assisted scanning, done by the device: Mouna asks short questions from a fixed tree built from what
 * ventilated patients most need to say, the person answers yes or no (nod, double blink or tap), and the answer
 * at the end of the path is spoken to the caregiver. No language model plans the questions: small models are poor
 * at twenty questions, and a fixed tree is predictable for patient and nurse alike.
 */
import pack from './ask-tree.json'
import { phraseById, registerPhrase, type Lang, type Phrase } from './phrases'

export interface AskNode {
  id: string
  ask: Record<Lang, string>
  urgent: boolean
  /** a built-in phrase (so its natural voice is used), or the words to say */
  phrase?: string
  say?: Record<Lang, string>
  children?: AskNode[]
}

export const ASK_TREE: AskNode[] = pack.tree as AskNode[]
export const ASK_START: Record<Lang, string> = pack.start
export const ASK_NONE: Record<Lang, string> = pack.none

export type AskResult = { kind: 'answer'; node: AskNode } | { kind: 'none' }

/** What a finished path says, as a phrase the rest of the app can speak and send. */
export function answerPhrase(r: AskResult): Phrase {
  if (r.kind === 'none') return { id: 'ask_none', urgent: false, text: ASK_NONE }
  const n = r.node
  if (n.phrase) return phraseById(n.phrase)
  return { id: `ask_${n.id}`, urgent: n.urgent, text: n.say! }
}

// every answer is a phrase the rest of the app knows by id (history, nurse link, repeat)
const leaves = (ns: AskNode[]): AskNode[] => ns.flatMap((n) => (n.children ? leaves(n.children) : [n]))
leaves(ASK_TREE).forEach((node) => node.phrase || registerPhrase(answerPhrase({ kind: 'answer', node })))
registerPhrase(answerPhrase({ kind: 'none' }))

export class AskSession {
  /** the lists we descended through, and where we were in each */
  private stack: { list: AskNode[]; index: number }[] = []
  private list: AskNode[]
  private index = 0
  result: AskResult | null = null
  /** how many questions were asked: the cost of reaching the answer */
  asked = 1

  constructor(tree: AskNode[] = ASK_TREE) {
    this.list = tree
  }

  get current(): AskNode | null {
    return this.result ? null : this.list[this.index]
  }
  /** the questions at this level, for showing what comes next */
  get options(): AskNode[] {
    return this.list
  }
  get position(): number {
    return this.index
  }
  /** the categories chosen so far */
  get path(): AskNode[] {
    return this.stack.map((s) => s.list[s.index])
  }

  yes(): void {
    const n = this.current
    if (!n) return
    if (n.children?.length) {
      this.stack.push({ list: this.list, index: this.index })
      this.list = n.children
      this.index = 0
      this.asked++
    } else this.result = { kind: 'answer', node: n }
  }

  /** Next question; past the end of a group, back to the next group up; past the end of everything, none. */
  no(): void {
    if (this.result) return
    this.index++
    while (this.index >= this.list.length) {
      const up = this.stack.pop()
      if (!up) {
        this.result = { kind: 'none' }
        return
      }
      this.list = up.list
      this.index = up.index + 1
    }
    this.asked++
  }

  /** Undo the last "yes" into a group: back to that group's question. */
  back(): void {
    const up = this.stack.pop()
    if (!up) return
    this.list = up.list
    this.index = up.index
    this.result = null
    this.asked++
  }
}
