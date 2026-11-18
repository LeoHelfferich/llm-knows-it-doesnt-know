# llm-knows-it-doesnt-know

**Do LLMs admit when the answer isn't in the context?** A reproducible Kotlin experiment on the unanswerable questions of SQuAD 2.0.

This is the sequel in an article series by Leo Helfferich. Part 1 is [`llm-judges-and-trust`](https://github.com/LeoHelfferich/llm-judges-and-trust). The repository follows the same approach: Kotlin/JVM + Gradle + JUnit, all model calls through [OpenRouter](https://openrouter.ai), a run manifest, a cost estimate before any paid run, a hard EUR 10 cap, results committed as data, and static HTML pages under `docs/` that load the real result JSON.

> **Status:** the full run is published. On 2026-10-27, 6,000 of 6,000 calls succeeded (EUR 1.26, under a runtime cap of EUR 5). `docs/data/results.json` and `docs/data/items.json` are what `analyze` computes from `data/runs/full/`. The manifest records no git commit: `git rev-parse` did not succeed where the calls were made. Re-running `analyze` on this repository regenerates those files from the committed calls.

## Research question

When the answer is **not** in the provided passage, how often does a model invent one instead of saying it is not there? And does a stricter prompt reduce the invention at the price of **over-refusing** questions that *are* answerable?

## Quick start

Requirements: JDK 21 (the Gradle toolchain is pinned to 21). Nothing else; the wrapper downloads Gradle.

```bash
./gradlew test                                  # 92 tests, fake client, no key, no OpenRouter calls; one skips until fetch-data
./gradlew run --args="estimate"                 # expected calls and cost, no key needed
./gradlew run --args="run --dry-run --mock"     # offline pipeline check with synthetic answers
```

For cleaner output and exit codes you can build once and call the script directly:

```bash
./gradlew installDist
alias kid=build/install/llm-knows-it-doesnt-know/bin/llm-knows-it-doesnt-know
kid estimate
```

## Commands

| Command | What it does |
|---|---|
| `fetch-data` | Downloads the SQuAD 2.0 dev set to `data/raw/` (git-ignored) and verifies its SHA-256. |
| `sample [--check]` | Draws the seeded sample into `data/sample.json`. `--check` re-draws and fails if the committed file differs. |
| `estimate [--refresh] [--from-run DIR] [--dry-run]` | Calls and cost for the full run and the dry run. Uses the committed OpenRouter pricing snapshot; `--refresh` re-reads OpenRouter's public model list (no key) and rewrites the snapshot; `--from-run` replaces the token assumptions by the token counts observed in an earlier run (use it on the dry run before the full run). |
| `run [--dry-run] [--yes] [--run-id ID] [--cap-eur X] [--mock]` | Executes the calls. Prints the estimate first and asks for `yes` unless `--yes` is given. `--dry-run` = 10 items (5 answerable + 5 unanswerable) x 5 models x 2 prompts x 1 rep = 100 calls. Without it: 200 items x 5 x 2 x 3 reps = 6000 calls. `--mock` uses an offline simulator (no key, no network, synthetic output under `build/`). |
| `analyze [--run DIR] [--out FILE] [--items-out FILE] [--review-out FILE] [--disputed FILE]` | Computes all metrics from the raw calls. For the real full run it writes `docs/data/results.json` and `docs/data/items.json`; for anything else it writes next to the run. |

Exit codes: `0` ok, `1` runtime failure or refusal (for example cost), `2` usage error.

## Reproduction steps

The published calls are already in `data/runs/full/` and `data/runs/dry-run/`. `analyze` only reads them. The steps below are how that run was produced, and how to produce another one.

```bash
# 1. Data and sample (public data only)
./gradlew run --args="fetch-data"
./gradlew run --args="sample --check"       # proves data/sample.json is exactly what seed 2026 yields

# 2. Cost, before any paid call
./gradlew run --args="estimate"

# 3. Dry run: 100 calls, about EUR 0.07 expected
export OPENROUTER_API_KEY=...               # read from the environment only
./gradlew run --args="run --dry-run --cap-eur 0.5"             # the published dry run used this cap
./gradlew run --args="analyze --run data/runs/dry-run"        # sanity check, not published
./gradlew run --args="estimate --from-run data/runs/dry-run"  # estimate recalibrated on real token counts

# 4. Full run: 6000 calls, resumable
./gradlew run --args="run --cap-eur 5"      # the published full run used this cap; config allows up to 10
./gradlew run --args="analyze"              # writes docs/data/results.json + items.json

# 5. Manual review (see below), then re-run analyze
```

`run` is **resumable**: every call is appended to `data/runs/<id>/calls.jsonl` as soon as it finishes. If it stops (cap, network, Ctrl-C), start the same command again; finished calls are never repeated or paid twice, failed ones are retried. A run directory is bound to its configuration, sample and prompts by a fingerprint; changing any of them requires a new `--run-id`, results are never overwritten.

The pages are static files in `docs/`. They fetch JSON, so opening them as `file://` or as a file on github.com shows the source and no charts. Locally: `cd docs && python3 -m http.server`, then open <http://localhost:8000>. On GitHub, set Pages to the `main` branch and the `/docs` folder. `docs/.nojekyll` is there so Jekyll does not touch the JSON.

## Method

### Data and sampling

SQuAD 2.0 **dev set** (`dev-v2.0.json`, 35 articles, 1,204 paragraphs, 5,928 answerable and 5,945 unanswerable questions), downloaded from <https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v2.0.json>, pinned by SHA-256 `80a5225e94905956a6446d296ca1093975c4d3b3260f1d6c8f68bc2ab77182d8`. CC BY-SA 4.0; see [Attribution](#attribution-and-licenses).

Sample: **100 answerable + 100 unanswerable** questions, **seed 2026**, algorithm `paired-paragraph-v1`:

1. Keep paragraphs in file order. A paragraph is eligible if it has at least one answerable and one unanswerable question (all 1,204 do).
2. Shuffle the eligible paragraphs with a Fisher-Yates shuffle driven by `java.util.Random(2026)`. `java.util.Random` is a specified LCG, so the result is identical on every JVM.
3. For the first 100 paragraphs draw one answerable and one unanswerable question (each from its own Fisher-Yates shuffle of that paragraph's questions, same random stream).

So every unanswerable question has an answerable twin on the **same passage**, and no passage appears twice. Unanswerable SQuAD questions were written by crowdworkers to look answerable from that passage, which is exactly the situation of interest. `sample --check` verifies the committed `data/sample.json` byte for byte.

The dry run uses the first 5 pairs of the sample (so also 5 + 5 on shared passages).

### Models

The same five as part 1, resolved from OpenRouter's public model list (`GET https://openrouter.ai/api/v1/models`, retrieved 2026-10-27, committed in `config/openrouter-models.snapshot.json`):

| Alias | OpenRouter id | Canonical slug | USD / M tokens in / out | `temperature` | Reasoning setting sent |
|---|---|---|---|---|---|
| grok-4.3 | `x-ai/grok-4.3` | `x-ai/grok-4.3-20260430` | 1.25 / 2.50 | sent | `effort: none`, hidden |
| gpt-5.4-mini | `openai/gpt-5.4-mini` | `openai/gpt-5.4-mini-20260317` | 0.75 / 4.50 | **not supported, omitted** | `effort: none`, hidden |
| claude-haiku-4.5 | `anthropic/claude-haiku-4.5` | `anthropic/claude-4.5-haiku-20251001` | 1.00 / 5.00 | sent | none (extended thinking off) |
| gemini-2.5-flash | `google/gemini-2.5-flash` | `google/gemini-2.5-flash` | 0.30 / 2.50 | sent | `effort: none`, hidden |
| llama-3.3-70b | `meta-llama/llama-3.3-70b-instruct` | `meta-llama/llama-3.3-70b-instruct` | 0.10 / 0.32 | sent | not applicable |

Notes:

- Ids are floating aliases on OpenRouter. The manifest records the id, the canonical slug at run time and, per call, the provider and the model name OpenRouter reports, so a later change is visible.
- A test asserts that `sendTemperature` in the config equals OpenRouter's `supported_parameters` for every model.
- "Hidden" means `reasoning.exclude = true`: reasoning tokens are still produced and billed but not returned. Reasoning effort is set to `none` for the three models that reason by default, the same setting as part 1, to keep cost predictable and the series comparable; this is a deviation from "provider defaults" and is recorded in every manifest.
- Provider routing is left to OpenRouter (no pinning); the serving provider is stored with each call. In the published run, Llama 3.3 70B was a mixture of eight hosts (DeepInfra 381, AkashML 360, Novita 224, Parasail 134, Cloudflare 45, SambaNova 26, Groq 17, CoreWeave 13). Grok 4.3 (xAI) and Gemini 2.5 Flash (Google AI Studio) were each one host. GPT-5.4 Mini was OpenAI for 1,198 calls and Azure for 2. Claude Haiku 4.5 was Amazon Bedrock for 1,199 calls and Azure for 1. The result pages list this from the call log. A Llama bar is that mixture, not one deployment.

### Prompts

Both prompts are one user message and ask for the same fixed output format. The only intended difference is the explicit abstention instruction. Verbatim templates (`src/main/kotlin/io/github/leohelfferich/abstain/prompt/Prompts.kt`; SHA-256 of both is stored in every manifest):

**neutral**

```text
Answer the question using the context.

Context:
{context}

Question: {question}

Reply with exactly one line in this format, with a short answer (a few words, copied from the context where possible):
ANSWER: <your answer>
```

**strict**

```text
Answer the question using only the context. If the context does not contain the answer, reply exactly NOT_IN_CONTEXT.

Context:
{context}

Question: {question}

Reply with exactly one line, in one of these two formats. When you answer, give a short answer (a few words, copied from the context where possible):
ANSWER: <your answer>
NOT_IN_CONTEXT
```

### Detecting abstentions without a judge

`ResponseParser` classifies every raw reply deterministically (the raw reply is stored, so a parser change never requires new calls):

1. First line is exactly `NOT_IN_CONTEXT` (ignoring surrounding backticks, quotes, asterisks, a final period), or `ANSWER: NOT_IN_CONTEXT`: **abstention (sentinel)**.
2. Otherwise the payload after `ANSWER:` is the answer. If the payload is short (at most 25 words) and matches a small refusal lexicon ("not mentioned", "does not say", "no information", "cannot be determined", "I don't know", "N/A", ...): **abstention (phrase)**. This is what lets the neutral prompt, which never mentions the sentinel, be scored at all.
3. Longer replies that mix an answer with refusal wording stay answers but are flagged `ambiguous` and listed for manual review.
4. Empty replies (for example a reasoning model that ran out of tokens) are `EMPTY`: neither invention nor refusal, reported separately.
5. Replies without the `ANSWER:` prefix or with extra lines are counted as `formatViolations` but still classified.

Sentinel and phrase abstentions are reported separately in the result files. The lexicon is the one place where a judgement call is baked in; it is unit-tested and the item explorer shows every raw reply so you can audit it.

### Call parameters

- Temperature: **0**, sent to all models except GPT-5.4 Mini (which does not support it), matching part 1 (`llm-judges-and-trust`, `config/experiment.json`). Repeats therefore measure provider nondeterminism, not sampling noise.
- `max_tokens`: 1024 (includes reasoning tokens).
- 3 repetitions of every item x model x prompt, each a separate request. No seed is sent (it would make repetitions identical where supported and is not supported everywhere).
- Single-turn, no system prompt, no tools, no streaming. OpenRouter usage accounting is on, so the real cost of each call is stored.

### Metrics (per model x prompt, with 95% intervals)

Rates are computed per item first (mean over its repetitions) and then averaged over items. Intervals are percentile bootstrap intervals over items (2,000 resamples, fixed seed), because the three repetitions of an item are not independent.

| Metric | Definition |
|---|---|
| **Invented-answer rate** | Share of replies to *unanswerable* questions that are an answer (not an abstention, not empty). |
| **Over-refusal rate** | Share of replies to *answerable* questions that are an abstention. |
| **Exact match / F1** | Official SQuAD normalization (lowercase, strip ASCII punctuation, drop a/an/the, collapse whitespace), maximum over gold answers, on *answerable* questions. A refusal or empty reply scores 0. `EM if answered` conditions on the model having answered. |
| **Self-consistency** | Across the 3 repetitions of an item: *decision agreement* = mean pairwise agreement of answer / abstain / empty; *items with identical decision* = share where all reps agree; *answer agreement* = share of pairs of answers (both answered) that are equal after normalization. Reported overall and per item kind. |
| **Strict vs neutral** | Per model, strict minus neutral, **paired by item**: change in invention rate, over-refusal rate, EM and F1, each with a bootstrap interval; *inventions avoided per extra refusal* = invention drop divided by over-refusal rise (both sets have the same size, so this is a count ratio). The ratio has no interval of its own, and the pages show it only when both intervals exclude zero. In the published run that is Grok 4.3 only; a rise of a few points makes the ratio swing, so quote the percentage-point changes. A `pattern` label (`trade-off`, `invention-down-without-clear-over-refusal`, `over-refusal-up-without-clear-invention-drop`, `no-clear-effect`) is decided by whether the interval of each difference excludes 0. |
| Extras | Abstention balanced accuracy = mean of (1 - invention) and (1 - over-refusal); share of invented answers that match SQuAD's own `plausible_answers` (F1 >= 0.5), i.e. the model was lured by the annotators' decoy; empty, format-violation and ambiguous counts; cost and mean completion tokens. |

### Manual review and disputed items

SQuAD 2.0 contains annotation errors, so the experiment has a built-in review loop:

1. `analyze` writes `data/runs/<id>/review-candidates.json`: unanswerable items where at least 3 of 5 models answered under the strict prompt (in at least 2 of 3 reps), answerable items that at least 3 models refused under the strict prompt, answerable items whose mean neutral-prompt F1 is below 0.2, and replies the abstention rule could not decide. These are *candidates only*.
2. Inspect them in `docs/items.html` (filters: "Invented even under strict prompt", "Refused under strict prompt", "Decision changed between reps", "Marked disputed").
3. Copy the ids you confirm into `data/disputed.json` with a reason and re-run `analyze`.

`results.json` always contains two scopes computed from the same raw calls: `all` and `excludingDisputed`. Both are shown on the pages (selector at the top). The default is `all`. Nothing is ever deleted from the raw data.

The published `data/disputed.json` excludes two unanswerable items, both label errors, checked against the passage: a one-character typo (UGSS for USGS), and a question whose answer (Japan) is stated in the sentence. Every other item on which all five models answered under the strict prompt stays in. On those, the passage does not state the answer the question actually asks. That is the SQuAD 2.0 trap, not a wrong label. The steam question about what had "superseded power" is one of them: it does not ask what the passage says, and GPT-5.4 Mini abstained.

## Cost control

- `estimate` prints expected calls and cost for the full run and the dry run. Token counts are assumed (about 3.5 characters per token of the actual rendered prompts; 12 answer tokens; plus 200 to 250 hidden reasoning tokens for the three reasoning models) until `--from-run` replaces them with observed values. Three scenarios: *expected*, *pessimistic* (3x output, 1.2x input) and *ceiling* (every call uses all 1,024 output tokens; only a bound).
- Prices come from the OpenRouter public list (committed snapshot, USD per token). The conversion is `eurPerUsd = 1.00` in `config/experiment.json`, deliberately conservative as long as the euro trades above the dollar (reported EUR figures then overstate); lower it only if you have checked the current rate.
- Before any calls, with assumed token counts and the committed snapshot of 2026-10-27, `estimate` prints:

| Run | Calls | Expected | Pessimistic | Ceiling |
|---|---|---|---|---|
| Dry run | 100 | EUR 0.07 | EUR 0.17 | EUR 0.35 |
| Full run | 6,000 | EUR 4.11 | EUR 9.97 | EUR 20.52 |

- The published full run was estimated again from the dry run's token counts, then executed with `--cap-eur 5` (the dry run used `--cap-eur 0.5`). `config/experiment.json` still says 10, which is the ceiling; `--cap-eur` can only lower it, and the manifest records the cap that was in force. Calibrated estimate: expected EUR 1.40, pessimistic EUR 1.99, ceiling EUR 20.52. Spent: **EUR 1.26** for 6,000 successful calls (grok-4.3 0.40, claude-haiku-4.5 0.42, gpt-5.4-mini 0.28, gemini-2.5-flash 0.11, llama-3.3-70b 0.06). Reported euros equal reported dollars because `eurPerUsd` is 1.00.
- All 1,886 extra attempts were HTTP 429s, OpenRouter's per-minute limit on GPT-5.4 Mini (970) and Claude Haiku 4.5 (916). Each was retried. No planned call is missing. The raw errors are in `data/runs/full/failed-attempts.jsonl`.
- **Hard cap EUR 10.00 across all real runs together** (`HARD_CAP_EUR` in code; the config and `--cap-eur` can only lower it). `run` refuses to start if the expected cost plus what `data/runs/` already shows as spent does not fit under the cap minus a EUR 0.25 safety margin, and asks for confirmation (`yes`, or `--yes`) before the first paid call.
- While running, every call first *reserves* its worst case (prompt size bound + `max_tokens` at the model's price); it is only sent if spent + reserved + worst case stays within the usable budget. The runner therefore stops by itself before the cap is crossed, even with 8 parallel workers. Cost per call is the figure OpenRouter reports (`usage.cost`), falling back to token counts times price. Calls run repetition by repetition, so a stop leaves complete repetition sets. The margin covers requests that time out client-side yet get billed.
- 401, 402 and 403 responses abort the run immediately; 20 consecutive failures abort it too. 429 and 5xx responses are retried with backoff inside the client.

## API key handling

The key is read **only** from the `OPENROUTER_API_KEY` environment variable. It is held in one private field of `OpenRouterClient`, whose `toString` is redacted; error messages from the API are scrubbed of the key and of anything shaped like `sk-or-...` before they are logged or stored; request headers, prompts and the key are never written to `calls.jsonl` or the manifest. Tests cover this (a local HTTP server echoes the key back in an error body and the test checks it is redacted; a hygiene test scans the repository for key-shaped strings). `.env` and `*.key` are git-ignored.

## Repository layout

```text
build.gradle.kts, settings.gradle.kts, gradlew      Kotlin 2.4 / JDK 21 / Gradle 9.8 (wrapper, checksum pinned)
config/experiment.json                              seed, reps, temperature, cap, models and per-model settings
config/openrouter-models.snapshot.json              resolved ids, canonical slugs, prices, supported parameters
data/sample.json                                    the 200 sampled items (derived from SQuAD 2.0, CC BY-SA 4.0)
data/disputed.json                                  ids excluded in the "excludingDisputed" scope
data/runs/<id>/{manifest.json,calls.jsonl}          raw results of each run (committed as data)
docs/                                               static pages (index, trade-off, items, method) + docs/data/*.json
src/main/kotlin/.../                                Model, Prompts, ResponseParser, SquadMetrics, Squad(Sampler), Client (OpenRouter),
                                                    Pricing, Cost, Runner, Analyzer, Simulator, Main
src/test/kotlin/.../                                92 tests; fake/mock clients and a local HTTP server, no network, no key. One sampler test skips until `fetch-data` has downloaded SQuAD.
```

The run manifest (`data/runs/<id>/manifest.json`) records: run id and kind, mock flag, status and stop reason, start and end time, git commit and dirty flag, JDK, a fingerprint of config + items + prompts + prices, SHA-256 of the config file, sample and source dataset, seed, repetitions, temperature, max tokens, prompt template hashes and versions, per-model id / canonical slug / prices / temperature sent / reasoning settings, planned, ok and failed call counts, money spent, the cap and the pre-run estimate.

## Limitations and deviations

- Temperature (0) and reasoning effort (`none`) were aligned with part 1 after the repository was built. Part 1 pinned Llama 3.3 70B to the DeepInfra route. This run did not, so the Llama number is the eight-host mixture listed under Models, not a DeepInfra result. The provider of every call is in `calls.jsonl` and on the method page.
- Reasoning effort is `none` for Grok 4.3, GPT-5.4 Mini and Gemini 2.5 Flash, as in part 1. A model that reasons by default and is told not to may behave differently from its default setting.
- Abstention in the neutral prompt is detected by a wording rule, not by the sentinel, so neutral-prompt refusals are somewhat less certain than strict-prompt refusals. Sentinel and phrase abstentions are reported separately for that reason.
- 100 + 100 items give intervals of a few percentage points, wide enough that small model differences are not significant. Only items, not repetitions, are resampled.
- One dataset, one passage format, short extractive answers. Results say nothing about long documents or retrieval pipelines.
- SQuAD annotations are imperfect; use the manual review loop and report both scopes.

Follow-up experiment (context vs. world knowledge): [`follow-up-wissenskonflikt/`](follow-up-wissenskonflikt/).

## Attribution and licenses

- Code, configuration, manifests, model replies and computed metrics: **MIT** (see [LICENSE](LICENSE)).
- Questions, passages and gold answers: **SQuAD 2.0**, Pranav Rajpurkar, Robin Jia and Percy Liang, *Know What You Don't Know: Unanswerable Questions for SQuAD*, ACL 2018, <https://rajpurkar.github.io/SQuAD-explorer/>, licensed **CC BY-SA 4.0**. `data/sample.json` and the files under `docs/data/` that quote SQuAD text remain under CC BY-SA 4.0. Details in [`data/README.md`](data/README.md).
