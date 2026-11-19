// Bosonic string on a circle of radius R, in units alpha' = 1.
// Closed: alpha'M^2 = k^2/R^2 + w^2 R^2 + 2(N + Nt - 2), with N - Nt = k w.
// Open (Dirichlet along the circle, ends on two branes a distance L apart):
//   alpha'M^2 = (L / 2pi)^2 + N - 1.
// Mirrored in src/analysis/string_spectrum.py; keep the two in sync.
(function (root) {
  const TOL = 1e-9;
  const TAU = 2 * Math.PI;

  const level = (bank) => bank.n * bank.q;
  const amplitude = (bank) => (bank.q > 0 ? Math.sqrt((2 * bank.q) / bank.n) : 0);

  function massSquared(cfg) {
    if (cfg.mode === "open") return (cfg.L / TAU) ** 2 + level(cfg.left) - 1;
    const { k, w, R } = cfg;
    return (k / R) ** 2 + (w * R) ** 2 + 2 * (level(cfg.left) + level(cfg.right) - 2);
  }

  const levelMatched = (cfg) =>
    cfg.mode === "open" || level(cfg.left) - level(cfg.right) === cfg.k * cfg.w;

  const state = (name, symbol, detail, extra = {}) => ({ name, symbol, detail, ...extra });
  const compact = (pol) => pol === "y";

  function closedTensor(pl, pr) {
    if (!compact(pl) && !compact(pr)) {
      if (pl === pr)
        return [state("Graviton", "g_μν", "symmetric tensor α₋₁ α̃₋₁; its trace part is the Dilaton Φ")];
      return [
        state("Graviton", "g_μν", "symmetric half of α₋₁ α̃₋₁"),
        state("Kalb–Ramond field", "B_μν", "antisymmetric half of α₋₁ α̃₋₁"),
      ];
    }
    if (compact(pl) && compact(pr))
      return [state("Radion", "σ", "size of the extra dimension, the 1-D analogue of KKLT's volume modulus t")];
    return [
      state("Graviphoton", "A_μ", "Kaluza–Klein photon from g_μy"),
      state("Winding photon", "B_μ", "vector from B_μy"),
    ];
  }

  function combine(parts, extra) {
    return state(
      parts.map((p) => p.name).join(" + "),
      parts.map((p) => p.symbol).join(" + "),
      parts.map((p) => p.detail).join("; "),
      { parts, ...extra }
    );
  }

  function classify(cfg) {
    const m2 = massSquared(cfg);
    const N = level(cfg.left);
    const Nt = cfg.mode === "open" ? 0 : level(cfg.right);
    const base = { m2, N, Nt, massless: Math.abs(m2) < TOL, physical: levelMatched(cfg) };

    if (!base.physical)
      return state("Not a physical state", "—", "level matching N − Ñ = kw fails", base);
    if (m2 < -TOL)
      return state("Tachyon", "T", "M² < 0: the bosonic string's unstable ground state", base);

    if (cfg.mode === "open") {
      const pol = cfg.left.pol;
      if (N === 1 && cfg.left.n === 1) {
        if (!compact(pol))
          return cfg.L === 0
            ? state("Photon", "γ", "massless U(1) gauge field A_μ living on the brane", base)
            : state("W boson", "W±", "string stretched between separated branes: a massive gauge boson (Higgs mechanism)", base);
        return cfg.L === 0
          ? state("Brane-position scalar", "φ", "oscillation along the Dirichlet direction moves the brane", base)
          : state("Massive scalar", "φ", "stretched-string mode along the Dirichlet direction", base);
      }
      if (base.massless) return state("Massless scalar", "φ", "stretched ground state at L = 2π√α′", base);
      return state("Massive string state", "—", `no named particle; spin up to ${N}`, base);
    }

    const { k, w } = cfg;
    if (N === 1 && Nt === 1) {
      const parts = closedTensor(cfg.left.pol, cfg.right.pol);
      if (base.massless) return combine(parts, base);
      const kind = k !== 0 ? "Kaluza–Klein" : "winding";
      const tag = k !== 0 ? `(k=${k})` : `(w=${w})`;
      return combine(
        parts.map((p) => state(`${p.name}, ${kind} mode`, `${p.symbol}${tag}`, `massive copy: ${p.detail}`)),
        base
      );
    }
    if (base.massless) {
      const bank = N === 1 ? cfg.left : Nt === 1 ? cfg.right : null;
      if (bank && !compact(bank.pol))
        return state("W gauge boson", "W±", "extra massless vector at the self-dual radius R = √α′ (SU(2) symmetry)", base);
      return state("Massless scalar", "φ", "extra massless scalar at a special radius", base);
    }
    return state("Massive string state", "—", `no named particle; spin up to ${N + Nt}`, base);
  }

  // Classical coherent-state picture of one excited mode per bank.
  function shape(cfg, tau, points = 256) {
    const open = cfg.mode === "open";
    const span = open ? Math.PI : TAU;
    const out = [];
    for (let i = 0; i <= points; i++) {
      const s = (span * i) / points;
      const p = { s, x1: 0, x2: 0, y: 0 };
      if (open) {
        const b = cfg.left;
        const a = amplitude(b) * Math.cos(b.n * tau);
        p.y = (cfg.L * s) / Math.PI;
        if (compact(b.pol)) p.y += a * Math.sin(b.n * s);
        else p[b.pol] += a * Math.cos(b.n * s);
      } else {
        p.y = (cfg.k / cfg.R) * tau + cfg.w * cfg.R * s;
        const L = cfg.left, Rb = cfg.right;
        p[L.pol] += amplitude(L) * Math.cos(L.n * (tau + s));
        p[Rb.pol] += amplitude(Rb) * Math.cos(Rb.n * (tau - s));
      }
      out.push(p);
    }
    return out;
  }

  const tDual = (cfg) => ({ ...cfg, k: cfg.w, w: cfg.k, R: 1 / cfg.R });

  const api = { massSquared, levelMatched, classify, shape, tDual, level, amplitude };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.Strings = api;
})(this);
