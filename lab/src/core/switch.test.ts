import { describe, expect, it } from 'vitest'
import { PersonalSwitch, teachSwitch, type SwitchModel } from './switch'
import { calibrateGaze, GazeSelector, type GazeModel } from './gaze'

// 52 blendshape-like channels: channel 3 is the person's eyebrow, channel 10 moves a lot while they mouth
let seed = 1
const rand = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) - 0.5
const frame = (brow: number, mouth: number) => Array.from({ length: 52 }, (_, c) => 0.05 + 0.01 * rand() + (c === 3 ? brow : 0) + (c === 10 ? mouth : 0))
const rest = Array.from({ length: 90 }, (_, t) => frame(0, 0.3 * Math.abs(Math.sin(t / 3))))
const move = () => Array.from({ length: 20 }, (_, t) => frame(0.5 * Math.sin((Math.PI * t) / 19), 0))

describe('personal switch', () => {
  it('learns the moving channel and ignores the one that moves while mouthing', () => {
    const m = teachSwitch(rest, [move(), move(), move()]) as SwitchModel
    expect(m.channels.map((c) => c.index)).toEqual([3])
  })
  it('presses once per movement, never while mouthing', () => {
    const sw = new PersonalSwitch(teachSwitch(rest, [move(), move(), move()]) as SwitchModel)
    let t = 0
    let presses = 0
    for (const f of [...rest, ...move(), ...rest, ...move(), ...rest]) presses += sw.push(f, (t += 40)) ? 1 : 0
    expect(presses).toBe(2)
  })
  it('says so when nothing moved', () => {
    expect('error' in teachSwitch(rest, [rest.slice(0, 20), rest.slice(20, 40)])).toBe(true)
  })
})

describe('look to choose', () => {
  const g = calibrateGaze(Array(9).fill(0.5), Array(9).fill(0.6), Array(9).fill(0.4)) as GazeModel
  it('selects a side after the dwell, once', () => {
    const s = new GazeSelector(g)
    const picks: string[] = []
    for (let t = 0; t <= 1500; t += 40) {
      const p = s.push(t < 200 ? 0.5 : 0.59, t)
      if (p) picks.push(p)
    }
    expect(picks).toEqual(['left'])
  })
  it('rejects a calibration where left and right look the same', () => {
    expect('error' in calibrateGaze(Array(9).fill(0.5), Array(9).fill(0.6), Array(9).fill(0.61))).toBe(true)
  })
})
