"""Knowledge-conflict follow-up to llm-knows-it-doesnt-know.
Same 5 models, same two prompt templates, same request settings (temperature 0, max_tokens 1024,
reasoning effort none where configured), 3 reps. Context contradicts world knowledge (invented actor).
API key read from OPENROUTER_API_KEY; never printed or stored."""
import json, os, re, sys, time, concurrent.futures as cf, urllib.request, datetime
HERE = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(HERE, "..", "config", "experiment.json")))
ITEMS = json.load(open(os.path.join(HERE, os.environ.get("ITEMS","items.json"))))
KEY = os.environ["OPENROUTER_API_KEY"].strip()
NEUTRAL = """Answer the question using the context.

Context:
{context}

Question: {question}

Reply with exactly one line in this format, with a short answer (a few words, copied from the context where possible):
ANSWER: <your answer>"""
STRICT = """Answer the question using only the context. If the context does not contain the answer, reply exactly NOT_IN_CONTEXT.

Context:
{context}

Question: {question}

Reply with exactly one line, in one of these two formats. When you answer, give a short answer (a few words, copied from the context where possible):
ANSWER: <your answer>
NOT_IN_CONTEXT"""
TEMPL = {"neutral": NEUTRAL, "strict": STRICT}
REPS = CFG["reps"]

def call(model, prompt):
    body = {"model": model["openRouterId"], "messages": [{"role": "user", "content": prompt}],
            "max_tokens": CFG["maxTokens"], "usage": {"include": True}}
    if model["sendTemperature"]: body["temperature"] = CFG["temperature"]
    if model["reasoning"]: body["reasoning"] = model["reasoning"]
    for attempt in range(4):
        try:
            req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=json.dumps(body).encode(),
                headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=120) as r:
                d = json.load(r)
            if "choices" not in d: raise RuntimeError("no choices: " + str(d.get("error"))[:200])
            return d
        except Exception as e:
            err = str(e).replace(KEY, "[REDACTED]")
            if attempt == 3: return {"error": err}
            time.sleep(2 * (attempt + 1))

def task(args):
    item, model, variant, rep = args
    prompt = TEMPL[variant].replace("{context}", item["context"]).replace("{question}", item["question"])
    t = datetime.datetime.now(datetime.timezone.utc).isoformat()
    d = call(model, prompt)
    if "error" in d:
        return dict(itemId=item["id"], model=model["alias"], prompt=variant, rep=rep, status="error", error=d["error"], requestedAt=t)
    u = d.get("usage", {})
    return dict(itemId=item["id"], model=model["alias"], openRouterId=model["openRouterId"], prompt=variant, rep=rep,
                status="ok", requestedAt=t, content=d["choices"][0]["message"].get("content"),
                finishReason=d["choices"][0].get("finish_reason"), promptTokens=u.get("prompt_tokens"),
                completionTokens=u.get("completion_tokens"), costUsd=u.get("cost"), provider=d.get("provider"),
                responseModel=d.get("model"))

tasks = [(i, m, v, r) for i in ITEMS for m in CFG["models"] for v in ("neutral", "strict") for r in range(1, REPS + 1)]
if len(sys.argv) > 1 and sys.argv[1] == "dry": tasks = tasks[:2]
out = os.path.join(HERE, "results", os.environ.get("OUT","calls.jsonl")); os.makedirs(os.path.dirname(out), exist_ok=True)
with cf.ThreadPoolExecutor(8) as ex, open(out, "w") as f:
    for rec in ex.map(task, tasks):
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
print("done", len(tasks))
