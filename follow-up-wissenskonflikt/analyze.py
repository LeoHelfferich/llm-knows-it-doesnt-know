"""Classifies replies: context (invented actor from the text), world (real actor from training),
both, abstain (NOT_IN_CONTEXT or refusal wording), role_name (answered with the character, not an actor), other."""
import json, re, collections, os
HERE=os.path.dirname(os.path.abspath(__file__))
REFUSAL=re.compile(r"NOT_IN_CONTEXT|not (explicitly |directly |clearly )?(mentioned|stated|specified|provided|given|named)|does(n't| not) (explicitly )?(say|mention|specify|state|name)|no information|cannot|unable to",re.I)
ROLE={"saving-private-ryan":["james"],"titanic":["jack dawson","penniless artist","kate winslet"],"dark-knight":["joker"],"forrest-gump":["forrest gump"],"pirates-caribbean":["sparrow"]}
def cls(r,it):
    c=(r.get("content") or "")
    lc=c.lower()
    ctx=any(k in lc for k in it.get("contextKeys",[])) if it.get("condition")!="gap" else False
    wld=any(k in lc for k in it["worldKeys"])
    if ctx and wld: return "both"
    if ctx: return "context"
    if wld: return "world"
    if REFUSAL.search(c): return "abstain"
    base=it["id"].replace("--gap","")
    if any(k in lc for k in ROLE[base]): return "role_name"
    return "other"
def load(items_file, calls_file):
    items={i["id"]:i for i in json.load(open(os.path.join(HERE,items_file)))}
    recs=[json.loads(l) for l in open(os.path.join(HERE,"results",calls_file))]
    for r in recs: r["class"]=cls(r,items[r["itemId"]])
    return recs
def table(recs):
    s={"calls":len(recs),"errors":sum(r["status"]!="ok" for r in recs),"costUsd":round(sum(r.get("costUsd") or 0 for r in recs),6)}
    for v in ["neutral","strict"]:
        s[v]={"total":dict(collections.Counter(r["class"] for r in recs if r["prompt"]==v))}
        for key in ["model","itemId"]:
            t=collections.defaultdict(collections.Counter)
            for r in recs:
                if r["prompt"]==v: t[r[key]][r["class"]]+=1
            s[v]["by_"+key]={k:dict(c) for k,c in t.items()}
    return s
out={"conflict":table(load("items.json","calls.jsonl")),"gap":table(load("items-gap.json","calls-gap.jsonl"))}
out["totalCostUsd"]=round(out["conflict"]["costUsd"]+out["gap"]["costUsd"],6)
json.dump(out,open(os.path.join(HERE,"results","summary.json"),"w"),indent=1)
print(json.dumps(out,indent=1))
