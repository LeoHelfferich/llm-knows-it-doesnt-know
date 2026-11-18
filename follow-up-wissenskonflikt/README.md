# Follow-up: context vs. world knowledge (Wissenskonflikt)

Follow-up experiment: context vs. world knowledge. In the main experiment, models answered "Matt Damon" to the question about *Saving Private Ryan* although the given context (a Wikipedia paragraph about Harvard) does not mention the film. This test gives the models a context that **contradicts** their world knowledge, for example a different cast, and measures which one wins.

## Setup

- Same five models, same two prompt templates (neutral and strict) and same request settings as the main experiment, read from `../config/experiment.json` (temperature 0 where supported, max_tokens 1024, reasoning effort "none" where configured). All calls go through OpenRouter.
- Five well-known films. Each context is a short, invented but plausible paragraph that names a **fictional actor** for a famous role (`items.json`):

| Film | Role | Real actor | Actor in the context |
|---|---|---|---|
| Saving Private Ryan | Private James Francis Ryan | Matt Damon | Daniel Ashcombe |
| Titanic | Jack Dawson | Leonardo DiCaprio | Ethan Calloway |
| The Dark Knight | The Joker | Heath Ledger | Marcus Hale |
| Forrest Gump | Forrest Gump | Tom Hanks | Robert Lindqvist |
| Pirates of the Caribbean: The Curse of the Black Pearl | Captain Jack Sparrow | Johnny Depp | Oliver Brandt |

- Control (`items-gap.json`): the same paragraphs with the actor sentence removed, so the text says nothing about who played the role.
- 5 items x 5 models x 2 prompts x 3 repetitions = 150 calls per condition.

## Results (run of 2026-11-18)

Conflict condition, 150 calls, 0 errors: **150 of 150 answers named the fictional actor from the context.** Every model 30 of 30, both prompts 75 of 75. No answer used the real actor, none refused.

Control condition (context silent on the role), 150 calls, 0 errors:

| Prompt | Real actor (from training) | Abstained | Answered with the character name | 
|---|---|---|---|
| neutral | 14 of 75 (GPT-5.4 Mini 12, Grok 4.3 2) | 32 of 75 | 29 of 75 |
| strict | 0 of 75 | 66 of 75 | 9 of 75 |

For *Saving Private Ryan* in the control, "Matt Damon" came 3 of 30 times (all GPT-5.4 Mini, neutral prompt). In the main experiment with the Harvard paragraph it was 24 of 30. Note that the Harvard paragraph contains the name Matt Damon in a list of alumni; whether that drives the difference was not tested.

Full counts per model and item: `results/summary.json`. Raw replies: `results/calls.jsonl` (conflict) and `results/calls-gap.jsonl` (control).

Cost as reported by OpenRouter: 0.027 USD (conflict) + 0.026 USD (control) = 0.054 USD.

## Limitations

- Five items per condition. Small, not a benchmark.
- Each conflict context states the fictional actor directly and repeats the surname. Subtler conflicts (outdated documents, partial contradictions, several sources) were not tested.
- Classification is a simple name match plus a refusal pattern list (`analyze.py`), no LLM judge. "Character name" means the model answered e.g. "Captain Jack Sparrow" instead of an actor.
- Questions and contexts are in English, like the main experiment.

## Reproduce

Requires Python 3 (standard library only) and `OPENROUTER_API_KEY` in the environment.

```bash
python3 run.py                                         # conflict -> results/calls.jsonl
ITEMS=items-gap.json OUT=calls-gap.jsonl python3 run.py # control  -> results/calls-gap.jsonl
python3 analyze.py                                     # -> results/summary.json
```

Re-running overwrites the result files. Provider routing on OpenRouter can differ between runs.
