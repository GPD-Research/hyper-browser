// Zoom-in inset for the early eras: a callout from a spot on the sphere to a small animated
// Feynman diagram of the process that defines the era. Time runs left to right in each diagram.
(function (root) {
  // Edge kinds: f fermion (arrow), v photon (wavy), w W/Z boson (wavy, heavier), g gluon (curly),
  // s scalar (dashed), b bound state (double line). Points are in a unit box.
  const D = {
    inflation: {
      title: "Reheating: the inflaton decays",
      eq: "φ → q q̄, ℓ⁺ℓ⁻, γγ …",
      note: "At the end of inflation the inflaton field's energy pours into particle pairs.",
      edges: [["s", [0.05, 0.5], [0.5, 0.5], "φ"], ["f", [0.5, 0.5], [0.95, 0.15], "q"], ["f", [0.95, 0.85], [0.5, 0.5], "q̄"]],
    },
    radiation: {
      title: "Radiation soup: light and matter swap",
      eq: "γγ ⇄ e⁺e⁻, q q̄, …",
      note: "Every particle is ultra-hot and effectively massless; photons and particle pairs turn into each other constantly.",
      edges: [["v", [0.05, 0.15], [0.5, 0.3], "γ"], ["v", [0.05, 0.85], [0.5, 0.7], "γ"], ["f", [0.5, 0.3], [0.95, 0.15], "e⁻"],
        ["f", [0.95, 0.85], [0.5, 0.7], "e⁺"], ["f", [0.5, 0.7], [0.5, 0.3], ""]],
    },
    electroweak: {
      title: "Electroweak breaking: the Higgs gives W and Z mass",
      eq: "m_W = g v / 2 ≈ 80 GeV,  v = 246 GeV",
      note: "Around 10⁻¹¹ s the Higgs field settles to its value v; W and Z gain mass from it.",
      edges: [["w", [0.05, 0.5], [0.5, 0.5], "W"], ["w", [0.5, 0.5], [0.95, 0.5], "W"], ["s", [0.5, 0.5], [0.5, 0.1], "⟨H⟩ = v"]],
      vev: [0.5, 0.1],
    },
    quark_gluon_plasma: {
      title: "Free quarks and gluons",
      eq: "q q̄ → g → q q̄",
      note: "Too hot for quarks to bind: they scatter freely through gluons.",
      edges: [["f", [0.05, 0.15], [0.35, 0.5], "q"], ["f", [0.35, 0.5], [0.05, 0.85], "q̄"], ["g", [0.35, 0.5], [0.65, 0.5], "g"],
        ["f", [0.65, 0.5], [0.95, 0.15], "q"], ["f", [0.95, 0.85], [0.65, 0.5], "q̄"]],
    },
    hadrons: {
      title: "Confinement: quarks bind into protons",
      eq: "u u d → p  (938 MeV)",
      note: "Gluons exchanged between the quarks hold them together as protons and neutrons.",
      edges: [["f", [0.05, 0.22], [0.95, 0.22], "u"], ["f", [0.05, 0.5], [0.95, 0.5], "u"], ["f", [0.05, 0.78], [0.95, 0.78], "d"],
        ["g", [0.35, 0.22], [0.35, 0.5], ""], ["g", [0.65, 0.5], [0.65, 0.78], ""], ["g", [0.82, 0.22], [0.82, 0.5], ""]],
    },
    leptons: {
      title: "Neutrinos decouple",
      eq: "ν e → ν e  via Z;  rate ∝ T⁵ drops below H at ~1 MeV",
      note: "Weak scattering becomes too rare, so neutrinos stream freely. Then e⁺e⁻ → γγ heats the photons.",
      edges: [["f", [0.05, 0.15], [0.5, 0.3], "ν"], ["f", [0.5, 0.3], [0.95, 0.15], "ν"], ["f", [0.05, 0.85], [0.5, 0.7], "e⁻"],
        ["f", [0.5, 0.7], [0.95, 0.85], "e⁻"], ["w", [0.5, 0.3], [0.5, 0.7], "Z"]],
    },
    nuclei: {
      title: "Nucleosynthesis",
      eq: "p + n → D + γ,  then D + D → ⁴He",
      note: "Deuterium survives once photons are too cool to split it; most of it ends up as helium-4.",
      edges: [["f", [0.05, 0.25], [0.5, 0.5], "p"], ["f", [0.05, 0.75], [0.5, 0.5], "n"], ["b", [0.5, 0.5], [0.95, 0.5], "D"],
        ["v", [0.5, 0.5], [0.95, 0.12], "γ"]],
    },
    opaque_plasma: {
      title: "Thomson scattering keeps it opaque",
      eq: "γ e⁻ → γ e⁻,  σ_T = 6.65 × 10⁻²⁹ m²",
      note: "Light bounces off free electrons so often it can't travel far: a glowing fog.",
      edges: [["v", [0.05, 0.15], [0.35, 0.55], "γ"], ["f", [0.05, 0.85], [0.35, 0.55], "e⁻"], ["f", [0.35, 0.55], [0.65, 0.55], "e⁻"],
        ["f", [0.65, 0.55], [0.95, 0.85], "e⁻"], ["v", [0.65, 0.55], [0.95, 0.15], "γ"]],
    },
    transparent: {
      title: "Recombination",
      eq: "p + e⁻ → H + γ  (13.6 eV)",
      note: "Electrons join protons into neutral hydrogen, so light no longer scatters: the CMB is released.",
      edges: [["f", [0.05, 0.25], [0.5, 0.5], "p"], ["f", [0.05, 0.75], [0.5, 0.5], "e⁻"], ["b", [0.5, 0.5], [0.95, 0.5], "H"],
        ["v", [0.5, 0.5], [0.95, 0.12], "γ"]],
    },
  };
  const COL = { f: "#e8eefc", v: "#ffd36b", w: "#ff9e6b", g: "#7fe0a8", s: "#c9a6ff", b: "#e8eefc" };

  function edge(ctx, kind, a, b, ph, s) {
    const dx = b[0] - a[0], dy = b[1] - a[1], L = Math.hypot(dx, dy), ux = dx / L, uy = dy / L, nx = -uy, ny = ux;
    ctx.strokeStyle = COL[kind]; ctx.lineWidth = (kind === "w" ? 2.4 : 1.8) * s; ctx.setLineDash([]);
    ctx.beginPath();
    if (kind === "v" || kind === "w" || kind === "g") {
      const n = Math.max(24, Math.round(L / (2 * s))), k = kind === "g" ? 0.9 : 0.6, amp = (kind === "g" ? 5 : 4) * s;
      for (let i = 0; i <= n; i++) {
        const u = (i / n) * L, w = (u / s) * k - ph * 6;
        const off = amp * Math.sin(w), back = kind === "g" ? amp * 0.9 * Math.cos(w) : 0;
        ctx.lineTo(a[0] + ux * (u - back) + nx * off, a[1] + uy * (u - back) + ny * off);
      }
      ctx.stroke();
      return;
    }
    if (kind === "s") ctx.setLineDash([6 * s, 5 * s]), (ctx.lineDashOffset = -ph * 30 * s);
    if (kind === "b") {
      for (const o of [-2, 2]) { ctx.moveTo(a[0] + nx * o * s, a[1] + ny * o * s); ctx.lineTo(b[0] + nx * o * s, b[1] + ny * o * s); }
      ctx.lineWidth = 1.4 * s;
    } else { ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); }
    ctx.stroke(); ctx.setLineDash([]);
    if (kind === "f") {
      const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2, h = 6 * s;
      ctx.fillStyle = COL.f; ctx.beginPath();
      ctx.moveTo(mx + ux * h, my + uy * h); ctx.lineTo(mx - ux * h + nx * h * 0.6, my - uy * h + ny * h * 0.6);
      ctx.lineTo(mx - ux * h - nx * h * 0.6, my - uy * h - ny * h * 0.6); ctx.fill();
    }
    // A particle riding the line, entering on the left and leaving on the right.
    const f = (ph % 1 + 1) % 1, start = a[0] <= b[0] ? a : b, end = a[0] <= b[0] ? b : a;
    const px = start[0] + (end[0] - start[0]) * f, py = start[1] + (end[1] - start[1]) * f;
    ctx.fillStyle = COL[kind]; ctx.globalAlpha = Math.sin(Math.PI * f);
    ctx.beginPath(); ctx.arc(px, py, 2.6 * s, 0, 6.283); ctx.fill(); ctx.globalAlpha = 1;
  }

  function wrap(ctx, text, x, y, width, lh) {
    let line = "";
    for (const word of text.split(" ")) {
      if (ctx.measureText(line + word).width > width && line) { ctx.fillText(line, x, y); y += lh; line = ""; }
      line += word + " ";
    }
    ctx.fillText(line, x, y);
    return y + lh;
  }

  // o: { W, H, cx, cy, R, era, logt, alpha, now }
  function draw(ctx, o) {
    const d = D[o.logt >= -13 && o.logt < -10 ? "electroweak" : o.era];
    if (!d || o.alpha <= 0.01 || o.W < 560) return;
    const s = Math.max(1, Math.min(o.W, o.H) / 700), bw = Math.min(360 * s, o.W * 0.3), bh = Math.max(bw * 0.95, 340 * s);
    const bx = o.W - bw - 18 * s, by = Math.max(56 * s, o.cy - bh / 2 - 40 * s);
    const ax = o.cx + Math.max(o.R, 2) * 0.42, ay = o.cy - Math.max(o.R, 2) * 0.32;
    ctx.save(); ctx.globalAlpha = o.alpha;
    ctx.strokeStyle = "rgba(255,255,255,0.75)"; ctx.lineWidth = 1.2 * s;
    ctx.beginPath(); ctx.arc(ax, ay, 6 * s, 0, 6.283); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(ax + 6 * s * 0.7, ay - 6 * s * 0.7); ctx.lineTo(bx, by + bh * 0.55); ctx.stroke();
    ctx.fillStyle = "rgba(8,10,24,0.86)"; ctx.beginPath(); ctx.roundRect(bx, by, bw, bh, 10 * s); ctx.fill(); ctx.stroke();

    const pad = 14 * s, gx = bx + pad, gy = by + 44 * s, gw = bw - 2 * pad, gh = bh * 0.5;
    ctx.fillStyle = "#e8eefc"; ctx.font = `600 ${14 * s}px system-ui, sans-serif`; ctx.textBaseline = "top";
    wrap(ctx, d.title, gx, by + pad * 0.8, gw, 17 * s);
    const P = (p) => [gx + p[0] * gw, gy + p[1] * gh], ph = o.now / 1600;
    for (const [kind, a, b] of d.edges) edge(ctx, kind, P(a), P(b), ph, s);
    if (d.vev) { const [x, y] = P(d.vev); ctx.strokeStyle = COL.s; ctx.lineWidth = 1.6 * s; ctx.beginPath(); ctx.arc(x, y, 6 * s, 0, 6.283); ctx.moveTo(x - 4 * s, y - 4 * s); ctx.lineTo(x + 4 * s, y + 4 * s); ctx.moveTo(x + 4 * s, y - 4 * s); ctx.lineTo(x - 4 * s, y + 4 * s); ctx.stroke(); }
    const vertices = new Map();
    ctx.font = `${12 * s}px system-ui, sans-serif`; ctx.textBaseline = "middle";
    for (const [kind, a, b, label] of d.edges) {
      for (const p of [a, b]) vertices.set(p.join(), (vertices.get(p.join()) || 0) + 1);
      if (!label) continue;
      const [x1, y1] = P(a), [x2, y2] = P(b), dx = x2 - x1, dy = y2 - y1, L = Math.hypot(dx, dy);
      ctx.fillStyle = COL[kind];
      ctx.fillText(label, (x1 + x2) / 2 - (dy / L) * 14 * s - ctx.measureText(label).width / 2, (y1 + y2) / 2 + (dx / L) * 14 * s);
    }
    ctx.fillStyle = "#ffffff";
    for (const [key, n] of vertices) if (n > 1) { const [x, y] = P(key.split(",").map(Number)); ctx.beginPath(); ctx.arc(x, y, 3 * s, 0, 6.283); ctx.fill(); }
    ctx.textBaseline = "top"; ctx.fillStyle = "#ffd36b"; ctx.font = `${13 * s}px "Cambria Math", "STIX Two Math", serif`;
    const eqEnd = wrap(ctx, d.eq, gx, gy + gh + 12 * s, gw, 16 * s);
    ctx.fillStyle = "#9aa6c8"; ctx.font = `${11.5 * s}px system-ui, sans-serif`;
    wrap(ctx, d.note, gx, eqEnd + 6 * s, gw, 14.5 * s);
    ctx.restore();
  }

  root.EraInset = { draw, eras: Object.keys(D) };
})(this);
