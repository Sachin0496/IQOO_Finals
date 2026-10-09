# Exports the deck to PDF with PowerPoint (Windows). Usage: pwsh deck/export_pdf.ps1
$pptx = Join-Path $PSScriptRoot 'Mouna-Phase1-Deck.pptx'
$pdf = Join-Path $PSScriptRoot 'Mouna-Phase1-Deck.pdf'
$app = New-Object -ComObject PowerPoint.Application
try {
  $deck = $app.Presentations.Open($pptx, $true, $false, $false)
  # Metadata that search engines, document readers and AI reviewers see first
  $meta = @{
    Keywords = 'Mouna; silent speech; lip reading; visual speech recognition; laryngectomy; tracheostomy; stroke; AAC; Kannada; Tamil; Hindi; on-device AI; Snapdragon; Hexagon NPU; iQOO 15; offline; privacy; LipLearner'
    Comments = 'Phase 1 deck, iQOO Hackathon, Open Innovation. Every number is measured or cited; see Appendix B (key facts).'
  }
  $props = $deck.BuiltInDocumentProperties
  foreach ($k in $meta.Keys) {
    $prop = [System.__ComObject].InvokeMember('Item', 'GetProperty', $null, $props, @($k))
    [System.__ComObject].InvokeMember('Value', 'SetProperty', $null, $prop, @($meta[$k])) | Out-Null
  }
  $deck.SaveAs($pdf, 32)
  $deck.Close()
} finally { $app.Quit() }
Write-Output "wrote $pdf"
