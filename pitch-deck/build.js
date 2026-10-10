// Mouna pitch deck: "Every soul deserves a voice"
// Build: NODE_PATH=$(npm root -g) node build.js
const path = require("path");
const pptxgen = require("pptxgenjs");
const { applyTheme } = require(process.env.APPLY_THEME ||
  "/Users/sachin/Library/Application Support/Claude/local-agent-mode-sessions/skills-plugin/dbf10fba-c429-41d0-84aa-bbb3119fb917/3778b01f-114a-4cec-a68b-dba4ee4bd57b/skills/pptx/scripts/apply_theme.js");

const THEME = {
  name: "Mouna Beige and Burgundy",
  headFontFace: "Cambria",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "3F3B38", // warm grey: all body text
    lt1: "F6F0E6", // pale beige: slide background
    dk2: "6E6862", // muted grey: captions
    lt2: "FFFDF9", // warm white: cards
    accent1: "7A1F2B", // burgundy: charts, key numbers, emphasis only
    accent2: "B5838B", // rose tint: secondary series
    accent3: "9A948C", // grey
    accent4: "E3D6BF", // sand: dividers, quiet fills
    accent5: "55141E", // deep burgundy
    accent6: "C9BBA4", // dark sand
    hlink: "7A1F2B",
    folHlink: "6E6862",
  },
};
const HEX = { text: "3F3B38", muted: "6E6862", bg: "F6F0E6", card: "FFFDF9", burg: "7A1F2B", sand: "E3D6BF", darkSand: "C9BBA4", rose: "B5838B" };

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.33 x 7.5 in
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = "Mouna: every soul deserves a voice";
pres.author = "Team Mouna";
pres.company = "Mouna";
const C = pres.SchemeColor;
const S = pres.ShapeType;

const MX = 0.7; // side margin
const CW = 13.333 - 2 * MX; // content width

// ---------- layouts ----------
pres.defineSlideMaster({
  title: "COVER",
  background: { color: HEX.bg },
  objects: [],
});
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: HEX.bg },
  objects: [
    {
      placeholder: {
        options: { name: "title", type: "title", x: MX, y: 0.45, w: CW, h: 1.2, fontSize: 32, bold: true, color: C.text1, align: "left", valign: "top", margin: 0 },
        text: "",
      },
    },
    { text: { text: "Mouna  ·  Every soul deserves a voice", options: { x: MX, y: 6.98, w: 6, h: 0.3, fontSize: 10, color: C.text2, margin: 0 } } },
  ],
  slideNumber: { x: 13.333 - MX - 0.6, y: 6.98, w: 0.6, h: 0.3, fontSize: 10, color: C.text2, align: "right" },
});

// ---------- helpers ----------
const shadow = () => ({ type: "outer", color: "6E6862", opacity: 0.14, blur: 8, offset: 2, angle: 90 });
function card(slide, name, x, y, w, h, fill) {
  slide.addShape(S.roundRect, { x, y, w, h, rectRadius: 0.12, fill: { color: fill || C.background2 }, line: { color: C.accent4, width: 0.75 }, shadow: shadow(), objectName: name });
}
function badge(slide, name, n, x, y, d, fill, txt) {
  slide.addShape(S.ellipse, { x, y, w: d, h: d, fill: { color: fill || C.accent1 }, line: { color: fill || C.accent1, width: 0 }, objectName: name });
  slide.addText(String(n), { x, y, w: d, h: d, align: "center", valign: "middle", fontSize: 14, bold: true, color: txt || C.background1, margin: 0, isTextBox: true, objectName: name + " number" });
}
function rings(slide, cx, cy, radii) {
  radii.forEach((r, i) => {
    slide.addShape(S.ellipse, { x: cx - r, y: cy - r, w: 2 * r, h: 2 * r, fill: { type: "none" }, line: { color: C.accent1, width: 1.25, transparency: [0, 30, 55, 78][i] }, objectName: "Voice ring " + (i + 1) });
  });
  slide.addShape(S.ellipse, { x: cx - 0.45, y: cy - 0.45, w: 0.9, h: 0.9, fill: { color: C.accent1 }, line: { color: C.accent1, width: 0 }, objectName: "Voice core" });
}
function bigStat(slide, name, value, label, x, y, w, h, valueSize) {
  card(slide, name + " card", x, y, w, h);
  slide.addText(value, { x: x + 0.3, y: y + 0.1, w: w - 0.6, h: 0.62, fontFace: "+mj-lt", fontSize: valueSize || 36, bold: true, color: C.accent1, valign: "bottom", margin: 0, isTextBox: true, objectName: name + " value" });
  slide.addText(label, { x: x + 0.3, y: y + 0.76, w: w - 0.6, h: h - 0.9, fontSize: 14, color: C.text1, valign: "top", margin: 0, isTextBox: true, objectName: name + " label" });
}
const chartText = { fontFace: "+mn-lt", fontSize: 13, color: HEX.text };

// =====================================================================
// 1. Cover
// =====================================================================
pres.addSection({ title: "Open" });
{
  const s = pres.addSlide({ masterName: "COVER", sectionTitle: "Open" });
  rings(s, 10.65, 3.75, [0.95, 1.65, 2.35, 3.0]);
  s.addText("MOUNA   ·   மௌனம்   ·   ಮೌನ   ·   मौन", { x: MX, y: 1.0, w: 8, h: 0.4, fontSize: 14, color: C.text2, charSpacing: 3, margin: 0, isTextBox: true, objectName: "Wordmark" });
  s.addText(
    [
      { text: "Every soul deserves a ", options: { color: C.text1 } },
      { text: "voice.", options: { color: C.accent1 } },
    ],
    { x: MX, y: 1.9, w: 7.6, h: 3.0, fontFace: "+mj-lt", fontSize: 62, bold: true, valign: "top", margin: 0, isTextBox: true, objectName: "Cover title" }
  );
  s.addText("A phone app that turns a silent mouth or a sign into speech, in the language the listener needs.", { x: MX, y: 5.1, w: 6.5, h: 0.9, fontSize: 18, color: C.text2, margin: 0, isTextBox: true, objectName: "Cover subtitle" });
  s.addText("iQOO Hackathon 2026  ·  Grand Finale  ·  Bengaluru", { x: MX, y: 6.7, w: 8, h: 0.3, fontSize: 12, color: C.text2, margin: 0, isTextBox: true, objectName: "Event line" });
  s.addNotes("Open slowly. Mouna means silence. Everyone in this room has said something today without thinking about it. For tens of millions of people that is the one thing they cannot do. Our line: every soul deserves a voice.");
}

// =====================================================================
// 2. Problem
// =====================================================================
pres.addSection({ title: "Problem" });
{
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Problem" });
  s.addText(
    [
      { text: "Over ", options: {} },
      { text: "97 million", options: { color: C.accent1 } },
      { text: " people cannot rely on their voice, and the help available is scarce", options: {} },
    ],
    { placeholder: "title" }
  );

  // chart card
  card(s, "Global chart card", MX, 1.95, 7.2, 4.65);
  s.addText("People affected worldwide, in millions", { x: MX + 0.35, y: 2.1, w: 6.5, h: 0.4, fontSize: 16, bold: true, color: C.text1, margin: 0, isTextBox: true, objectName: "Chart title" });
  s.addChart(
    pres.charts.BAR,
    [{ name: "Millions of people", labels: ["Disabling hearing loss", "Cannot rely on speech", "Deaf, rely on sign language"], values: [430, 97, 70] }],
    {
      x: MX + 0.25, y: 2.55, w: 6.7, h: 3.1, barDir: "bar", chartColors: [HEX.darkSand, HEX.burg, HEX.darkSand], barGapWidthPct: 55,
      showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: '0"M"', dataLabelFontFace: "+mn-lt", dataLabelFontSize: 16, dataLabelColor: HEX.text, dataLabelFontBold: true,
      catAxisOrientation: "maxMin", catAxisLabelFontFace: "+mn-lt", catAxisLabelFontSize: 14, catAxisLabelColor: HEX.text, catGridLine: { style: "none" },
      valAxisHidden: true, valGridLine: { style: "none" }, valAxisMinVal: 0, valAxisMaxVal: 520, showLegend: false, showTitle: false,
      altText: "Bar chart: 430 million people with disabling hearing loss, 97 million who cannot rely on speech, 70 million deaf people who use sign language.",
    }
  );
  s.addText("Groups overlap, so we never add them up. Highlighted: the group Mouna is built for first.", { x: MX + 0.35, y: 5.7, w: 6.5, h: 0.35, fontSize: 12, color: C.text2, margin: 0, isTextBox: true, objectName: "Chart takeaway" });
  s.addText("Sources: WHO fact sheet on hearing loss (2026); J. Light, Penn State (AAC research); World Federation of the Deaf.", { x: MX + 0.35, y: 6.05, w: 6.5, h: 0.45, fontSize: 10, color: C.text2, margin: 0, isTextBox: true, objectName: "Chart sources" });

  // India stat stack
  const rx = 8.2, rw = 13.333 - MX - rx;
  const stats = [
    ["≈1.9 million", "Indians with a speech disability (Census 2011)"],
    ["≈300", "certified Indian Sign Language interpreters for about 5 million deaf Indians (ISLRTC)"],
    ["≈2,500", "speech therapists and audiologists for the whole country (ISHA, 2017)"],
  ];
  const sh = 1.4, gap = (4.65 - 3 * sh) / 2;
  stats.forEach(([v, l], i) => bigStat(s, "India stat " + (i + 1), v, l, rx, 1.95 + i * (sh + gap), rw, sh, 34));
  s.addNotes("The scale: WHO counts 430 million people with disabling hearing loss. Researchers put the number of people who cannot rely on speech at more than 97 million. About 70 million deaf people use sign language. These groups overlap, so we never add them. In India: Census 2011 counts about 1.9 million people with a speech disability. There are only about 300 certified Indian Sign Language interpreters for roughly 5 million deaf Indians, and about 2,500 speech therapists and audiologists for the whole country. Hearing-loss figures are WHO; the 97 million figure is from AAC research (Penn State, J. Light); interpreter and therapist counts vary by source and year.");
}

// =====================================================================
// 3. Solution
// =====================================================================
pres.addSection({ title: "Solution" });
{
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Solution" });
  s.addText(
    [
      { text: "Mouth it or sign it. Mouna ", options: {} },
      { text: "speaks it", options: { color: C.accent1 } },
      { text: " in the language the listener needs", options: {} },
    ],
    { placeholder: "title" }
  );

  const cw = 2.35, ag = 0.725, cy = 1.95, ch = 3.1;
  const steps = [
    ["Mouth or sign", "Silent lip movement, Indian Sign Language, or any movement that still works: a nod, a blink, a look."],
    ["Mouna understands", "It learns how this person moves from a few examples. When unsure, it shows choices instead of guessing."],
    ["Speaks in any language", "Out loud in English, Hindi, Tamil or Kannada, with more to come. Nothing leaves the phone."],
  ];
  steps.forEach(([h, b], i) => {
    const x = MX + i * (cw + ag);
    card(s, "Step card " + (i + 1), x, cy, cw, ch);
    badge(s, "Step badge " + (i + 1), i + 1, x + 0.25, cy + 0.25, 0.45);
    s.addText(h, { x: x + 0.25, y: cy + 0.85, w: cw - 0.5, h: 0.75, fontFace: "+mj-lt", fontSize: 19, bold: true, color: C.text1, valign: "top", margin: 0, isTextBox: true, objectName: "Step title " + (i + 1) });
    s.addText(b, { x: x + 0.25, y: cy + 1.65, w: cw - 0.5, h: 1.35, fontSize: 14, color: C.text1, valign: "top", margin: 0, isTextBox: true, objectName: "Step body " + (i + 1) });
    if (i < 2) s.addShape(S.line, { x: x + cw + 0.12, y: cy + ch / 2, w: ag - 0.24, h: 0, line: { color: C.accent1, width: 2, endArrowType: "triangle" }, objectName: "Flow arrow " + (i + 1) });
  });

  // example callout
  s.addShape(S.roundRect, { x: MX, y: 5.35, w: 8.5, h: 1.25, rectRadius: 0.12, fill: { color: C.accent1 }, line: { color: C.accent1, width: 0 }, objectName: "Example callout" });
  s.addText(
    [
      { text: "In practice", options: { bold: true, breakLine: true } },
      { text: "A patient mouths “I need water” in Tamil. The nurse hears it in Kannada, instantly, with no internet.", options: {} },
    ],
    { x: MX + 0.3, y: 5.35, w: 7.9, h: 1.25, fontSize: 16, color: C.background1, valign: "middle", margin: 0, isTextBox: true, objectName: "Example text" }
  );

  // phone
  const ph = 4.45, pw = ph * (315 / 700), px = 9.95, py = 1.95;
  s.addShape(S.roundRect, { x: px - 0.08, y: py - 0.08, w: pw + 0.16, h: ph + 0.16, rectRadius: 0.22, fill: { color: C.text1 }, line: { color: C.text1, width: 0 }, shadow: shadow(), objectName: "Phone frame" });
  s.addImage({ path: path.join(__dirname, "assets", "mouna-sign.png"), x: px, y: py, w: pw, h: ph, altText: "Mouna app screen in Sign mode with a person signing to the camera, with Lips, Voice and Sign modes at the bottom of the camera view", objectName: "App screenshot" });
  s.addText("The Mouna app: Lips, Voice or Sign", { x: px - 0.55, y: py + ph + 0.2, w: pw + 1.1, h: 0.3, fontSize: 11, color: C.text2, align: "center", margin: 0, isTextBox: true, objectName: "Screenshot caption" });
  s.addNotes("The idea in one breath. A person who cannot make sound mouths a word or signs it. Mouna recognises it on the phone, and says it aloud in the language of whoever is listening. A Tamil speaker can be heard in Kannada. The screenshot is the real app: the three modes Lips, Voice and Sign sit on the camera screen. Sign mode uses a published Indian Sign Language model with 263 signs; we have not yet tested it with real signers, and we say so.");
}

// =====================================================================
// 4. Evidence
// =====================================================================
pres.addSection({ title: "Proof" });
{
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Proof" });
  s.addText(
    [
      { text: "It works today: ", options: {} },
      { text: "89% right", options: { color: C.accent1 } },
      { text: " after teaching just five examples", options: {} },
    ],
    { placeholder: "title" }
  );

  card(s, "Accuracy chart card", MX, 1.95, 6.9, 4.4);
  s.addText("Right phrase on the first guess, by examples taught (%)", { x: MX + 0.35, y: 2.1, w: 6.2, h: 0.4, fontSize: 16, bold: true, color: C.text1, margin: 0, isTextBox: true, objectName: "Chart title" });
  s.addChart(
    pres.charts.BAR,
    [{ name: "Top-1 accuracy (%)", labels: ["1 example", "3 examples", "5 examples"], values: [71.9, 85.2, 89.0] }],
    {
      x: MX + 0.25, y: 2.55, w: 6.4, h: 3.1, barDir: "col", chartColors: [HEX.darkSand, HEX.rose, HEX.burg], barGapWidthPct: 50,
      showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: '0.0"%"', dataLabelFontFace: "+mn-lt", dataLabelFontSize: 16, dataLabelColor: HEX.text, dataLabelFontBold: true,
      catAxisLabelFontFace: "+mn-lt", catAxisLabelFontSize: 14, catAxisLabelColor: HEX.text, catGridLine: { style: "none" }, catAxisLineShow: true,
      valAxisHidden: true, valGridLine: { color: HEX.sand, size: 0.5 }, valAxisMinVal: 0, valAxisMaxVal: 100, valAxisMajorUnit: 25, showLegend: false, showTitle: false,
      altText: "Column chart: right phrase on first guess is 71.9 percent with 1 example, 85.2 percent with 3 examples and 89.0 percent with 5 examples.",
    }
  );
  s.addText("Public Kannada lip-reading dataset: 7 speakers, 27 words and phrases, learned per speaker, tested on clips never shown during teaching.", { x: MX + 0.35, y: 5.65, w: 6.2, h: 0.6, fontSize: 11, color: C.text2, margin: 0, isTextBox: true, objectName: "Chart note" });

  const rx = 8.1, rw = 13.333 - MX - rx;
  const kp = [
    ["99.5%", "right whenever Mouna decides to speak (held-out speakers)"],
    ["18 ms", "to read 1.9 seconds of lips on the phone’s NPU (iQOO 15 chip), offline"],
    ["23% → 7%", "words it was never taught, spoken by mistake, before and after Careful mode"],
  ];
  const kh = 1.3, kg = (4.4 - 3 * kh) / 2;
  kp.forEach(([v, l], i) => bigStat(s, "Proof stat " + (i + 1), v, l, rx, 1.95 + i * (kh + kg), rw, kh, 32));

  s.addText("Measured on a public dataset and on-device profiling (Qualcomm AI Hub). Tests on our own silent-speech recordings and with real signers come next.", { x: MX, y: 6.5, w: CW, h: 0.4, fontSize: 12, italic: true, color: C.text2, margin: 0, isTextBox: true, objectName: "Honesty note" });
  s.addNotes("Why you can trust this. Everything here is measured, not promised. On a public Kannada lip-reading dataset, after five examples per phrase the first guess is right 89% of the time. When Mouna chooses to speak, it is right 99.5% of the time (held-out speakers); when unsure it shows choices instead. Careful mode cuts untaught words spoken by mistake from 23% to about 7%. The encoder runs entirely on the Snapdragon NPU in 18 ms per 1.9 seconds of lips. The limits: this is a public dataset, not our own silent-speech recordings, and not yet real signers. Source files: deck/data/*.json in the repository.");
}

// =====================================================================
// 5. Architecture
// =====================================================================
pres.addSection({ title: "Architecture" });
{
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Architecture" });
  s.addText(
    [
      { text: "Everything runs on the phone: see, understand, ", options: {} },
      { text: "decide", options: { color: C.accent1 } },
      { text: ", then speak", options: {} },
    ],
    { placeholder: "title" }
  );

  const cols = [
    ["SEE", "Camera and landmarks", ["Front camera at 30 fps", "Face, hands and body landmarks (MediaPipe)", "Mouth cropped and cleaned up"]],
    ["UNDERSTAND", "Three channels", ["Lip encoder on the NPU, 18 ms", "Indian Sign Language model, 263 signs", "Blink, nod, gaze and personal switch"]],
    ["DECIDE", "The safety core", ["Learns this person from a few examples", "Speaks only when sure, else shows 2 to 4 choices", "Urgent actions always confirmed"]],
    ["SPEAK", "Voice and delivery", ["Voice in the listener’s language, offline fallback", "Caregiver phone link, no internet", "Web-link call, no SIM needed"]],
  ];
  const cw = 2.65, ag = 0.44, cy = 1.95, ch = 3.55;
  cols.forEach(([tag, head, items], i) => {
    const x = MX + i * (cw + ag);
    const hot = i === 2;
    card(s, "Layer card " + (i + 1), x, cy, cw, ch, hot ? C.accent1 : C.background2);
    s.addText(tag, { x: x + 0.25, y: cy + 0.25, w: cw - 0.5, h: 0.3, fontSize: 12, bold: true, charSpacing: 2, color: hot ? C.background1 : C.accent1, margin: 0, isTextBox: true, objectName: "Layer tag " + (i + 1) });
    s.addText(head, { x: x + 0.25, y: cy + 0.6, w: cw - 0.5, h: 0.8, valign: "top", fontFace: "+mj-lt", fontSize: 19, bold: true, color: hot ? C.background1 : C.text1, margin: 0, isTextBox: true, objectName: "Layer title " + (i + 1) });
    s.addText(
      items.map((t, k) => ({ text: t, options: { breakLine: k < items.length - 1, paraSpaceAfter: 9 } })),
      { x: x + 0.25, y: cy + 1.5, w: cw - 0.5, h: ch - 1.7, fontSize: 14, color: hot ? C.background1 : C.text1, valign: "top", margin: 0, isTextBox: true, objectName: "Layer items " + (i + 1) }
    );
    if (i < 3) s.addShape(S.line, { x: x + cw + 0.08, y: cy + ch / 2, w: ag - 0.16, h: 0, line: { color: C.accent1, width: 2, endArrowType: "triangle" }, objectName: "Layer arrow " + (i + 1) });
  });
  s.addShape(S.roundRect, { x: MX, y: 5.8, w: CW, h: 0.7, rectRadius: 0.12, fill: { color: C.background2 }, line: { color: C.accent4, width: 0.75 }, objectName: "Privacy band" });
  s.addText("On-device on the Snapdragon NPU  ·  no cloud needed  ·  no video stored  ·  one phone, one camera", { x: MX, y: 5.8, w: CW, h: 0.7, fontSize: 16, bold: true, color: C.accent1, align: "center", valign: "middle", margin: 0, isTextBox: true, objectName: "Privacy text" });
  s.addNotes("Four stages, one phone. SEE: the camera and MediaPipe landmarks. UNDERSTAND: the lip encoder on the Qualcomm NPU, the Indian Sign Language model, and simple channels like blinks and nods for people who cannot mouth clearly. DECIDE is the heart: Mouna learns this person from a few examples, speaks only when it is sure, shows two to four choices when it is not, and always confirms urgent actions. SPEAK: a voice in the listener's language with an offline fallback, a caregiver link over the local network, and a web-link call that needs no SIM. Privacy: no video is stored and nothing needs the cloud.");
}

// =====================================================================
// 6. Pipeline / roadmap
// =====================================================================
pres.addSection({ title: "Roadmap" });
{
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Roadmap" });
  s.addText(
    [
      { text: "Built and measured today, with a clear path to ", options: {} },
      { text: "sign language", options: { color: C.accent1 } },
      { text: " and real phone calls", options: {} },
    ],
    { placeholder: "title" }
  );

  const cw = 3.75, gap = 0.34, ly = 2.45;
  s.addShape(S.line, { x: MX + 0.17, y: ly, w: CW - 0.34, h: 0, line: { color: C.accent6, width: 2 }, objectName: "Timeline line" });
  const phases = [
    ["DONE", "Built and measured", ["Lip reading on the phone’s NPU, taught in about a minute", "Choices and yes/no questions when unsure", "Voices in four languages, caregiver phone link", "Calls with no SIM, through a web link"]],
    ["IN PROGRESS", "Proving it for real", ["Sign mode tested with real signers (263-sign model already integrated)", "Our own silent-speech recordings", "Native-speaker review of Tamil, Kannada and Hindi phrases", "A real person on the other end of a call"]],
    ["NEXT", "Growing the reach", ["Real phone-number calls through a telephony bridge (planned, 6 to 10 weeks)", "More Indian languages, bigger sign vocabulary", "Small on-device language model for new sentences", "Pilots with speech therapists and hospitals, with consent"]],
  ];
  phases.forEach(([tag, head, items], i) => {
    const x = MX + i * (cw + gap);
    const fill = i === 0 ? C.accent1 : i === 1 ? C.background1 : C.background1;
    const lineC = i === 2 ? C.accent3 : C.accent1;
    s.addShape(S.ellipse, { x: x, y: ly - 0.17, w: 0.34, h: 0.34, fill: { color: fill }, line: { color: lineC, width: 2 }, objectName: "Timeline node " + (i + 1) });
    s.addText(tag, { x: x + 0.48, y: ly - 0.17, w: [0.95, 1.55, 0.85][i], h: 0.34, fill: { color: HEX.bg }, align: "center", fontSize: 12, bold: true, charSpacing: 2, color: i === 2 ? C.text2 : C.accent1, valign: "middle", margin: 0, isTextBox: true, objectName: "Phase tag " + (i + 1) });
    card(s, "Phase card " + (i + 1), x, 2.9, cw, 3.55);
    s.addText(head, { x: x + 0.3, y: 3.1, w: cw - 0.6, h: 0.45, fontFace: "+mj-lt", fontSize: 19, bold: true, color: C.text1, margin: 0, isTextBox: true, objectName: "Phase title " + (i + 1) });
    s.addText(
      items.map((t, k) => ({ text: t, options: { bullet: { indent: 14 }, breakLine: k < items.length - 1, paraSpaceAfter: 7 } })),
      { x: x + 0.3, y: 3.65, w: cw - 0.6, h: 2.65, fontSize: 14, color: C.text1, valign: "top", margin: 0, isTextBox: true, objectName: "Phase items " + (i + 1) }
    );
  });
  s.addNotes("The pipeline, honestly labelled. Done: the lip encoder on the NPU, teaching in about a minute, choices when unsure, voices in four languages, the caregiver link and calls through a web link so the demo phone needs no SIM. In progress: real signers for sign mode, our own silent-speech recordings, native-speaker review of the phrase packs. Next: real phone-number calls through a telephony bridge such as Twilio (we estimate six to ten weeks), more Indian languages, a small on-device language model, and pilots with speech therapists and hospitals with consent. Android does not let a third-party app inject audio into a carrier call, which is why the bridge is the production path.");
}

// =====================================================================
// 7. Who benefits
// =====================================================================
pres.addSection({ title: "Impact" });
{
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: "Impact" });
  s.addText(
    [
      { text: "97 million", options: { color: C.accent1 } },
      { text: " people live without reliable speech. Mouna needs only the phone they own", options: {} },
    ],
    { placeholder: "title" }
  );

  // left: hero stat + cost contrast
  card(s, "Hero card", MX, 1.95, 4.6, 2.45);
  s.addText("97M", { x: MX + 0.3, y: 2.05, w: 4.0, h: 1.3, fontFace: "+mj-lt", fontSize: 72, bold: true, color: C.accent1, valign: "middle", margin: 0, isTextBox: true, objectName: "Hero value" });
  s.addText("people worldwide cannot rely on speech to communicate (AAC research, Penn State)", { x: MX + 0.3, y: 3.35, w: 4.0, h: 0.9, fontSize: 14, color: C.text1, valign: "top", margin: 0, isTextBox: true, objectName: "Hero label" });
  card(s, "Cost card", MX, 4.6, 4.6, 1.85);
  s.addText("$4,000–23,000", { x: MX + 0.3, y: 4.7, w: 4.0, h: 0.7, fontFace: "+mj-lt", fontSize: 30, bold: true, color: C.accent1, valign: "middle", margin: 0, isTextBox: true, objectName: "Cost value" });
  s.addText("what a dedicated eye-gaze speech device costs, imported and English-first. Mouna is an app on the phone they already have.", { x: MX + 0.3, y: 5.4, w: 4.0, h: 0.95, fontSize: 14, color: C.text1, valign: "top", margin: 0, isTextBox: true, objectName: "Cost label" });

  // right: India chart
  const rx = 5.6, rw = 13.333 - MX - rx;
  card(s, "India chart card", rx, 1.95, rw, 4.5);
  s.addText("In India: people in the groups Mouna is built for, in millions", { x: rx + 0.35, y: 2.1, w: rw - 0.7, h: 0.4, fontSize: 16, bold: true, color: C.text1, margin: 0, isTextBox: true, objectName: "Chart title" });
  s.addChart(
    pres.charts.BAR,
    [{ name: "Millions of people in India", labels: ["Hearing impairment (sign users)", "Stroke survivors with aphasia", "Speech disability"], values: [5.07, 2.0, 1.88] }],
    {
      x: rx + 0.25, y: 2.55, w: rw - 0.5, h: 2.6, barDir: "bar", chartColors: [HEX.burg], barGapWidthPct: 55,
      showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: '0.0"M"', dataLabelFontFace: "+mn-lt", dataLabelFontSize: 16, dataLabelColor: HEX.text, dataLabelFontBold: true,
      catAxisOrientation: "maxMin", catAxisLabelFontFace: "+mn-lt", catAxisLabelFontSize: 14, catAxisLabelColor: HEX.text, catGridLine: { style: "none" },
      valAxisHidden: true, valGridLine: { style: "none" }, valAxisMinVal: 0, valAxisMaxVal: 6.5, showLegend: false, showTitle: false,
      altText: "Bar chart for India: 5.07 million with hearing impairment, 2.0 million stroke survivors with aphasia, 1.88 million with a speech disability.",
    }
  );
  s.addText("Plus about 70,000 new laryngeal and hypopharyngeal cancer cases a year, where surgery can take the voice. Groups overlap, so we never add them up.", { x: rx + 0.35, y: 5.2, w: rw - 0.7, h: 0.6, fontSize: 12, color: C.text1, margin: 0, isTextBox: true, objectName: "Chart takeaway" });
  s.addText("Sources: Census 2011 (MoSPI); Pauranik et al. 2019; GLOBOCAN 2024; Penn State; Tobii Dynavox price range.", { x: rx + 0.35, y: 5.85, w: rw - 0.7, h: 0.45, fontSize: 10, color: C.text2, margin: 0, isTextBox: true, objectName: "Chart sources" });
  s.addNotes("Who can benefit. Worldwide, more than 97 million people cannot rely on speech. In India, Census 2011 counts about 5 million with hearing impairment (the sign users), about 1.9 million with a speech disability, and research estimates about 2 million living with aphasia after stroke. About 70,000 new laryngeal and hypopharyngeal cancers are diagnosed each year, where surgery can remove the voice. These groups overlap, so we never add them. We do not claim every one of these people can use Mouna today; it needs a movement the camera can see. The contrast that matters: dedicated eye-gaze speech devices cost 4,000 to 23,000 dollars. Mouna is an app on the phone they already have. Cost range and 97M are from secondary sources; confirm against primary papers before external publication.");
}

// =====================================================================
// 8. Thank you
// =====================================================================
{
  const s = pres.addSlide({ masterName: "COVER" });
  rings(s, 10.65, 3.75, [0.95, 1.65, 2.35, 3.0]);
  s.addText("Thank you", { x: MX, y: 1.9, w: 7.6, h: 1.4, fontFace: "+mj-lt", fontSize: 66, bold: true, color: C.text1, valign: "top", margin: 0, isTextBox: true, objectName: "Thank you title" });
  s.addText("Every soul deserves a voice.", { x: MX, y: 3.4, w: 7.6, h: 0.8, fontFace: "+mj-lt", fontSize: 30, italic: true, color: C.accent1, valign: "top", margin: 0, isTextBox: true, objectName: "Closing line" });
  s.addText(
    [
      { text: "Try the prototype: ", options: { color: C.text2 } },
      { text: "mouna-spike.vercel.app", options: { color: C.accent1, bold: true, hyperlink: { url: "https://mouna-spike.vercel.app" } } },
    ],
    { x: MX, y: 4.8, w: 7.6, h: 0.4, fontSize: 18, margin: 0, isTextBox: true, objectName: "Prototype link" }
  );
  s.addText("Team Mouna  ·  Maadhav  ·  Sachin  ·  Nakul", { x: MX, y: 5.4, w: 7.6, h: 0.4, fontSize: 16, color: C.text1, margin: 0, isTextBox: true, objectName: "Team" });
  s.addNotes("Close on the line. Every soul deserves a voice. Ask the room to try the prototype. Questions welcome.");
}

(async () => {
  const out = path.join(__dirname, "Mouna-Pitch-Deck.pptx");
  await pres.writeFile({ fileName: out });
  await applyTheme(out, THEME);
  console.log("wrote", out);
})();
