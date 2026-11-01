// Shared helpers. Everything on these pages is rendered from the real result files in docs/data/.
const SERIES = { neutral: "#6b7a99", strict: "#c2410c" };
// One hue per model, used where neutral/strict already encode the series (the trade-off scatter).
const MODEL_COLOR = {
  "grok-4.3": "#1d4e89",
  "gpt-5.4-mini": "#b45309",
  "claude-haiku-4.5": "#0f766e",
  "gemini-2.5-flash": "#6d28d9",
  "llama-3.3-70b": "#9f1239",
};
const PAGES = [
  ["index.html", "Results"],
  ["tradeoff.html", "Trade-off"],
  ["items.html", "Items"],
  ["method.html", "Method"],
];

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (v !== false && v !== null && v !== undefined) node.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return node;
}

function svgEl(tag, attrs = {}, ...children) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  for (const c of children.flat()) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return node;
}

function chrome(current) {
  const header = el("header", { class: "site" }, el("div", { class: "wrap" },
    el("strong", {}, "Does the model know it doesn't know?"),
    el("nav", {}, PAGES.map(([href, label]) => el("a", { href, "aria-current": href === current ? "page" : false }, label)))));
  document.body.prepend(header);
  document.body.append(el("footer", {}, el("div", { class: "wrap" },
    "Questions and passages from SQuAD 2.0 (Rajpurkar, Jia & Liang, 2018), licensed CC BY-SA 4.0. ",
    el("a", { href: "https://rajpurkar.github.io/SQuAD-explorer/" }, "rajpurkar.github.io/SQuAD-explorer"),
    ". Code: MIT.")));
}

async function loadJson(path) {
  const response = await fetch(path, { cache: "no-cache" });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return response.json();
}

async function loadResults(main) {
  try {
    return await loadJson("data/results.json");
  } catch (e) {
    main.append(el("div", { class: "banner" },
      el("b", {}, "No results published yet. "),
      "This page renders docs/data/results.json, which is produced by ",
      el("code", {}, "./gradlew run --args=\"analyze\""),
      " after the full run has been committed. (", String(e.message), ")"));
    return null;
  }
}

const pct = (v, d = 1) => (v === null || v === undefined ? "n/a" : (v * 100).toFixed(d) + "%");
const pp = (v, d = 1) => (v === null || v === undefined ? "n/a" : (v >= 0 ? "+" : "") + (v * 100).toFixed(d) + " pp");
const ci = (i) => (i && i.lo !== null && i.hi !== null ? `${pct(i.lo, 0)}–${pct(i.hi, 0)}` : "");

function scopeFromQuery(results) {
  const wanted = new URLSearchParams(location.search).get("scope");
  return results.scopes.find((s) => s.name === wanted) || results.scopes[0];
}

function scopeControl(results, scope, onChange) {
  const select = el("select", { id: "scope", "aria-label": "Item set" },
    results.scopes.map((s) => el("option", { value: s.name, selected: s.name === scope.name },
      s.name === "all" ? "All sampled items" : `Without disputed items (${s.excludedItems} excluded)`)));
  select.addEventListener("change", () => {
    const url = new URL(location.href);
    url.searchParams.set("scope", select.value);
    history.replaceState(null, "", url);
    onChange(results.scopes.find((s) => s.name === select.value));
  });
  return el("div", { class: "controls" }, el("label", { for: "scope" }, "Item set: "), select);
}

function runBanner(results) {
  const r = results.run;
  const banners = [];
  if (r.mock) banners.push(el("div", { class: "banner error" }, el("b", {}, "MOCK DATA. "), "These numbers are synthetic and must not be published."));
  if (r.status !== "complete") banners.push(el("div", { class: "banner" }, el("b", {}, "Partial run. "),
    `${r.okCalls} of ${r.plannedCalls} calls finished (status: ${r.status}).`));
  return banners;
}

/**
 * Grouped bar chart: one group per model, neutral vs strict bars, optional CI whiskers.
 * The title is not drawn here; the caller puts it in HTML so a long title can wrap
 * instead of sitting on the 100% labels.
 */
function barChart({ title, groups, valueOf, ciOf, width = 560, height = 268 }) {
  const m = { top: 16, right: 8, bottom: 46, left: 36 };
  const w = width - m.left - m.right, h = height - m.top - m.bottom;
  const svg = svgEl("svg", { viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": title, width: "100%" });
  for (let t = 0; t <= 1.0001; t += 0.25) {
    const y = m.top + h - t * h;
    svg.append(svgEl("line", { x1: m.left, x2: m.left + w, y1: y, y2: y, class: "grid" }));
    svg.append(svgEl("text", { x: m.left - 6, y: y + 3, "text-anchor": "end", "font-size": 10 }, Math.round(t * 100) + "%"));
  }
  const gw = w / groups.length, bw = Math.min(22, gw / 3.4);
  const pendingLabels = [];
  groups.forEach((g, gi) => {
    const cx = m.left + gi * gw + gw / 2;
    ["neutral", "strict"].forEach((p, pi) => {
      const v = valueOf(g, p);
      if (v === null || v === undefined) return;
      const x = cx + (pi === 0 ? -bw - 2 : 2);
      const barTop = m.top + h - v * h;
      const bar = svgEl("rect", { x, y: barTop, width: bw, height: Math.max(0, v * h), fill: SERIES[p] });
      bar.append(svgEl("title", {}, `${g.label} (${p}): ${pct(v)}`));
      svg.append(bar);
      const range = ciOf ? ciOf(g, p) : null;
      const mid = x + bw / 2;
      if (range && range.lo !== null && range.hi !== null) {
        svg.append(svgEl("line", {
          x1: mid, x2: mid,
          y1: m.top + h - range.lo * h, y2: m.top + h - range.hi * h,
          stroke: "#222", "stroke-width": 1.2,
        }));
        svg.append(svgEl("line", {
          x1: mid - 3, x2: mid + 3, y1: m.top + h - range.hi * h, y2: m.top + h - range.hi * h, stroke: "#222", "stroke-width": 1.2,
        }));
        svg.append(svgEl("line", {
          x1: mid - 3, x2: mid + 3, y1: m.top + h - range.lo * h, y2: m.top + h - range.lo * h, stroke: "#222", "stroke-width": 1.2,
        }));
      }
      // Sit the number above the whisker. High bars would otherwise print it through the interval line.
      const above = range && range.hi !== null ? range.hi : v;
      pendingLabels.push({ x: mid, y: m.top + h - above * h - 4, text: String(Math.round(v * 100)) });
    });
    // Aliases are one long token. Split at the first hyphen so each group keeps its own two lines.
    const hyphen = g.label.indexOf("-");
    const lines = hyphen < 0 ? [g.label] : [g.label.slice(0, hyphen), g.label.slice(hyphen + 1)];
    const label = svgEl("text", { x: cx, y: m.top + h + 14, "text-anchor": "middle", "font-size": 11 });
    lines.forEach((part, i) => label.append(svgEl("tspan", { x: cx, dy: i === 0 ? 0 : 13 }, part)));
    svg.append(label);
  });
  // Two bars in one group can claim the same pixel row. Move the lower label down until the glyphs clear.
  pendingLabels.sort((a, b) => a.y - b.y || a.x - b.x);
  for (let i = 1; i < pendingLabels.length; i++) {
    const prev = pendingLabels[i - 1];
    const cur = pendingLabels[i];
    if (Math.abs(cur.x - prev.x) < 18 && cur.y < prev.y + 11) cur.y = prev.y + 11;
  }
  pendingLabels.forEach((l) => {
    svg.append(svgEl("text", {
      x: l.x, y: Math.max(11, l.y), "text-anchor": "middle", "font-size": 9,
      stroke: "#fff", "stroke-width": 3, "paint-order": "stroke",
    }, l.text));
  });
  return svg;
}

/**
 * Pack callout labels into a gutter. Each label prefers its anchor's height, then steps
 * down until it clears the label above. Returns positions in anchor order.
 */
function layoutGutterLabels(anchors, box) {
  const line = 22;
  const placed = anchors.map((a) => ({ ...a, x: box.left, y: a.ay }));
  const order = placed.map((_, i) => i).sort((a, b) => placed[a].y - placed[b].y || a - b);
  for (let n = 1; n < order.length; n++) {
    const prev = placed[order[n - 1]];
    const cur = placed[order[n]];
    if (cur.y < prev.y + line) cur.y = prev.y + line;
  }
  const lowest = order.length ? placed[order[order.length - 1]].y : box.top;
  const overflow = lowest - (box.bottom - 2);
  if (overflow > 0) placed.forEach((l) => { l.y -= overflow; });
  if (placed.some((l) => l.y < box.top + 11)) {
    order.forEach((index, n) => { placed[index].y = box.top + 12 + n * line; });
  }
  return placed;
}

function legend(whiskers = true) {
  return el("div", { class: "legend" },
    el("span", {}, el("i", { style: `background:${SERIES.neutral}` }), "neutral prompt"),
    el("span", {}, el("i", { style: `background:${SERIES.strict}` }), "strict prompt"),
    whiskers ? el("span", {}, "whiskers: 95% bootstrap interval over items") : "");
}

function cellOf(scope, model, prompt) {
  return scope.cells.find((c) => c.model === model && c.prompt === prompt);
}
