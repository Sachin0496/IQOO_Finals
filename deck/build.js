/**
 * Mouna Phase 1 deck. Run `npm run build` in deck/.
 *
 * Rules this generator enforces:
 *  - Slide titles are claims, so the deck reads correctly from titles alone.
 *  - Every number on a slide has a source in the notes, or comes from data/spike-results.json
 *    (written by the harness, which refuses synthetic data). Targets are labelled as targets.
 *  - All text is real text (no text baked into images) so people and machines can read it.
 */
const fs = require('fs')
const path = require('path')
const pptxgen = require('pptxgenjs')
const { applyTheme } = require('./apply_theme.js')

const OUT = path.join(__dirname, 'Mouna-Phase1-Deck.pptx')
const RESULTS = path.join(__dirname, 'data', 'spike-results.json')
const pct = (v) => (v === null || v === undefined ? '—' : `${(100 * v).toFixed(1)}%`)
const ENCODER = JSON.parse(fs.readFileSync(path.join(__dirname, 'data', 'encoder-report.json'), 'utf8'))
const ENC_1S = ENCODER.cpu_bench.rows.find((r) => r.frames === 25).median_ms
const readJson = (name) => {
  const p = path.join(__dirname, 'data', name)
  return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, 'utf8')) : null
}
const KN_B = readJson('kannada-planb.json') // lip geometry + DTW
const KN_A = readJson('kannada-plana.json') // LipLearner encoder + prototypes
const DEVICE = readJson('device-report.json') // Qualcomm AI Hub, Galaxy S26 (SM8850)
const KB = readJson('kannada-plana-breakdown.json') // words vs phrases
const STATIC = readJson('encoder-static.json') // fp32 vs int8 size and accuracy, fixed 48-frame encoder
const CURVE = readJson('kannada-curve.json') // 1 / 3 / 5 shots, identical held-out clips
const OPEN = readJson('kannada-openset-plana.json') // untaught words and chained blocks, encoder
const OPEN_B = readJson('kannada-openset-planb.json') // the same, lip geometry
const UNTAUGHT = OPEN.small_vocabulary_8.untaught_words.spoken_as_a_taught_phrase
const CAREFUL = OPEN.strictness_curve.find((r) => r.strictness === 0.7)
const CAREFUL_B = OPEN_B.strictness_curve.find((r) => r.strictness === 0.7)
const PAIRS = OPEN.chains['2_blocks']
const shot = (k) => CURVE.curve.find((c) => c.shots === k)
const NPU_MS = DEVICE.runs.static_npu.median_ms.toFixed(1) // whole encoder, 48-frame window
const PROOF = `${pct(shot(5).top1)} top-1 on 7 Kannada speakers  ·  whole encoder ${NPU_MS} ms on the iQOO 15’s NPU  ·  no internet permission`

const THEME = {
  name: 'Mouna',
  headFontFace: 'Bodoni MT',
  bodyFontFace: 'Segoe UI Semilight',
  colors: {
    dk1: '0E0D0B', // ink: background
    lt1: 'EDE6D6', // bone: text
    dk2: '1F1D19', // card
    lt2: 'B9B2A3', // secondary text
    accent1: 'F0AA2E', // turmeric: heard / ours
    accent2: 'E4472B', // kumkum: urgent / risk
    accent3: '8FBF8A', // leaf: pass
    accent4: '8A8478', // mute
    accent5: '3D3A33', // rule
    accent6: '2C2924', // card edge
    hlink: 'F0AA2E',
    folHlink: 'B9B2A3',
  },
}
const HEAD = THEME.headFontFace
const MONO = 'Consolas' // labels, echoing the Lab's mono
// Static fonts only: PowerPoint substitutes variable fonts (Bahnschrift, Sitka) when exporting PDF.
const SCRIPT = 'Nirmala UI' // ships with Windows and Office; covers Tamil, Kannada, Devanagari

const pres = new pptxgen()
pres.layout = 'LAYOUT_WIDE' // 13.33 x 7.5 in
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace }
pres.title = 'Mouna: a voice without sound'
pres.subject = 'iQOO Hackathon, Open Innovation track. Phone app that speaks silently mouthed phrases aloud in Indian languages, offline, on the NPU.'
pres.author = 'Team Highest in the Room: Maadhav V H, Sachin A S, Nakul T'
pres.company = 'Team Highest in the Room'

const C = pres.SchemeColor
const INK = C.text1
const BONE = C.background1
const DIM = C.background2
const CARD = C.text2
const TUR = C.accent1
const KUM = C.accent2
const LEAF = C.accent3
const MUTE = C.accent4
const RULE = C.accent5

const W = 13.333
const M = 0.6 // side margin

// ---------- layouts ----------

pres.defineSlideMaster({
  title: 'COVER',
  background: { color: INK },
  objects: [
    { placeholder: { options: { name: 'title', type: 'title', x: M, y: 2.2, w: 8.5, h: 1.6, fontFace: HEAD, fontSize: 88, italic: true, color: BONE, valign: 'bottom', align: 'left', margin: 0 }, text: '' } },
    { placeholder: { options: { name: 'body', type: 'body', x: M, y: 4.05, w: 8.5, h: 1.4, fontSize: 20, color: DIM, valign: 'top', align: 'left', margin: 0 }, text: '' } },
  ],
})

pres.defineSlideMaster({
  title: 'CONTENT',
  background: { color: INK },
  margin: [0.5, M, 0.6, M],
  objects: [
    { placeholder: { options: { name: 'kicker', type: 'body', x: M, y: 0.38, w: 8, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: TUR, margin: 0 }, text: '' } },
    { placeholder: { options: { name: 'title', type: 'title', x: M, y: 0.72, w: W - 2 * M, h: 1.05, fontFace: HEAD, fontSize: 30, color: BONE, valign: 'top', align: 'left', margin: 0 }, text: '' } },
    { text: { text: 'Mouna  ·  iQOO Hackathon  ·  Open Innovation', options: { x: M, y: 7.0, w: 6, h: 0.3, fontSize: 9, fontFace: MONO, color: MUTE, margin: 0 } } },
  ],
  slideNumber: { x: W - M - 0.6, y: 7.0, w: 0.6, h: 0.3, fontSize: 9, fontFace: MONO, color: MUTE, align: 'right' },
})

// ---------- helpers ----------

const text = (slide, t, o) => slide.addText(t, { isTextBox: true, margin: 0, color: BONE, fontSize: 15, valign: 'top', ...o })

const sections = new Set(['Pitch'])
let kickerNo = 0
function content(kicker, title, section) {
  if (/^\d\d · /.test(kicker)) kicker = `${String(++kickerNo).padStart(2, '0')} · ${kicker.slice(5)}`
  if (!sections.has(section)) {
    sections.add(section)
    pres.addSection({ title: section })
  }
  const s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: section })
  s.addText(kicker.toUpperCase(), { placeholder: 'kicker' })
  s.addText(title, { placeholder: 'title' })
  return s
}

/** Motif: the "sound" of a silent mouth, lip aperture drawn as waveform bars. */
function wave(slide, x, y, w, h, n = 48, color = TUR, seed = 3) {
  let r = seed
  const rnd = () => ((r = (r * 16807) % 2147483647) / 2147483647)
  const step = w / n
  for (let i = 1; i < n; i++) {
    const env = Math.sin((Math.PI * i) / n) ** 0.7
    const syll = 0.35 + 0.65 * Math.abs(Math.sin(i * 0.55))
    const bh = Math.max(0.03, h * env * syll * (0.75 + 0.25 * rnd()))
    slide.addShape(pres.shapes.RECTANGLE, { x: x + i * step, y: y + (h - bh) / 2, w: step * 0.5, h: bh, fill: { color }, line: { type: 'none' }, objectName: `wave-${i}` })
  }
}

function card(slide, x, y, w, h, name, fill = CARD) {
  slide.addShape(pres.shapes.RECTANGLE, { x, y, w, h, fill: { color: fill }, line: { color: RULE, width: 0.75 }, objectName: name })
}

function stat(slide, x, y, w, big, label, src, color = TUR) {
  text(slide, big, { x, y, w, h: 1.1, fontFace: HEAD, fontSize: 60, color, valign: 'bottom' })
  text(slide, label, { x, y: y + 1.2, w, h: 1.0, fontSize: 16 })
  text(slide, src, { x, y: y + 2.25, w, h: 0.4, fontSize: 11, color: MUTE })
}

function readResults() {
  if (!fs.existsSync(RESULTS)) return null
  const r = JSON.parse(fs.readFileSync(RESULTS, 'utf8'))
  if (r.synthetic) throw new Error('spike-results.json is synthetic; the deck prints measured numbers only')
  return r
}

// ---------- 1. cover ----------

pres.addSection({ title: 'Pitch' })
{
  const s = pres.addSlide({ masterName: 'COVER', sectionTitle: 'Pitch' })
  s.addText('mouna', { placeholder: 'title' })
  s.addText(
    [
      { text: 'Mouth it. Mouna says it.', options: { fontFace: HEAD, italic: true, fontSize: 28, color: BONE, breakLine: true } },
      { text: 'A phone app that lets a person who cannot make sound speak by mouthing words, in the caregiver’s language, offline.', options: { fontSize: 18, color: DIM } },
    ],
    { placeholder: 'body' },
  )
  text(s, 'மௌனம்   ಮೌನ   मौन', { x: M, y: 1.5, w: 8, h: 0.6, fontFace: SCRIPT, fontSize: 24, color: TUR })
  text(s, '“silence” in Tamil, Kannada and Hindi', { x: M, y: 2.0, w: 8, h: 0.3, fontSize: 12, color: MUTE })
  wave(s, 9.4, 1.6, 3.3, 3.6, 30)
  text(s, PROOF, { x: M, y: 5.55, w: 12, h: 0.35, fontSize: 13, fontFace: MONO, color: TUR })
  text(s, 'Team Highest in the Room  ·  Maadhav V H  ·  Sachin A S  ·  Nakul T', { x: M, y: 6.15, w: 9, h: 0.35, fontSize: 14, color: DIM })
  text(s, 'Open Innovation track  ·  built phone-first for the iQOO 15 (Snapdragon NPU)', { x: M, y: 6.55, w: 9, h: 0.35, fontSize: 12, color: MUTE })
  text(s, [{ text: 'Try the prototype: mouna-spike.vercel.app', options: { hyperlink: { url: 'https://mouna-spike.vercel.app' } } }], { x: 8.4, y: 6.55, w: W - M - 8.4, h: 0.35, fontSize: 12, fontFace: MONO, color: TUR, align: 'right' })
  s.addNotes(
    'Mouna means silence in Tamil, Kannada and Hindi. It is a phone app for people who can move their lips but cannot make a sound, ' +
      'after a laryngectomy, a tracheostomy or some strokes. They teach it a few phrases by mouthing them, and from then on the phone speaks ' +
      'those phrases aloud in the caregiver’s language, with no network.',
  )
}

// ---------- 1b. summary ----------
{
  const s = content('Summary', 'Mouna lets people who have lost their voice speak by mouthing, offline, in Indian languages', 'Pitch')
  const rows = [
    ['PROBLEM', 'After laryngectomy, tracheostomy or stroke, many patients can move their lips but make no sound.', '~70,000 new laryngeal and hypopharyngeal cancers a year in India (GLOBOCAN 2024)'],
    ['PRODUCT', 'Teach three phrases in about 25 s by mouthing them; the phone then speaks each one in the caregiver’s language, in a natural voice the patient chooses.', 'Kannada, Tamil, Hindi, English; mouth in one, be heard in another'],
    ['PROOF', `With five samples per phrase it reads ${pct(shot(5).top1)} of words and phrases right first time; ${pct(KB.by_type.phrase.top1)} of two-word phrases with three.`, `7 Kannada speakers, 27 classes, public CC BY dataset; right ${pct(KN_A.pooled.same_session.spoke_precision)} of the times it chose to speak`],
    ['PLATFORM', `The whole lip encoder runs on the Hexagon NPU of the iQOO 15’s chip: ${NPU_MS} ms for 1.92 s of lips, every layer on the NPU.`, `Galaxy S26 (same SM8850) via Qualcomm AI Hub; int8 model ${STATIC.int8.mb} MB; no internet permission`],
    ['RESPONSIBILITY', 'It asks instead of guessing, confirms urgent phrases, stores no video, credits LipLearner and states its limits.', 'Communication aid, not a medical device; pilot with a speech-language pathology team next'],
  ]
  rows.forEach(([k, claim, proof], i) => {
    const y = 1.95 + i * 0.98
    if (i) s.addShape(pres.shapes.LINE, { x: M, y: y - 0.12, w: W - 2 * M, h: 0, line: { color: RULE, width: 0.75 }, objectName: `rule-${i}` })
    text(s, k, { x: M, y, w: 2.1, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: TUR })
    text(s, claim, { x: M + 2.2, y: y - 0.04, w: 6.2, h: 0.85, fontFace: HEAD, fontSize: 16 })
    text(s, proof, { x: M + 8.6, y, w: W - 2 * M - 8.6, h: 0.8, fontSize: 11, color: DIM })
  })
  s.addNotes(
    'One-slide summary for reviewers. Every number is measured or cited, and repeated with its source in Appendix B (key facts). ' +
      'Proof: Kannada multi-speaker lip-reading dataset (Divya P, Mendeley Data 2026, CC BY 4.0), same-session split. Platform: Qualcomm AI Hub profile on a Samsung Galaxy S26.',
  )
}

// ---------- 2. situation ----------
{
  const s = content('01 · The situation', 'After throat surgery or a stroke, many patients can still move their lips but make no sound', 'Pitch')
  card(s, M, 2.1, 5.85, 4.5, 'today')
  text(s, 'TODAY', { x: M + 0.35, y: 2.35, w: 5, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: MUTE })
  text(
    s,
    [
      { text: 'A ward at 3 a.m. The patient needs water, or is in pain.', options: { bullet: true, breakLine: true } },
      { text: 'Hands are weak or taped to a line; the whiteboard is slow and the script may not be theirs.', options: { bullet: true, breakLine: true } },
      { text: 'The nurse may speak another language than the patient.', options: { bullet: true, breakLine: true } },
      { text: 'Voice prostheses help many people later, but there is a gap before they work.', options: { bullet: true } },
    ],
    { x: M + 0.35, y: 2.8, w: 5.2, h: 3.6, fontSize: 16, color: DIM, paraSpaceAfter: 10 },
  )
  card(s, 6.88, 2.1, 5.85, 4.5, 'with-mouna')
  text(s, 'WITH MOUNA', { x: 7.23, y: 2.35, w: 5, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: TUR })
  text(s, 'The patient mouths “I need water” in Tamil.', { x: 7.23, y: 2.85, w: 5.2, h: 0.6, fontSize: 17 })
  text(s, 'எனக்கு தண்ணீர் வேண்டும்', { x: 7.23, y: 3.45, w: 5.2, h: 0.55, fontFace: SCRIPT, fontSize: 20, color: DIM })
  wave(s, 7.23, 4.15, 5.1, 0.7, 40, MUTE, 11)
  text(s, 'The phone says it aloud, in Kannada, for the nurse:', { x: 7.23, y: 5.0, w: 5.2, h: 0.4, fontSize: 15, color: DIM })
  text(s, 'ನನಗೆ ನೀರು ಬೇಕು', { x: 7.23, y: 5.45, w: 5.2, h: 0.8, fontFace: SCRIPT, fontSize: 32, bold: true, color: TUR })
  s.addNotes(
    'Who it helps: people who know what they want to say and can move their lips, but cannot vocalise: after laryngectomy, tracheostomy, ' +
      'some strokes and motor neurone disease. It does not help aphasia, which is a language impairment. ' +
      'The tracheo-oesophageal prosthesis is the standard of voice rehabilitation after total laryngectomy (National Cancer Grid head and neck guideline, 2019), ' +
      'which still leaves a gap before it works. We do not use any real patient; this is an illustration.',
  )
}

// ---------- 3. numbers ----------
{
  const s = content('02 · The numbers', 'The need is large, and the one clinical lip-reading app struggles exactly where it is needed', 'Pitch')
  stat(s, M, 2.0, 3.8, '~70,000', 'new laryngeal and hypopharyngeal cancers a year in India', 'GLOBOCAN 2024, India fact sheet')
  stat(s, 4.75, 2.0, 3.8, '51.2%', 'of stroke patients show speech disturbance at onset (34,792 cases, 30 centres)', 'Mathur et al., Indian hospital stroke registries')
  stat(s, 8.9, 2.0, 3.8, '21.8%', 'accuracy of the cloud lip-reading app SRAVI in UK critical care; Wi-Fi limits hurt usability', 'HRA summary of the SRAVI study', KUM)
  card(s, M, 5.25, W - 2 * M, 1.1, 'insight')
  text(
    s,
    [
      { text: 'What the evidence says: ', options: { bold: true, color: TUR } },
      { text: 'patients after laryngectomy preferred lip-reading to writing (69.7%, n = 16), but a fixed, cloud, English-only, speaker-independent model is too weak in a ward. Personalised, on-device, multilingual is the gap.', options: { color: BONE } },
    ],
    { x: M + 0.35, y: 5.45, w: W - 2 * M - 0.7, h: 1.0, fontSize: 16 },
  )
  s.addNotes(
    'GLOBOCAN 2024 India: laryngeal 35,169 new cases, hypopharyngeal 35,489; about 70,000 combined. We do not claim a laryngectomy count; it is not published in what we found. ' +
      'Stroke: Mathur et al., hospital-based stroke registries, 2020-22: motor impairment 74.8%, speech disturbance 51.2%. Speech disturbance includes aphasia, which Mouna does not address. ' +
      'SRAVI: 21.8% on fixed English phrases, 34.6% free-speech version, in the HRA-summarised UK critical-care study; a larger trial was judged not currently feasible. ' +
      'Fassler et al. 2026: 16 laryngectomy patients, 43% first try, 67% overall, 69.7% preferred it to writing.',
  )
}

// ---------- 4. what it does ----------
{
  const s = content('03 · What Mouna does', 'Teach three phrases in about 25 seconds, mouth them silently, and the caregiver hears them in their own language', 'Product')
  const steps = [
    ['1', 'Teach', 'Mouth each phrase three times. A quality gate re-asks any clip with the face lost, the head turned or the lips still.'],
    ['2', 'Mouth', 'Phone on a stand at 30–45 cm. Hold the large button, mouth the phrase, release. No sound needed, so ward noise does not matter.'],
    ['3', 'Speak', 'Confident: it speaks in a natural voice the patient chooses, and shows large text. Unsure: it shows the top three to tap.'],
  ]
  steps.forEach(([n, h, b], i) => {
    const y = 2.05 + i * 1.5
    s.addShape(pres.shapes.OVAL, { x: M, y, w: 0.62, h: 0.62, fill: { color: TUR }, line: { type: 'none' }, objectName: `step-${n}` })
    text(s, n, { x: M, y: y + 0.08, w: 0.62, h: 0.46, fontFace: HEAD, fontSize: 22, color: INK, align: 'center', valign: 'middle' })
    text(s, h, { x: M + 0.9, y: y - 0.02, w: 6.2, h: 0.45, fontFace: HEAD, fontSize: 22 })
    text(s, b, { x: M + 0.9, y: y + 0.45, w: 6.4, h: 0.9, fontSize: 14, color: DIM })
  })

  // phone mock: the Speak screen
  const px = 8.75
  const py = 1.95
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: px, y: py, w: 3.0, h: 4.85, rectRadius: 0.3, fill: { color: CARD }, line: { color: RULE, width: 1.5 }, objectName: 'phone' })
  text(s, 'CAREGIVER HEARS  ·  ಕನ್ನಡ', { x: px + 0.25, y: py + 0.4, w: 2.5, h: 0.25, fontSize: 9, bold: true, color: MUTE })
  text(s, 'ನನಗೆ ನೀರು ಬೇಕು', { x: px + 0.25, y: py + 0.8, w: 2.55, h: 1.1, fontFace: SCRIPT, fontSize: 24, bold: true, color: TUR })
  text(s, 'I need water  ·  NPU  ·  ms per window measured', { x: px + 0.25, y: py + 1.95, w: 2.55, h: 0.3, fontSize: 9, color: MUTE })
  wave(s, px + 0.25, py + 2.4, 2.5, 0.55, 26, MUTE, 5)
  ;['Please call the nurse', 'I am in pain', 'Please help me sit up'].forEach((t, i) => {
    const y = py + 3.15 + i * 0.47
    s.addShape(pres.shapes.RECTANGLE, { x: px + 0.25, y, w: 2.5, h: 0.38, fill: { color: INK }, line: { color: i ? RULE : TUR, width: 0.75 }, objectName: `cand-${i}` })
    text(s, t, { x: px + 0.35, y: y + 0.08, w: 2.3, h: 0.25, fontSize: 10, color: i ? DIM : BONE })
  })
  text(s, 'Illustration of the Speak screen', { x: px, y: py + 4.95, w: 3.0, h: 0.25, fontSize: 9, color: MUTE, align: 'center' })
  s.addNotes(
    'A phrase is a concept with fixed, human-checked labels in several languages, so a Tamil-speaking patient can be heard in Kannada by the nurse. ' +
      'No machine translation at runtime. Starter pack: 12 phrases plus “Thank you very much”, in English, Tamil, Kannada and Hindi; native-speaker review is in progress. ' +
      'Phrase design follows the LipLearner finding that longer commands are recognised better: at least three syllables, different lengths, avoid look-alike lip starts.',
  )
}

// ---------- 5. how it works ----------
{
  const s = content('04 · How it works', 'Camera to voice runs entirely on the phone, and the app has no permission to use the internet', 'Product')
  const stages = [
    ['Front camera', 'CameraX, 30 fps'],
    ['Face landmarks', 'MediaPipe, GPU'],
    ['Mouth crop', 'aligned, 96 px grey'],
    ['Lip encoder', 'open-source, NPU'],
    ['Few-shot head', 'trained on the phone'],
    ['Confidence gate', 'speak · ask · reject'],
    ['Voice', 'natural, pre-rendered, chosen'],
  ]
  const bw = 1.55
  const gap = (W - 2 * M - stages.length * bw) / (stages.length - 1)
  stages.forEach(([h, sub], i) => {
    const x = M + i * (bw + gap)
    const ours = i === 4 || i === 5
    card(s, x, 2.25, bw, 1.45, `stage-${i}`, ours ? INK : CARD)
    if (ours) s.addShape(pres.shapes.RECTANGLE, { x, y: 2.25, w: bw, h: 1.45, fill: { type: 'none' }, line: { color: TUR, width: 1.25 }, objectName: `ours-${i}` })
    text(s, h, { x: x + 0.15, y: 2.42, w: bw - 0.3, h: 0.6, fontFace: HEAD, fontSize: 15 })
    text(s, sub, { x: x + 0.15, y: 3.05, w: bw - 0.3, h: 0.5, fontSize: 11, color: DIM })
    if (i < stages.length - 1)
      s.addShape(pres.shapes.LINE, { x: x + bw + 0.04, y: 2.97, w: gap - 0.08, h: 0, line: { color: MUTE, width: 1.25, endArrowType: 'triangle' }, objectName: `arrow-${i}` })
  })
  text(s, 'Outlined: the part we design and train per person. Video exists only in memory; stored data is a few embedding vectors per phrase.', { x: M, y: 3.9, w: W - 2 * M, h: 0.35, fontSize: 13, color: MUTE })

  const facts = [
    ['No INTERNET permission', 'verified in our probe APK: CAMERA is the only permission it requests'],
    DEVICE
      ? [`${NPU_MS} ms on NPU`, 'whole encoder for 1.92 s of lips on the iQOO 15’s chip (SM8850); target ≤ 700 ms end to end']
      : [`${Math.round(ENC_1S)} ms measured`, 'encoder per 1 s of lips on a laptop CPU; phone NPU next (target ≤ 700 ms end to end)'],
    [`${ENCODER.params_m.toFixed(1)}M params`, `${Math.round(ENCODER.onnx_fp32_mb)} MB fp32 today; int8 on the NPU to fit the < 100 MB target`],
    ['NPU, GPU, CPU', 'same answer on each path; latency and energy shown side by side'],
  ]
  facts.forEach(([h, b], i) => {
    const x = M + i * 3.07
    card(s, x, 4.55, 2.9, 2.0, `fact-${i}`)
    text(s, h, { x: x + 0.25, y: 4.75, w: 2.45, h: 0.75, fontFace: HEAD, fontSize: 18, color: TUR })
    text(s, b, { x: x + 0.25, y: 5.5, w: 2.45, h: 0.95, fontSize: 13, color: DIM })
  })
  s.addNotes(
    'Pipeline: CameraX front camera; MediaPipe Face Landmarker (Apache-2.0) on GPU; landmark smoothing and an aligned grayscale mouth crop; a cheap lip-activity gate on CPU; ' +
      'a visual-speech encoder of the LipLearner class (3D-CNN front end, ResNet-18 trunk, contrastive pre-training on LRW) on the Snapdragon NPU with GPU/CPU fallback; ' +
      'a few-shot head (prototypes plus a linear head with an idle class); then the confidence gate and pre-rendered voices. ' +
      `Measured so far (deck/data/encoder-report.json): ${ENCODER.params_m}M parameters, ${ENCODER.onnx_fp32_mb} MB ONNX fp32, ${ENC_1S} ms per 1 s window on a laptop CPU. ` +
      'The bundle and end-to-end latency figures are targets until measured on the phone. ' +
      'Lessons from our VeriTransit build on the same phone: ship every model in the app, warm the NPU context before the pitch, retry on HTP context error 1007, keep a GPU path.',
  )
}

// ---------- 5b. built for a ward ----------
{
  const s = content('00 · Built for a ward', 'Built for a ward: hands-free, in the patient’s own voice, and it calls the nurse', 'Product')
  const tiles = [
    ['Blink to talk', 'Two quick blinks start listening, so a patient who cannot press a button can still speak.'],
    ['Their own voice', 'Before surgery the patient says each phrase aloud once; afterwards Mouna speaks with that recording.'],
    ['Calls the nurse', 'Each phrase also appears on the caregiver’s phone; urgent ones ring it. Direct over Wi-Fi, no server.'],
    ['Learns from mistakes', '“Wrong?” takes one tap: the right phrase is spoken and becomes a new sample. A meter shows it improving.'],
    ['Natural voices', '4 voices in Kannada, Tamil, Hindi and English, rendered once and played offline.'],
    ['Context, safely', 'After “pain”, “medicine” is offered first among the choices. Context reorders; it never speaks on a guess.'],
  ]
  const cw = (W - 2 * M - 2 * 0.3) / 3
  const ch = 2.15
  tiles.forEach(([h, b], i) => {
    const x = M + (i % 3) * (cw + 0.3)
    const y = 1.95 + Math.floor(i / 3) * (ch + 0.3)
    card(s, x, y, cw, ch, `tile-${i}`)
    text(s, String(i + 1).padStart(2, '0'), { x: x + 0.3, y: y + 0.25, w: 0.6, h: 0.35, fontSize: 12, fontFace: MONO, color: TUR })
    text(s, h, { x: x + 0.3, y: y + 0.6, w: cw - 0.6, h: 0.5, fontFace: HEAD, fontSize: 21 })
    text(s, b, { x: x + 0.3, y: y + 1.15, w: cw - 0.6, h: 0.9, fontSize: 13, color: DIM })
  })
  text(s, 'All six run in the prototype today (Mouna Lab). Voices: Sarvam Bulbul v3 and AI4Bharat Indic Parler-TTS (Apache-2.0).', {
    x: M, y: 6.66, w: W - 2 * M, h: 0.3, fontSize: 11, color: MUTE,
  })
  s.addNotes(
    'Blink-to-talk: eye aspect ratio from the face landmarks already tracked; a double blink within a second starts a clip, which ends when the lips stop. ' +
      'Voice banking: microphone and camera record together while teaching; the recording stays on the device. ' +
      'Nurse-call link: WebRTC data channel with no ICE servers, paired by QR code; the caregiver acknowledges with "Coming". ' +
      'Corrections are saved as new teaching samples. Context re-ranking only reorders the three choices shown when Mouna is unsure.',
  )
}

// ---------- 6. evidence ----------
{
  const r = readResults()
  const s = content('05 · Evidence', `Personalised lip reading carries over to Kannada: ${pct(shot(5).top1)} top-1 with five samples per phrase`, 'Evidence')
  const label = (k) => `${k} sample${k > 1 ? 's' : ''}`
  s.addChart(
    pres.charts.BAR,
    [
      { name: 'LipLearner, published (English, 30 commands)', labels: [1, 3, 5].map(label), values: [81.7, 96.0, 98.8] },
      { name: 'Mouna, measured (Kannada, 27 classes, 7 speakers)', labels: CURVE.curve.map((c) => label(c.shots)), values: CURVE.curve.map((c) => Math.round(c.top1 * 1000) / 10) },
    ],
    {
      x: M, y: 1.95, w: 6.6, h: 4.5, barDir: 'col', barGrouping: 'clustered',
      chartColors: [THEME.colors.accent4, THEME.colors.accent1],
      showTitle: true, title: 'Top-1 % by samples taught per phrase', titleFontSize: 12, titleColor: THEME.colors.lt2, titleFontFace: '+mn-lt',
      showValue: true, dataLabelPosition: 'outEnd', dataLabelColor: THEME.colors.lt1, dataLabelFontSize: 12, dataLabelFontFace: '+mn-lt', dataLabelFormatCode: '0.0',
      catAxisLabelColor: THEME.colors.lt2, catAxisLabelFontSize: 12, catAxisLabelFontFace: '+mn-lt',
      valAxisHidden: true, valAxisMinVal: 0, valAxisMaxVal: 110, valGridLine: { style: 'none' }, catGridLine: { style: 'none' },
      showLegend: true, legendPos: 'b', legendColor: THEME.colors.lt2, legendFontSize: 11, legendFontFace: '+mn-lt', barGapWidthPct: 60,
    },
  )
  const x = 7.5
  const facts = [
    [pct(KB.by_type.phrase.top1), `two-word phrases, three samples (n ${KB.by_type.phrase.n})`],
    [pct(KN_A.pooled.same_session.spoke_precision), 'right when it chose to speak; otherwise it showed choices'],
    [pct(shot(5).top3), 'right answer among the three choices, five samples'],
  ]
  facts.forEach(([big, small], i) => {
    const y = 1.95 + i * 1.25
    text(s, big, { x, y, w: 2.4, h: 0.75, fontFace: HEAD, fontSize: 38, color: i ? BONE : TUR, valign: 'bottom' })
    text(s, small, { x: x + 2.5, y: y + 0.2, w: W - M - x - 2.5, h: 0.6, fontSize: 13, color: DIM, valign: 'middle' })
  })
  text(
    s,
    r
      ? `Our own silent recordings: ${pct(r.pooled.same_session.top1)} same session, ${pct(r.pooled.next_session.top1)} next morning (${r.participants.length} people).`
      : 'Single words are the hard case (bare “water” is the hardest), so Mouna’s phrases are whole sentences. Next: our own silent recordings, same protocol, evening and next morning.',
    { x, y: 5.75, w: W - M - x, h: 0.8, fontSize: 12, color: MUTE },
  )
  s.addNotes(
    'LipLearner: Su, Fang, Rekimoto, CHI 2023 (arXiv 2302.05907): 30 English commands, healthy users, iPhone: 81.7 / 96.0 / 98.8% top-1 at 1 / 3 / 5 samples. ' +
      'Mouna: the LipLearner encoder with our few-shot head on the Kannada multi-speaker lip-reading dataset (Divya P, Mendeley Data 2026, doi:10.17632/zbzrbs89pz.1, CC BY 4.0): ' +
      `${CURVE.curve.map((c) => `${c.shots} sample${c.shots > 1 ? 's' : ''} ${pct(c.top1)}`).join(', ')} top-1 on the same ${CURVE.curve[0].n} held-out clips. ` +
      'Our classes include many two-syllable single words, which are harder than LipLearner’s commands. The recordings are voiced; silent mouthing is expected to cost a few points. ' +
      'Pass lines for our own silent spike were set before measuring: 95% same session, 90% next morning.',
  )
}

// ---------- 6b. on the chip ----------
{
  const R = DEVICE.runs
  const npu = R.static_npu
  const cpu = R.static_cpu?.median_ms ? R.static_cpu : null
  const q8 = R.static_npu_int8?.median_ms ? R.static_npu_int8 : null
  const speedup = cpu ? `, ${Math.round(cpu.median_ms / npu.median_ms)}× faster than the CPU` : ''
  const s = content('00 · On the iQOO 15’s chip', `The whole encoder runs on the Hexagon NPU: ${npu.median_ms.toFixed(1)} ms for 1.92 s of lips${speedup}`, 'Evidence')
  const bars = [
    ['Whole encoder · NPU', npu.median_ms],
    ...(q8 ? [['Whole encoder · NPU int8', q8.median_ms]] : []),
    ...(cpu ? [['Whole encoder · CPU', cpu.median_ms]] : []),
    ['Before: front end NPU + GRU CPU', R.frontend_npu.median_ms + R.temporal_cpu.median_ms],
  ]
  s.addChart(
    pres.charts.BAR,
    [{ name: 'ms per window', labels: bars.map((b) => b[0]), values: bars.map((b) => Math.round(b[1] * 10) / 10) }],
    {
      x: M, y: 1.95, w: 7.2, h: 4.6, barDir: 'bar',
      chartColors: bars.map((b, i) => (i === 0 || (q8 && i === 1) ? THEME.colors.accent1 : THEME.colors.accent4)),
      showTitle: true, title: 'Milliseconds per window, Galaxy S26 (SM8850, as in the iQOO 15)', titleFontSize: 12, titleColor: THEME.colors.lt2, titleFontFace: '+mn-lt',
      showValue: true, dataLabelPosition: 'outEnd', dataLabelColor: THEME.colors.lt1, dataLabelFontSize: 12, dataLabelFontFace: '+mn-lt', dataLabelFormatCode: '0.0',
      catAxisLabelColor: THEME.colors.lt2, catAxisLabelFontSize: 12, catAxisLabelFontFace: '+mn-lt', catAxisOrientation: 'maxMin',
      valAxisHidden: true, valAxisMinVal: 0, valAxisMaxVal: Math.max(...bars.map((b) => b[1])) * 1.2, valGridLine: { style: 'none' }, catGridLine: { style: 'none' }, showLegend: false, barGapWidthPct: 45,
    },
  )
  const x = 8.2
  text(s, [
    { text: `All ${npu.layers_by_unit.NPU.toLocaleString('en-IN')} layers run on the NPU; nothing falls back to the CPU.`, options: { bullet: true, breakLine: true } },
    { text: 'To get there we rewrote the encoder exactly: its 3-D convolution as a 2-D one and its GRU as plain matrix multiplies (difference below 0.000001).', options: { bullet: true, breakLine: true } },
    { text: `A fixed 48-frame window made it NPU-ready and more accurate: ${pct(KN_A.pooled.same_session.top1)} top-1 on Kannada.`, options: { bullet: true, breakLine: true } },
    { text: `int8 weights: ${STATIC.int8.mb} MB instead of ${STATIC.fp32.mb} MB, with the same accuracy.`, options: { bullet: true } },
  ], { x, y: 2.0, w: W - M - x, h: 4.5, fontSize: 14, color: DIM, paraSpaceAfter: 9 })
  s.addNotes(
    `Qualcomm AI Hub jobs on a Samsung Galaxy S26 (Snapdragon 8 Elite Gen 5, SM8850). Whole static encoder on the NPU: ${npu.median_ms} ms (compile ${npu.compile_job}, profile ${npu.profile_job}). ` +
      (cpu ? `Same model on the CPU: ${cpu.median_ms} ms. ` : '') +
      (q8 ? `int8 on the NPU: ${q8.median_ms} ms. ` : '') +
      `Before the rewrite, only the front end ran on the NPU (${R.frontend_npu.median_ms} ms) and the GRU on the CPU (${R.temporal_cpu.median_ms} ms, 2.56 s window). ` +
      `Accuracy and size from deck/data/encoder-static.json: int8 ${STATIC.int8.mb} MB and fp32 ${STATIC.fp32.mb} MB both reach ${pct(STATIC.int8.top1)} top-1.`,
  )
}

// ---------- 6c. never taught ----------
{
  const s = content('00 · Words it was never taught', 'For words it was never taught, Mouna is measured, made careful, and can still be reached by yes and no', 'Evidence')
  const rows = [
    [`${pct(UNTAUGHT)} → ${pct(CAREFUL.untaught_spoken)}`, 'untaught words spoken as a taught phrase: default vs “Careful” mode. The rest go to choices or “not taught”; Careful still speaks taught phrases right ' + pct(CAREFUL.taught_spoken_precision) + ' of the time.'],
    [`${pct(PAIRS.each_block_alone)} → ${pct(PAIRS.with_sentence_list)}`, 'two taught words mouthed one after another (“water · want”): right block by block, vs read against a list of human-written sentences.'],
    [pct(OPEN.chains_not_on_list.spoken_as_a_listed_sentence), 'word pairs on no list that were spoken as a listed sentence. Off-list pairs are offered as the plain words, never as an invented sentence.'],
  ]
  rows.forEach(([big, small], i) => {
    const y = 1.95 + i * 1.5
    text(s, big, { x: M, y, w: 3.6, h: 0.75, fontFace: HEAD, fontSize: 34, color: i ? BONE : TUR, valign: 'bottom' })
    text(s, small, { x: M + 3.75, y: y + 0.05, w: 3.0, h: 1.3, fontSize: 12, color: DIM })
  })
  text(s, 'Kannada dataset, encoder, 8 of 27 classes taught at random (20 draws per speaker); every other clip is an untaught word. Same teach / test split as the evidence slide.', { x: M, y: 6.35, w: 6.2, h: 0.5, fontSize: 10, color: MUTE })

  const x = 7.45
  const cw = W - M - x
  card(s, x, 1.95, cw, 4.85, 'ask-mode')
  text(s, 'ASK MODE · WHEN NOTHING TAUGHT FITS', { x: x + 0.3, y: 2.15, w: cw - 0.6, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: TUR })
  const steps = [['வலி?', 'Pain?', 'nod'], ['தலை?', 'Head?', 'shake'], ['தொண்டை அல்லது கழுத்து?', 'Throat or neck?', 'blink twice']]
  steps.forEach(([ta, en, how], i) => {
    const y = 2.6 + i * 0.72
    text(s, ta, { x: x + 0.3, y, w: 3.2, h: 0.45, fontFace: SCRIPT, fontSize: 17, color: BONE })
    text(s, `${en}  ·  ${how}`, { x: x + 3.5, y: y + 0.08, w: cw - 3.8, h: 0.35, fontSize: 12, fontFace: MONO, color: i === 1 ? MUTE : TUR })
  })
  text(s, 'ನನಗೆ ಗಂಟಲು ಅಥವಾ ಕುತ್ತಿಗೆ ನೋವಾಗುತ್ತಿದೆ', { x: x + 0.3, y: 4.85, w: cw - 0.6, h: 0.8, fontFace: SCRIPT, fontSize: 16, bold: true, color: TUR })
  text(s, 'heard by the nurse in Kannada, and shown live on their phone', { x: x + 0.3, y: 5.65, w: cw - 0.6, h: 0.3, fontSize: 11, color: MUTE })
  text(s, '30 answers in 8 groups drawn from ICU studies (pain by site, breathing and suction, thirst, comfort, people, fears, questions). Yes: nod, two blinks or tap. No: shake, tap, or wait. One signal is enough.', { x: x + 0.3, y: 5.98, w: cw - 0.6, h: 0.78, fontSize: 11, color: DIM })
  s.addNotes(
    `Measured with harness/mouna_harness/openset.py on the Kannada multi-speaker dataset (deck/data/kannada-openset-plana.json, -planb.json). Encoder, 8 classes taught: taught words ${pct(OPEN.small_vocabulary_8.taught_words.top1)} top-1; ` +
      `untaught words spoken as a taught phrase ${pct(UNTAUGHT)}, offered as choices ${pct(OPEN.small_vocabulary_8.untaught_words.offered_choices)}, said not taught ${pct(OPEN.small_vocabulary_8.untaught_words.said_not_taught)}. ` +
      `Careful mode scales the speak threshold by 0.7: untaught spoken ${pct(CAREFUL.untaught_spoken)} (lip geometry ${pct(CAREFUL_B.untaught_spoken)}), and taught phrases spoken directly fall from ${pct(OPEN.strictness_curve[0].taught_spoken)} to ${pct(CAREFUL.taught_spoken)}; the rest become choices. ` +
      `Chained blocks: 23 sentences written before scoring; 2 blocks ${pct(PAIRS.each_block_alone)} block by block, ${pct(PAIRS.with_sentence_list)} with the list, ceiling of any re-ranking over the top three ${pct(PAIRS.top3_ceiling)}; ` +
      `3 blocks ${pct(OPEN.chains['3_blocks'].each_block_alone)} to ${pct(OPEN.chains['3_blocks'].with_sentence_list)}. The commonest remaining mistakes are water/snack and want/don't-want, which no language model can fix, so every sentence is confirmed. ` +
      'Ask mode is partner-assisted scanning done by the device, from a fixed tree; small language models plan twenty questions poorly, so none is used here. ICU needs: thirst and pain on repositioning (69% each) lead in reviews of ventilated patients.',
  )
}

// ---------- 7. what is different ----------
{
  const s = content('07 · What is different', 'We build on LipLearner and credit it; what is new is the setting: Indian languages, wards, Android, offline', 'Evidence')
  const Y = '●'
  const N = '—'
  const head = ['', 'On device', 'Learns your lips', 'Indian languages', 'Android', 'Speaks aloud', 'Ward safety rules']
  const rows = [
    ['Mouna', Y, Y, Y, Y, Y, Y],
    ['LipLearner (CHI 2023)', Y, Y, N, N, N, N],
    ['Liperty (2026)', Y, 'in progress', N, Y, Y, N],
    ['SRAVI (Liopa)', N, N, N, Y, Y, 'clinical'],
    ['Voice Back (AAC)', Y, 'voice clone', 'Hindi', Y, 'from taps', N],
  ]
  const cell = (t, r, c) => ({
    text: t,
    options: {
      color: r === 0 ? (c === 0 ? TUR : BONE) : c === 0 ? DIM : t === Y ? (r === 0 ? TUR : DIM) : MUTE,
      bold: r === 0 || (c === 0 && r === 0),
      fontSize: c === 0 ? 15 : t === Y ? 16 : 12,
      align: c === 0 ? 'left' : 'center',
      fill: { color: r === 0 ? INK : CARD },
      fontFace: c === 0 && r === 0 ? 'Cambria' : undefined,
    },
  })
  s.addTable(
    [head.map((t, c) => ({ text: t, options: { color: MUTE, fontSize: 11, bold: true, align: c ? 'center' : 'left', fill: { color: INK } } })), ...rows.map((row, r) => row.map((t, c) => cell(t, r, c)))],
    { x: M, y: 2.0, w: W - 2 * M, colW: [3.0, 1.5, 1.6, 1.6, 1.4, 1.5, 1.53], rowH: 0.55, border: { type: 'solid', color: THEME.colors.accent5, pt: 0.75 }, valign: 'middle', margin: [0, 0.12, 0, 0.12] },
  )
  card(s, M, 5.65, W - 2 * M, 0.95, 'wedge')
  text(s, [
    { text: 'The wedge: ', options: { bold: true, color: TUR } },
    { text: 'mouth in one language, be heard in another, from human-checked phrase packs, on a phone with no network, with rules that never let it guess in a ward.', options: { color: BONE } },
  ], { x: M + 0.35, y: 5.85, w: W - 2 * M - 0.7, h: 0.6, fontSize: 16 })
  s.addNotes(
    'LipLearner: MIT-licensed code, iOS app, English commands; we credit the method. Liperty: open-source Android lip reading, SyncVSR trained on English LRS3, about a 1.4 GB first-launch download, per-user personalisation marked in progress. ' +
      'SRAVI: about 40 English phrases, video processed in the cloud, CE-marked and MHRA-registered, hospital-only. Voice Back: AAC app that speaks from taps or text with a voice clone; complementary. ' +
      'Also related: MELDER (CHI 2024) real-time mobile lip reader with server-side recognition; Auto-AVSR and Chaplin-UI, desktop open-vocabulary lip reading around 19-20% WER on LRS3.',
  )
}

// ---------- 8. demo and failure ----------
{
  const s = content('08 · The demo', 'On stage a judge teaches Mouna live, and we show the failure case on purpose: it asks instead of guessing', 'Demo and plan')
  const beats = [
    ['0:00', 'A quiet bed, a whiteboard'],
    ['0:20', 'Judge teaches three phrases, about 25 s'],
    ['1:10', 'Mouths in English or Tamil; phone speaks Kannada'],
    ['1:50', 'Airplane mode, permission list, live telemetry'],
    ['2:20', 'Untaught: “not sure”, then Ask mode finds it'],
    ['2:50', 'Credit LipLearner; close'],
  ]
  beats.forEach(([t, b], i) => {
    const y = 2.05 + i * 0.73
    text(s, t, { x: M, y, w: 0.9, h: 0.4, fontSize: 15, bold: true, color: i === 4 ? KUM : TUR })
    text(s, b, { x: M + 1.0, y, w: 5.3, h: 0.6, fontSize: 15 })
  })
  const x = 7.2
  card(s, x, 2.0, W - M - x, 2.65, 'rules')
  text(s, 'SAFETY RULES', { x: x + 0.35, y: 2.2, w: 5, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: KUM })
  text(
    s,
    [
      { text: 'Speaks only above a per-person threshold and a clear lead over the runner-up.', options: { bullet: true, breakLine: true } },
      { text: 'Otherwise: top three as large cards, or “not sure, please repeat”.', options: { bullet: true, breakLine: true } },
      { text: 'Urgent phrases (pain, breathing) need a confirm, then alarm and flash.', options: { bullet: true } },
    ],
    { x: x + 0.35, y: 2.6, w: W - M - x - 0.7, h: 1.95, fontSize: 14, color: DIM, paraSpaceAfter: 6 },
  )
  card(s, x, 4.85, W - M - x, 1.75, 'fallbacks')
  text(s, 'FALLBACKS', { x: x + 0.35, y: 5.05, w: 5, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: MUTE })
  text(s, 'Pre-taught profile · push-to-talk · GPU path if the NPU context fails · 60 s recorded run · second phone with the same build', { x: x + 0.35, y: 5.45, w: W - M - x - 0.7, h: 1.0, fontSize: 14, color: DIM })
  s.addNotes(
    'Setup: phone on a stand, front light, no backlight. Judge-proofing: check beards, glasses, caps and masks in rehearsal; ask the judge to face the lens and mouth as if whispering. ' +
      'The failure case is shown on purpose: an untaught phrase produces “not sure” and three candidates. In a ward, guessing is worse than asking.',
  )
}

// ---------- 9. plan and risks ----------
{
  const s = content('09 · 48 hours and the risks', 'We know what breaks on a stage, and the 48-hour plan is built around it', 'Demo and plan')
  const blocks = [
    ['0–3 h', 'Repo, camera, landmarks, crop, voices'],
    ['3–12 h', 'Encoder on phone, head, teaching flow'],
    ['12–24 h', 'Accuracy runs, gate, cross-language'],
    ['24–36 h', 'NPU path, telemetry, soak, caregiver view'],
    ['36–44 h', 'Freeze at 40 h; rehearse five times'],
    ['44–48 h', 'Repo lock, final run, thermals'],
  ]
  const bw = (W - 2 * M - 5 * 0.12) / 6
  blocks.forEach(([h, b], i) => {
    const x = M + i * (bw + 0.12)
    card(s, x, 2.0, bw, 1.55, `block-${i}`, i === 3 ? INK : CARD)
    if (i === 3) s.addShape(pres.shapes.RECTANGLE, { x, y: 2.0, w: bw, h: 1.55, fill: { type: 'none' }, line: { color: TUR, width: 1.25 }, objectName: 'cutline' })
    text(s, h, { x: x + 0.15, y: 2.15, w: bw - 0.3, h: 0.4, fontFace: HEAD, fontSize: 17, color: TUR })
    text(s, b, { x: x + 0.15, y: 2.6, w: bw - 0.3, h: 0.9, fontSize: 12, color: DIM })
  })
  text(s, 'Roles: Maadhav, pitch and mouth pipeline · Sachin, app, phrase packs, voices · Nakul, NPU conversion, benchmarks, thermals. NPU cut line at hour 30 (outlined).', { x: M, y: 3.7, w: W - 2 * M, h: 0.4, fontSize: 12, color: MUTE })

  const risks = [
    ['Accuracy below the paper', 'Spike gate before we commit; five samples; top-3 fallback; geometry plan B'],
    ['Ops fall off the NPU (Conv3D)', 'GPU path ready; swap to 2D + temporal conv; report honestly'],
    ['NPU context error 1007', 'Pre-warm, cached context binary, retry with backoff, GPU switch on screen'],
    ['False triggers: smile, yawn', 'Push-to-talk by default; idle class; measured false speaks per minute'],
    ['Venue lighting', 'Own front light; tested under overhead light and backlight'],
  ]
  s.addTable(
    [
      [{ text: 'Risk', options: { bold: true, color: MUTE, fontSize: 11 } }, { text: 'Mitigation', options: { bold: true, color: MUTE, fontSize: 11 } }],
      ...risks.map(([r, m]) => [
        { text: r, options: { color: BONE, fontSize: 13 } },
        { text: m, options: { color: DIM, fontSize: 13 } },
      ]),
    ],
    { x: M, y: 4.2, w: W - 2 * M, colW: [3.8, W - 2 * M - 3.8], border: { type: 'solid', color: THEME.colors.accent5, pt: 0.5 }, fill: { color: INK }, margin: [0.04, 0.1, 0.04, 0.1], rowH: 0.42 },
  )
  s.addNotes(
    'Further risks in the repo: encoder weight licence (checked on day one; plan B needs no weights), offline voices missing on the loaner phone (pre-rendered clips), ' +
      'patient limits such as oxygen masks, beards, bandages and facial droop (stated as limits; tap board later), misreading in a clinical setting (never auto-speak low confidence; not a medical device), ' +
      'prior-art challenges (we cite LipLearner, Liperty and SRAVI), and pre-built work (spike in a separate, disclosed repo; all Finale code written in the window).',
  )
}

// ---------- 10. impact and next ----------
{
  const s = content('10 · Impact and next step', 'Next: a pilot with a speech-language pathology team, with the limits stated up front', 'Close')
  const cols = [
    ['Next', TUR, ['Pilot with an ENT or speech-language pathology team', 'Open-source, human-checked phrase packs in more Indian languages', 'Clinical validation before any claim', 'Later: “lip banking” before surgery']],
    ['Limits we state', KUM, ['Accuracy on real patients is unproven', 'Masks, beards, bandages and facial droop block the lips', 'A phrase list, not open conversation', 'A communication aid, not a medical device']],
    ['Privacy by design', LEAF, ['No INTERNET permission at all', 'Video only in memory; templates encrypted', 'Consent screen and one-tap delete', 'No real patient in this pitch']],
  ]
  const cw = (W - 2 * M - 2 * 0.3) / 3
  cols.forEach(([h, color, items], i) => {
    const x = M + i * (cw + 0.3)
    card(s, x, 2.0, cw, 3.45, `col-${i}`)
    text(s, h, { x: x + 0.3, y: 2.2, w: cw - 0.6, h: 0.5, fontFace: HEAD, fontSize: 22, color })
    text(s, items.map((t, k) => ({ text: t, options: { bullet: true, breakLine: k < items.length - 1 } })), { x: x + 0.3, y: 2.85, w: cw - 0.6, h: 3.0, fontSize: 14, color: DIM, paraSpaceAfter: 8 })
  })
  text(s, 'Mouth it. Mouna says it.', { x: M, y: 5.85, w: 8, h: 0.6, fontFace: HEAD, italic: true, fontSize: 26, color: TUR })
  s.addNotes(
    'Pre-operative lip banking: the patient says the phrases aloud before surgery, on-device speech recognition labels them, and the model adapts after surgery; ' +
      'LipLearner saw about a 5.6-point drop from voiced to silent samples. Facial data is personal data under India’s DPDP Act; on-device processing minimises exposure, but we do not claim compliance. ' +
      'A legal and clinical read is needed before any pilot.',
  )
}

// ---------- appendix: reviewer summary ----------
{
  const s = content('Appendix A · For reviewers', 'Mouna at a glance, mapped to what a judge looks for', 'Appendix')
  const rows = [
    ['Problem and impact', 'People who can mouth words but make no sound; ~70,000 new larynx and hypopharynx cancers a year in India; speech disturbance in 51.2% of stroke onsets.'],
    ['Innovation', 'Personalised silent-speech phrases with cross-language output (mouth Tamil, heard in Kannada) from human-checked packs; ward safety rules.'],
    ['Technical depth', 'MediaPipe landmarks, aligned mouth crop, open-source lip encoder on the Snapdragon NPU, few-shot head trained on the phone, calibrated confidence gate.'],
    ['Evidence and honesty', KN_A
      ? `Measured on 7 Kannada speakers: ${pct(KN_A.pooled.same_session.top1)} top-1, ${pct(readJson('kannada-plana-breakdown.json').by_type.phrase.top1)} on phrases, ${pct(KN_A.pooled.same_session.spoke_precision)} right when it speaks; pass lines set before measuring; limits stated.`
      : 'Published 96–99% with 3–5 samples (LipLearner); our pass lines set before measuring; spike harness that refuses synthetic data; limits stated.'],
    ['Feasibility', 'Built in 21 hours (git history, 3–4 Oct 2026): working prototype live at mouna-spike.vercel.app (Mouna Lab) and tested harness; 48-hour plan with cut lines; GPU fallback; lessons from shipping a VLM on the iQOO 15 NPU.'],
    ['Use of the device', DEVICE
      ? `Whole encoder runs on the Hexagon NPU of the iQOO 15’s chip: ${NPU_MS} ms per 1.92 s window, every layer on the NPU (Galaxy S26, Qualcomm AI Hub). Front camera, offline voices, airplane-mode demo.`
      : 'Front camera, NPU, offline voices, airplane-mode demo; NPU, GPU and CPU latency and energy shown side by side.'],
    ['Responsibility', 'No network permission, no stored video, consent and delete, not a medical device, prior art credited, pre-built work disclosed.'],
  ]
  s.addTable(
    rows.map(([k, v]) => [
      { text: k, options: { color: TUR, fontSize: 14, bold: true, fontFace: HEAD } },
      { text: v, options: { color: DIM, fontSize: 13 } },
    ]),
    { x: M, y: 1.95, w: W - 2 * M, colW: [2.7, W - 2 * M - 2.7], border: { type: 'solid', color: THEME.colors.accent5, pt: 0.5 }, fill: { color: INK }, margin: [0.06, 0.12, 0.06, 0.12], valign: 'middle' },
  )
  s.addNotes('A one-slide summary for reviewers reading the deck offline. Every claim maps to a slide earlier in the deck, and every number has a source in the notes.')
}

// ---------- appendix: key facts ----------
{
  const s = content('Appendix B · Key facts', 'Every number in this deck, with its source', 'Appendix')
  const facts = [
    ['Need', '~70,000 new laryngeal and hypopharyngeal cancers a year in India', 'GLOBOCAN 2024 India fact sheet'],
    ['Need', '51.2% of stroke patients have speech disturbance at onset (34,792 cases)', 'Mathur et al., Indian stroke registries'],
    ['Status quo', 'Cloud lip-reading app SRAVI: 21.8% accuracy in UK critical care', 'HRA summary of the SRAVI study'],
    ['Prior art', 'LipLearner: 81.7 / 96.0 / 98.8% top-1 at 1 / 3 / 5 samples, 30 English commands', 'Su, Fang, Rekimoto, CHI 2023'],
    ['Measured', `Kannada, 7 speakers, 27 classes: ${CURVE.curve.map((c) => pct(c.top1)).join(' / ')} top-1 at 1 / 3 / 5 samples (n ${CURVE.curve[0].n})`, 'Our harness; Divya P, Mendeley Data 2026, CC BY 4.0'],
    ['Measured', `Two-word phrases ${pct(KB.by_type.phrase.top1)}, single words ${pct(KB.by_type.word.top1)} (three samples)`, 'Same'],
    ['Measured', `Right ${pct(KN_A.pooled.same_session.spoke_precision)} of the times it chose to speak; top-3 ${pct(shot(5).top3)} with five samples`, 'Same'],
    ['Measured', `Lip geometry alone, no model weights: ${pct(KN_B.pooled.same_session.top1)} top-1 (three samples)`, 'Same'],
    ['Measured', `Untaught words spoken as a taught phrase ${pct(UNTAUGHT)}, ${pct(CAREFUL.untaught_spoken)} in Careful mode; two-word sentences ${pct(PAIRS.each_block_alone)} → ${pct(PAIRS.with_sentence_list)} with a sentence list`, 'Same; 8 of 27 classes taught'],
    ['Measured', `Whole encoder on the Hexagon NPU: ${NPU_MS} ms per 1.92 s window, ${DEVICE.runs.static_npu.layers_by_unit.NPU} of ${DEVICE.runs.static_npu.layers_by_unit.NPU} layers on the NPU`, 'Qualcomm AI Hub, Galaxy S26 (SM8850)'],
    ['Measured', `int8 encoder ${STATIC.int8.mb} MB (fp32 ${STATIC.fp32.mb} MB), same Kannada top-1 ${pct(STATIC.int8.top1)}`, 'Our harness'],
    ['Measured', `Encoder rebuilt from LipLearner’s MIT Core ML release; ${ENCODER.params_m.toFixed(1)}M parameters; matches the original to within 0.00002`, 'Our encoder tools'],
    ['Measured', 'Prototype APK requests the CAMERA permission only; the Lab page cannot make network requests (CSP)', 'aapt2 in CI; HTTP headers'],
    ['Built', 'Voice pack: 208 clips (4 voices × 4 languages × 13 phrases), 2.7 MB, rendered once and played offline', 'Sarvam Bulbul v3; AI4Bharat Indic Parler-TTS (Apache-2.0)'],
  ]
  s.addTable(
    [
      ['Kind', 'Fact', 'Source'].map((t) => ({ text: t, options: { bold: true, color: MUTE, fontSize: 10, fontFace: MONO } })),
      ...facts.map(([k, f, src]) => [
        { text: k, options: { color: k === 'Measured' ? TUR : DIM, fontSize: 11, fontFace: MONO } },
        { text: f, options: { color: BONE, fontSize: 11 } },
        { text: src, options: { color: DIM, fontSize: 10 } },
      ]),
    ],
    { x: M, y: 1.8, w: W - 2 * M, colW: [1.4, 7.6, W - 2 * M - 9.0], border: { type: 'solid', color: THEME.colors.accent5, pt: 0.5 }, fill: { color: INK }, margin: [0.03, 0.1, 0.03, 0.1], valign: 'middle' },
  )
  s.addNotes('Plain list of every quantitative claim in the deck and where it comes from. Targets (end-to-end latency, bundle size) are not listed because they are not yet measured.')
}

// ---------- appendix: team, disclosure, sources ----------
{
  const s = content('Appendix C · Team, disclosure, sources', 'We have shipped on-device AI on this phone before, and we disclose everything that existed before the event', 'Appendix')
  const half = (W - 2 * M - 0.3) / 2
  card(s, M, 1.95, half, 2.3, 'team')
  text(s, 'TEAM HIGHEST IN THE ROOM', { x: M + 0.3, y: 2.12, w: half - 0.6, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: TUR })
  text(
    s,
    [
      { text: 'VeriTransit, iQOO Chennai City Battle: Qwen3-VL-4B on the iQOO 15 NPU via GenieX', options: { bullet: true, breakLine: true } },
      { text: 'DriftSense, SEMICON India 2026: 1st runner-up, sub-pixel image registration', options: { bullet: true, breakLine: true } },
      { text: 'Android in Kotlin and Compose: CameraX, Nearby, BLE (MeshSOS)', options: { bullet: true } },
    ],
    { x: M + 0.3, y: 2.5, w: half - 0.6, h: 1.7, fontSize: 13, color: DIM, paraSpaceAfter: 4 },
  )
  card(s, M + half + 0.3, 1.95, half, 2.3, 'disclosure')
  text(s, 'DISCLOSURE', { x: M + half + 0.6, y: 2.12, w: half - 0.6, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: KUM })
  text(
    s,
    'Pre-existing: the LipLearner method (cited); open-source MediaPipe, the LipLearner encoder (weight terms being confirmed), LiteRT, ONNX Runtime, the Qualcomm AI stack, Sarvam Bulbul and Indic Parler-TTS voices, with attribution; a small feasibility-spike repository, linked. All Finale code is written in the event window, in a new repository. No patient data; volunteers consent.',
    { x: M + half + 0.6, y: 2.5, w: half - 0.6, h: 1.7, fontSize: 12, color: DIM },
  )
  text(s, 'SOURCES', { x: M, y: 4.5, w: 5, h: 0.3, fontSize: 11, bold: true, fontFace: MONO, color: MUTE })
  text(
    s,
    [
      'LipLearner, Su, Fang, Rekimoto, CHI 2023, arxiv.org/abs/2302.05907; github.com/rkmtlab/LipLearner',
      'GLOBOCAN 2024 India fact sheet, gco.iarc.who.int',
      'Mathur et al., hospital-based stroke registries in India, 2020–22',
      'HRA summary of the SRAVI study (critical care); liopa.ai',
      'Fassler et al., 2026, lip-reading after laryngectomy, n = 16',
      'Divya P, Kannada multi-speaker lip-reading dataset, Mendeley Data 2026, doi:10.17632/zbzrbs89pz.1, CC BY 4.0',
      'Qualcomm AI Hub, profile jobs on Samsung Galaxy S26 (SM8850)',
      'Voices: Sarvam AI Bulbul v3; AI4Bharat Indic Parler-TTS (Apache-2.0), rendered at build time',
      'National Cancer Grid head and neck guideline, 2019, ncgindia.org',
      'Liperty, github.com/HereLiesAz/Liperty; MELDER, CHI 2024; Auto-AVSR, github.com/mpc001/auto_avsr',
    ].map((t, k, a) => ({ text: t, options: { bullet: true, breakLine: k < a.length - 1 } })),
    { x: M, y: 4.85, w: W - 2 * M, h: 2.0, fontSize: 11, color: DIM, paraSpaceAfter: 2 },
  )
  s.addNotes('Only verified prior builds are listed here. The feasibility spike repository is linked in the submission form.')
}

pres
  .writeFile({ fileName: OUT })
  .then(() => applyTheme(OUT, THEME))
  .then(() => console.log(`wrote ${path.relative(process.cwd(), OUT)}`))
