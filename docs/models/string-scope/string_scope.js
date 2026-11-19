(() => {
  const S = window.Strings;
  const $ = (id) => document.getElementById(id);
  const POLS = [["x1", "x¹"], ["x2", "x²"], ["y", "y (compact)"]];
  const bank = (n, q, pol) => ({ n, q, pol });

  const PRESETS = {
    "Graviton g_μν": { mode: "closed", left: bank(1, 1, "x1"), right: bank(1, 1, "x1"), k: 0, w: 0, R: 1.5 },
    "Graviton + Kalb–Ramond B_μν": { mode: "closed", left: bank(1, 1, "x1"), right: bank(1, 1, "x2"), k: 0, w: 0, R: 1.5 },
    "Radion σ": { mode: "closed", left: bank(1, 1, "y"), right: bank(1, 1, "y"), k: 0, w: 0, R: 1.5 },
    "Graviphoton A_μ": { mode: "closed", left: bank(1, 1, "x1"), right: bank(1, 1, "y"), k: 0, w: 0, R: 1.5 },
    "Closed tachyon T": { mode: "closed", left: bank(1, 0, "x1"), right: bank(1, 0, "x1"), k: 0, w: 0, R: 1.5 },
    "Kaluza–Klein graviton (k = 1)": { mode: "closed", left: bank(1, 1, "x1"), right: bank(1, 1, "x1"), k: 1, w: 0, R: 1.5 },
    "Winding graviton (w = 1)": { mode: "closed", left: bank(1, 1, "x1"), right: bank(1, 1, "x2"), k: 0, w: 1, R: 1.5 },
    "W± at self-dual radius": { mode: "closed", left: bank(1, 1, "x1"), right: bank(1, 0, "x1"), k: 1, w: 1, R: 1 },
    "Massive level (2, 2)": { mode: "closed", left: bank(2, 1, "x1"), right: bank(1, 2, "x2"), k: 0, w: 0, R: 1.5 },
    "Open: photon γ": { mode: "open", left: bank(1, 1, "x1"), L: 0, R: 1.5 },
    "Open: W± boson (stretched)": { mode: "open", left: bank(1, 1, "x1"), L: 4, R: 1.5 },
    "Open: brane-position scalar φ": { mode: "open", left: bank(1, 1, "y"), L: 0, R: 1.5 },
    "Open: tachyon T": { mode: "open", left: bank(1, 0, "x1"), L: 0, R: 1.5 },
  };

  let cfg = { mode: "closed", left: bank(1, 1, "x1"), right: bank(1, 1, "x1"), k: 0, w: 0, R: 1.5, L: 0 };
  let tau = 0, playing = true, lastT = performance.now(), dirty = true;

  // ---------- controls ----------
  function bankHTML(id, title) {
    const opts = POLS.map(([v, t]) => `<option value="${v}">${t}</option>`).join("");
    return `<div class="bank" id="${id}"><h2 style="margin:.3rem 0">${title}</h2>
      <div class="ctl"><label>Mode number n <b id="${id}-n-v"></b></label><input type="range" id="${id}-n" min="1" max="4" step="1"></div>
      <div class="ctl"><label>Quanta q <b id="${id}-q-v"></b></label><input type="range" id="${id}-q" min="0" max="3" step="1"></div>
      <div class="ctl row"><label style="flex:1">Polarization</label><select id="${id}-pol">${opts}</select></div></div>`;
  }
  $("banks").innerHTML = bankHTML("left", "Left movers α") + bankHTML("right", "Right movers α̃");

  Object.keys(PRESETS).forEach((k) => $("preset").insertAdjacentHTML("beforeend", `<option>${k}</option>`));
  $("preset").insertAdjacentHTML("afterbegin", `<option value="" disabled>(choose a state)</option>`);

  function syncControls() {
    for (const id of ["R", "k", "w", "L"]) $(id).value = cfg[id];
    for (const b of ["left", "right"]) {
      $(`${b}-n`).value = cfg[b].n; $(`${b}-q`).value = cfg[b].q; $(`${b}-pol`).value = cfg[b].pol;
    }
    const open = cfg.mode === "open";
    $("m-closed").classList.toggle("on", !open); $("m-open").classList.toggle("on", open);
    document.querySelectorAll(".closed-only").forEach((e) => (e.style.display = open ? "none" : ""));
    document.querySelectorAll(".open-only").forEach((e) => (e.style.display = open ? "" : "none"));
    $("right").style.display = open ? "none" : "";
    $("left").querySelector("h2").textContent = open ? "Oscillator α (ends tie left and right movers together)" : "Left movers α";
    $("R-v").textContent = Number(cfg.R.toFixed(2)) + (Math.abs(cfg.R - 1) < 1e-9 ? " (self-dual)" : "");
    $("k-v").textContent = cfg.k; $("w-v").textContent = cfg.w; $("L-v").textContent = cfg.L.toFixed(1);
    for (const b of ["left", "right"]) { $(`${b}-n-v`).textContent = cfg[b].n; $(`${b}-q-v`).textContent = cfg[b].q; }
    $("spd-v").textContent = Number($("spd").value).toFixed(1);
    dirty = true;
  }

  function readControls() {
    cfg.R = Number($("R").value); cfg.k = Number($("k").value); cfg.w = Number($("w").value); cfg.L = Number($("L").value);
    for (const b of ["left", "right"]) cfg[b] = bank(Number($(`${b}-n`).value), Number($(`${b}-q`).value), $(`${b}-pol`).value);
    $("preset").value = "";
    syncControls(); updatePanel();
  }
  document.querySelectorAll("#controls input[type=range], #controls select:not(#preset):not(#persist)").forEach((el) =>
    el.addEventListener("input", () => (el.id === "spd" ? syncControls() : readControls()))
  );
  $("preset").addEventListener("change", (e) => {
    const p = PRESETS[e.target.value];
    cfg = { ...cfg, k: 0, w: 0, L: 0, right: bank(1, 0, "x1"), ...structuredClone(p) };
    syncControls(); updatePanel(); $("preset").value = e.target.value;
  });
  $("m-closed").onclick = () => { cfg.mode = "closed"; syncControls(); updatePanel(); };
  $("m-open").onclick = () => { cfg.mode = "open"; syncControls(); updatePanel(); };
  $("tdual").onclick = () => {
    cfg = S.tDual(cfg);
    syncControls(); updatePanel();
  };
  $("play").onclick = () => { playing = !playing; $("play").textContent = playing ? "Pause" : "Play"; };

  // ---------- readouts & equations ----------
  const sub = (s) => s.replace(/_([^\s(+]+)/g, "<sub>$1</sub>");
  const f = (x, d = 2) => (Math.abs(x) < 5e-13 ? "0" : Number(x.toFixed(d)).toString());
  const sgn = (x, d = 2) => (x < 0 ? `− ${f(-x, d)}` : `+ ${f(x, d)}`);
  let prevTerms = {};

  function renderEq(lines) {
    const terms = {};
    $("eq").innerHTML = lines.map((ln) => `<div class="ln">${ln.map(([id, h]) => {
      if (!id) return h;
      terms[id] = h;
      return `<span class="t" data-id="${id}">${h}</span>`;
    }).join(" ")}</div>`).join("");
    $("eq").querySelectorAll("span.t").forEach((el) => {
      if (prevTerms[el.dataset.id] !== undefined && prevTerms[el.dataset.id] !== terms[el.dataset.id]) {
        el.classList.add("flash"); setTimeout(() => el.classList.remove("flash"), 700);
      }
    });
    prevTerms = terms;
  }

  const polSym = (p) => ({ x1: "x<sup>1</sup>", x2: "x<sup>2</sup>", y: "δy" })[p];
  const oscTerm = (b, arg, trig) =>
    b.q === 0 ? "0" : `${f(S.amplitude(b))} ${trig} ${b.n === 1 ? "" : b.n}(${arg})`;

  function equations(st) {
    const L = cfg.left, Rb = cfg.right;
    if (cfg.mode === "open") {
      const lines = [
        [["", "<i>y</i>(σ,τ) = <i>y</i><sub>A</sub> +"], ["L", `(${f(cfg.L, 1)}/π)σ`], ["", "+ δy,"], ["", '<span class="num">Dirichlet: both ends fixed on branes</span>']],
        [["", `${polSym(L.pol)}(σ,τ) ⊃`], ["osc", L.pol === "y" ? `${oscTerm(L, "τ", "cos")}·sin ${L.n === 1 ? "" : L.n}σ` : `${oscTerm(L, "τ", "cos")}·cos ${L.n === 1 ? "" : L.n}σ`],
          ["", `<span class="num">a = √(2q/n) = √(2·${L.q}/${L.n})</span>`]],
        [["", "α′M² = (<i>L</i>/2π)² + <i>N</i> − 1 ="], ["Lm", f((cfg.L / (2 * Math.PI)) ** 2, 3)], ["", "+"], ["N", `${st.N}`], ["", "− 1 ="], ["m2", `<b>${f(st.m2, 3)}</b>`]],
        [["", "<i>N</i> = <i>n</i>·<i>q</i> ="], ["Nn", `${L.n}·${L.q} = ${st.N}`]],
      ];
      return renderEq(lines);
    }
    const lm = st.N - st.Nt === cfg.k * cfg.w;
    renderEq([
      [["", "<i>y</i>(σ,τ) = <i>y</i><sub>0</sub> + α′(<i>k</i>/<i>R</i>)τ + <i>wR</i>σ + δy ="], ["kR", `${f(cfg.k / cfg.R)}τ`], ["wR", sgn(cfg.w * cfg.R) + "σ"], ["", "+ δy"]],
      [["", `${polSym(L.pol)} ⊃`], ["oL", oscTerm(L, "τ+σ", "cos")], ["", '<span class="num">(left)</span>'],
        ["", `&nbsp; ${polSym(Rb.pol)} ⊃`], ["oR", oscTerm(Rb, "τ−σ", "cos")], ["", '<span class="num">(right)</span>']],
      [["", "α′M² = <i>k</i>²/<i>R</i>² + <i>w</i>²<i>R</i>² + 2(<i>N</i> + <i>Ñ</i> − 2)"]],
      [["", "&nbsp;&nbsp;="], ["k2", f((cfg.k / cfg.R) ** 2, 3)], ["", "+"], ["w2", f((cfg.w * cfg.R) ** 2, 3)], ["", "+ 2("],
        ["N", `${st.N}`], ["", "+"], ["Nt", `${st.Nt}`], ["", "− 2) ="], ["m2", `<b>${f(st.m2, 3)}</b>`]],
      [["", "<i>N</i> = <i>n</i>·<i>q</i> ="], ["Nn", `${L.n}·${L.q}`], ["", ", <i>Ñ</i> ="], ["Ntn", `${Rb.n}·${Rb.q}`],
        ["", "; <i>N</i> − <i>Ñ</i> ="], ["lm", `${st.N - st.Nt} ${lm ? "=" : "≠"} ${cfg.k * cfg.w} <span class="${lm ? "ok" : "bad"}">${lm ? "✓" : "✗"}</span>`]],
    ]);
  }

  function updatePanel() {
    const st = S.classify(cfg);
    $("p-sym").innerHTML = sub(st.symbol);
    $("p-sym").style.fontSize = st.symbol.length > 12 ? "2rem" : "";
    $("p-sym").classList.toggle("bad", !st.physical || st.m2 < -1e-9);
    $("p-name").textContent = st.name;
    $("p-detail").textContent = st.parts && st.parts.length > 1 ? "superposition, split into:" : st.detail;
    $("p-parts").innerHTML = st.parts && st.parts.length > 1
      ? st.parts.map((p) => `<li><span>${p.name}<br><span style="color:var(--dim);font-size:.75rem">${p.detail}</span></span><span class="s">${sub(p.symbol)} · 50%</span></li>`).join("")
      : "";
    $("r-m2").textContent = f(st.m2, 3);
    $("r-m").textContent = st.m2 < -1e-9 ? `${f(Math.sqrt(-st.m2), 3)} i (imaginary)` : f(Math.sqrt(Math.max(0, st.m2)), 3);
    $("r-n").textContent = cfg.mode === "open" ? `${st.N}` : `${st.N}, ${st.Nt}`;
    const lm = S.levelMatched(cfg);
    $("r-lm").innerHTML = `<span class="${lm ? "ok" : "bad"}">${lm ? "satisfied" : "violated"}</span>`;
    equations(st);
  }

  // ---------- scope drawing ----------
  const bg = $("bg"), fg = $("fg"), tr = $("trace");
  function fit(c) {
    const r = c.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    c.width = Math.round(r.width * dpr); c.height = Math.round(r.height * dpr);
    c.getContext("2d").setTransform(dpr, 0, 0, dpr, 0, 0);
    return [r.width, r.height];
  }
  function graticule(ctx, W, H, nx, ny) {
    ctx.strokeStyle = "#13261f"; ctx.lineWidth = 1;
    for (let i = 0; i <= nx; i++) { ctx.beginPath(); ctx.moveTo((W * i) / nx, 0); ctx.lineTo((W * i) / nx, H); ctx.stroke(); }
    for (let j = 0; j <= ny; j++) { ctx.beginPath(); ctx.moveTo(0, (H * j) / ny); ctx.lineTo(W, (H * j) / ny); ctx.stroke(); }
    ctx.strokeStyle = "#1f3a33"; ctx.beginPath(); ctx.moveTo(W / 2, 0); ctx.lineTo(W / 2, H); ctx.moveTo(0, H / 2); ctx.lineTo(W, H / 2); ctx.stroke();
  }
  let geo = {};
  function drawBackground() {
    const [W, H] = fit(bg); fit(fg);
    const ctx = bg.getContext("2d");
    ctx.fillStyle = "#05080a"; ctx.fillRect(0, 0, W, H);
    graticule(ctx, W, H, 10, 8);
    const rho = 0.3 * Math.min(W, H);
    geo = { W, H, cx: W / 2, cy: H / 2, rho, ux: rho * 0.35 };
    ctx.setLineDash([5, 6]); ctx.strokeStyle = "#2f6b57"; ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.arc(geo.cx, geo.cy, rho, 0, 2 * Math.PI); ctx.stroke(); ctx.setLineDash([]);
    ctx.fillStyle = "#6b8f84"; ctx.font = "11px ui-monospace, monospace";
    ctx.fillText(`compact y, circumference 2πR = ${(2 * Math.PI * cfg.R).toFixed(2)}`, 10, H - 10);
    if (cfg.mode === "open") {
      for (const [y, lab] of [[0, "brane A"], [cfg.L, "brane B"]]) {
        const th = y / cfg.R - Math.PI / 2;
        ctx.strokeStyle = "#ffcf4a"; ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(geo.cx + (rho - 0.4 * rho) * Math.cos(th), geo.cy + (rho - 0.4 * rho) * Math.sin(th));
        ctx.lineTo(geo.cx + (rho + 0.4 * rho) * Math.cos(th), geo.cy + (rho + 0.4 * rho) * Math.sin(th));
        ctx.stroke();
        ctx.fillStyle = "#ffcf4a";
        ctx.fillText(lab, geo.cx + 1.5 * rho * Math.cos(th) - 22, geo.cy + 1.5 * rho * Math.sin(th) + (lab === "brane A" ? -4 : 12));
      }
    }
    trace.dims = fit(tr);
    fg.getContext("2d").clearRect(0, 0, W, H);
    dirty = false;
  }
  const trace = {};

  function toXY(p) {
    const th = p.y / cfg.R - Math.PI / 2;
    const r = Math.max(4, geo.rho + p.x1 * geo.ux);
    const d = p.x2 * geo.ux * 0.45;
    return [geo.cx + r * Math.cos(th) + d, geo.cy + r * Math.sin(th) - d];
  }

  function drawFrame() {
    const ctx = fg.getContext("2d");
    ctx.globalCompositeOperation = "destination-out";
    ctx.fillStyle = `rgba(0,0,0,${$("persist").value})`;
    ctx.fillRect(0, 0, geo.W, geo.H);
    ctx.globalCompositeOperation = "source-over";
    const pts = S.shape(cfg, tau, 360);
    const extent = pts.reduce((m, p) => Math.max(m, Math.abs(p.x1), Math.abs(p.x2), Math.abs(p.y - pts[0].y)), 0);
    ctx.shadowColor = "#39ff9c"; ctx.shadowBlur = 10;
    if (extent < 1e-9) {
      const [x, y] = toXY(pts[0]);
      for (let i = 0; i < 6; i++) {
        ctx.fillStyle = "rgba(57,255,156,0.5)";
        ctx.beginPath(); ctx.arc(x + (Math.random() - 0.5) * 6, y + (Math.random() - 0.5) * 6, 1.6, 0, 2 * Math.PI); ctx.fill();
      }
      ctx.fillStyle = "#39ff9c"; ctx.beginPath(); ctx.arc(x, y, 3.5, 0, 2 * Math.PI); ctx.fill();
    } else {
      ctx.strokeStyle = "#39ff9c"; ctx.lineWidth = 2;
      ctx.beginPath();
      pts.forEach((p, i) => { const [x, y] = toXY(p); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
      if (cfg.mode === "closed") ctx.closePath();
      ctx.stroke();
      if (cfg.mode === "closed") {
        const [x, y] = toXY(pts[0]);
        ctx.fillStyle = "#ffcf4a"; ctx.beginPath(); ctx.arc(x, y, 3, 0, 2 * Math.PI); ctx.fill();
      }
    }
    ctx.shadowBlur = 0;
    drawTrace(pts);
  }

  function drawTrace(pts) {
    const [W, H] = trace.dims, ctx = tr.getContext("2d");
    ctx.fillStyle = "#05080a"; ctx.fillRect(0, 0, W, H);
    graticule(ctx, W, H, 12, 4);
    const span = pts[pts.length - 1].s, scale = H / 2 / 3.2;
    const zero = cfg.mode === "open" ? (p) => (cfg.L * p.s) / Math.PI : (p) => (cfg.k / cfg.R) * tau + cfg.w * cfg.R * p.s;
    const series = [["x1", "#39ff9c", (p) => p.x1], ["x2", "#ffcf4a", (p) => p.x2], ["y", "#4ad8ff", (p) => p.y - zero(p)]];
    for (const [, color, get] of series) {
      ctx.strokeStyle = color; ctx.lineWidth = 1.6; ctx.shadowColor = color; ctx.shadowBlur = 6;
      ctx.beginPath();
      pts.forEach((p, i) => { const x = (p.s / span) * W, y = H / 2 - get(p) * scale; i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
      ctx.stroke();
    }
    ctx.shadowBlur = 0; ctx.fillStyle = "#6b8f84"; ctx.font = "11px ui-monospace, monospace";
    ctx.fillText("σ = 0", 4, H - 6); ctx.fillText(cfg.mode === "open" ? "σ = π" : "σ = 2π", W - 44, H - 6);
    ctx.fillText(`τ = ${tau.toFixed(2)}`, W - 80, 14);
  }

  function loop(now) {
    const dt = Math.min(0.1, (now - lastT) / 1000); lastT = now;
    if (playing) tau += dt * Number($("spd").value);
    if (dirty) drawBackground();
    drawFrame();
    requestAnimationFrame(loop);
  }
  window.addEventListener("resize", () => (dirty = true));

  $("preset").value = "Graviton g_μν";
  syncControls(); updatePanel();
  requestAnimationFrame(loop);
})();
