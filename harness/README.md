# Harness

Scores Mouna Lab exports with the spike protocol ([docs/protocol.md](../docs/protocol.md)).
`DTWHead` is a line-for-line mirror of the Lab recogniser; a parity test in both languages keeps them identical.
`PrototypeHead` is the plan A head for encoder embeddings (phase 4).

```bash
pip install -e harness[dev]
python -m mouna_harness synth data/synthetic            # dry run, marked synthetic
python -m mouna_harness evaluate data/spike --publish deck/data/spike-results.json
python -m pytest harness
```

`--publish` refuses synthetic data, so the deck can only ever show measured numbers.
