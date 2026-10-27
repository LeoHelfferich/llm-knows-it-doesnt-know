# Data

| Path | What it is | Committed |
|---|---|---|
| `raw/dev-v2.0.json` | SQuAD 2.0 dev set, downloaded by `fetch-data`, SHA-256 verified | no (git-ignored, 4.4 MB, public download) |
| `sample.json` | The 100 answerable + 100 unanswerable questions with their passages, drawn with a fixed seed | yes |
| `disputed.json` | Item ids you flag for exclusion after manual review (`analyze` writes both the full and the "without disputed" numbers) | yes |
| `runs/dry-run/` | `manifest.json` + `calls.jsonl` of the 10-item dry run | yes, after it has been run |
| `runs/full/` | `manifest.json` + `calls.jsonl` of the full run (6000 calls) | yes, after it has been run |
| `runs/<id>/review-candidates.json` | Items the analysis suggests you look at (never applied automatically) | yes |

`calls.jsonl` holds one JSON object per API attempt: item id, model, prompt variant, repetition, the raw reply, finish reason, token counts, the cost reported by OpenRouter, the provider that served the call and the latency. It does not contain the prompt text (re-render it from `sample.json` and the prompt templates) and never any credentials.

## Licensing of the data

`sample.json` (and the question texts, passages and gold answers that the pages under `docs/data/` repeat) are derived from SQuAD 2.0 and therefore stay under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/):

> Pranav Rajpurkar, Robin Jia and Percy Liang. *Know What You Don't Know: Unanswerable Questions for SQuAD.* ACL 2018.
> https://rajpurkar.github.io/SQuAD-explorer/

The model replies, manifests and computed metrics are original to this repository (MIT, like the code), but any file that quotes SQuAD text carries the CC BY-SA 4.0 share-alike obligation.

## disputed.json

```json
{
  "items": [
    { "id": "5a57cf7a770dc0001aeefda5", "reason": "The passage does state the answer", "reviewer": "Leo" }
  ]
}
```

Only ids that exist in `sample.json` are accepted. After editing, re-run `analyze`; the `excludingDisputed` scope in `docs/data/results.json` is recomputed from the raw calls.
