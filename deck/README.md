# Deck

`Mouna-Phase1-Deck.pptx` (and `.pdf`) is generated. Do not edit it by hand; edit `build.js` and rebuild.

```bash
cd deck && npm install && npm run build      # writes the .pptx
pwsh deck/export_pdf.ps1                     # PowerPoint → .pdf (Windows)
```

- Slide titles are claims; the deck reads correctly from titles alone.
- Every number has a source in the speaker notes, or comes from `data/spike-results.json`.
- `data/spike-results.json` is written by `python -m mouna_harness evaluate … --publish deck/data/spike-results.json`.
  When it exists, slide 6 switches from "in progress" to the measured table. Synthetic results are refused.
- Targets (latency, bundle size) are labelled as targets until measured.
- Fonts: Bodoni MT (headings), Segoe UI Semilight (body), Consolas (labels), Nirmala UI (Indian scripts).
  All static and shipped with Windows/Office, so PowerPoint embeds them in the PDF. Submit the PDF.
