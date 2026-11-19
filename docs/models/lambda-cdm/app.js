(() => {
  const root = window, C = Cosmos, $ = (id) => document.getElementById(id);
  const LOG_MIN = -36, LOG_NOW = Math.log10(C.AGE), LOG_MAX = Math.log10(C.fromNow(1e110));
  const STATUS = {
    verified: ["Verified", "#4ade80"],
    lab: ["Recreated in the lab", "#38bdf8"],
    partial: ["Partly verified", "#fbbf24"],
    untested: ["Not yet verified", "#94a3b8"],
    future: ["Future prediction", "#c084fc"],
  };
  const OBSERVABLE_RADIUS_M = 4.4e26, LY_M = 9.4607e15;
  let logt = LOG_MIN, playing = false, selected = null, lastT = performance.now();

  // ---------- fixed random sky: one smooth field gives the CMB spots and seeds galaxies ----------
  let seed = 11;
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const unit = () => { const z = 2 * rnd() - 1, p = 2 * Math.PI * rnd(), s = Math.sqrt(1 - z * z); return [s * Math.cos(p), s * Math.sin(p), z]; };
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const norm = (v) => { const l = Math.hypot(...v); return v.map((x) => x / l); };
  const lobes = Array.from({ length: 60 }, () => ({ n: unit(), c: 2 * rnd() - 1, w: 0.12 + 0.22 * rnd() }));
  const field = (p) => lobes.reduce((s, l) => s + l.c * Math.exp((dot(p, l.n) - 1) / (l.w * l.w)), 0);
  const SPOTS = Array.from({ length: 1100 }, () => { const p = unit(); return { p, v: field(p), ph: rnd() * 6.283, sp: 0.5 + rnd() }; });
  const vmax = Math.max(...SPOTS.map((s) => Math.abs(s.v)));
  SPOTS.forEach((s) => (s.v /= vmax));
  // Structure grows from the overdense spots, so stars and galaxies prefer them.
  const near = (s, j) => norm(s.p.map((x) => x + j * (rnd() - 0.5)));
  const pick = () => { for (;;) { const s = SPOTS[Math.floor(rnd() * SPOTS.length)]; if (rnd() < 0.08 + 0.92 * Math.max(0, s.v)) return s; } };
  const STARS = Array.from({ length: 260 }, () => ({ p: near(pick(), 0.08), born: 15.45 + 0.75 * rnd() }));
  const GALAXIES = Array.from({ length: 420 }, () => ({
    p: near(pick(), 0.1), born: 15.95 + 0.9 * rnd(), size: 0.6 + rnd(), spiral: rnd() < 0.6, ang: rnd() * Math.PI,
  }));

  // Real galaxies from the Hubble Ultra Deep Field, each shown twice at random spots and angles.
  // A galaxy appears when the light Hubble saw left it, at t(z) from its catalogue redshift.
  const SKY = root.SKY_ASSETS || {}, HUDF = SKY.hudf_galaxies;
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const HUDF_PLACED = [];
  if (HUDF) {
    seed = 29;
    for (let copy = 0; copy < 2; copy++) for (const [x, y, size, z, sidePx] of HUDF.galaxies) {
      const p = near(pick(), 0.12), ang = rnd() * 6.283, hs = 0.005 + sidePx / 8000;
      const u = norm(cross(p, Math.abs(p[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0])), v = cross(p, u);
      const e1 = u.map((c, i) => hs * (Math.cos(ang) * c + Math.sin(ang) * v[i]));
      const e2 = u.map((c, i) => hs * (-Math.sin(ang) * c + Math.cos(ang) * v[i]));
      HUDF_PLACED.push({ p, e1, e2, z, born: Math.log10(C.timeOfRedshift(z)), gain: copy ? 0.85 : 1,
        uv: [x / HUDF.width, y / HUDF.height, size / HUDF.width, size / HUDF.height] });
    }
    HUDF_PLACED.minBorn = Math.min(...HUDF_PLACED.map((g) => g.born));
  }
  const HUDF_BORN = HUDF_PLACED.slice(0, HUDF ? HUDF.galaxies.length : 0).map((g) => g.born).sort((a, b) => a - b);

  // ---------- era visuals, keyed on log10(t / s) ----------
  const lerp = (a, b, f) => a + (b - a) * f;
  function track(stops, x) {
    if (x <= stops[0][0]) return stops[0][1];
    for (let i = 1; i < stops.length; i++) {
      const [x1, v1] = stops[i];
      if (x <= x1) {
        const [x0, v0] = stops[i - 1], f = (x - x0) / (x1 - x0);
        return Array.isArray(v0) ? v0.map((v, k) => lerp(v, v1[k], f)) : lerp(v0, v1, f);
      }
    }
    return stops[stops.length - 1][1];
  }
  const ramp = (x, a, b) => { const f = Math.min(1, Math.max(0, (x - a) / (b - a))); return f * f * (3 - 2 * f); };
  const BASE = [[-36, [255, 255, 255]], [-32, [225, 236, 255]], [-12, [255, 248, 222]], [-5, [255, 226, 150]],
    [2.5, [255, 178, 92]], [12.9, [255, 140, 62]], [13.1, [120, 40, 32]], [14, [45, 14, 22]], [15.3, [12, 10, 22]], [18, [10, 10, 24]]];
  BASE.push([21.6, [7, 7, 16]], [27, [4, 4, 10]]);
  const SPECKLE = [[-36, 0.15], [-32, 0.75], [-5, 0.75], [2.5, 0.35], [12.85, 0.15], [12.98, 0]];
  const GLOW = [[-36, 1], [-12, 0.9], [2.5, 0.55], [12.9, 0.4], [13.1, 0.05], [18, 0]];

  // Sphere radius on a compressed log scale of a, so every era stays visible.
  const SIZE = [[-52, 0.006], [-25.7, 0.16], [-10, 0.36], [-3, 0.6], [-1, 0.8], [0, 1], [3, 1.06], [30, 1.12]];
  // How strongly the CMB map shows: it appears at release, dims in the dark ages and fades out
  // entirely by ~10¹² years from now, when it has redshifted beyond detection.
  const cmbLevel = (lt) => ramp(lt, 12.9, 13.05) * (1 - 0.97 * ramp(lt, 13.5, 15.6)) * (1 - ramp(lt, 18.5, 19.5));
  // Beyond the merged Local Group, galaxies fade out of view 30–300 billion years from now.
  const galFade = (lt) => 1 - ramp(lt, 18.1, 19.0);

  // ---------- formatting ----------
  const SUP = { "-": "⁻", 0: "⁰", 1: "¹", 2: "²", 3: "³", 4: "⁴", 5: "⁵", 6: "⁶", 7: "⁷", 8: "⁸", 9: "⁹" };
  const sci = (x, d = 1) => {
    if (x === 0) return "0";
    const e = Math.floor(Math.log10(Math.abs(x))), m = x / 10 ** e;
    return `${m.toFixed(d)} × 10${String(e).replace(/./g, (c) => SUP[c])}`;
  };
  const num = (x, d = 3) => (Math.abs(x) >= 1e5 || Math.abs(x) < 1e-3 ? sci(x, d - 1) : Number(x.toPrecision(d)).toLocaleString());
  function fmtTime(t) {
    const yr = t / C.YEAR;
    if (t < 1e-3) return `${sci(t)} s`;
    if (t < 120) return `${num(t)} s`;
    if (t < 7200) return `${num(t / 60)} minutes`;
    if (t < 2 * 86400) return `${num(t / 3600)} hours`;
    if (yr < 1) return `${num(t / 86400)} days`;
    if (yr < 1e6) return `${Number(yr.toPrecision(3)).toLocaleString()} years`;
    if (yr < 1e9) return `${num(yr / 1e6)} million years`;
    if (yr < 1e12) return `${(yr / 1e9).toFixed(2)} billion years`;
    return `${sci(yr)} years`;
  }
  function fmtEnergy(ev) {
    const u = [[1e12, "TeV"], [1e9, "GeV"], [1e6, "MeV"], [1e3, "keV"], [1, "eV"], [1e-3, "meV"]];
    for (const [s, n] of u) if (ev >= s) return ev / s >= 1e4 ? `${sci(ev / 1e9)} GeV` : `${num(ev / s)} ${n}`;
    return `${sci(ev)} eV`;
  }
  function fmtLength(m) {
    if (m < 1e-15) return `${sci(m)} m (smaller than a proton)`;
    if (m < 1e-3) return `${sci(m)} m`;
    if (m < 1e3) return `${num(m)} m`;
    if (m < 0.1 * LY_M) return `${sci(m)} m`;
    const ly = m / LY_M;
    if (ly < 1e6) return `${num(ly)} light-years`;
    if (ly < 1e9) return `${num(ly / 1e6)} million light-years`;
    return `${num(ly / 1e9)} billion light-years`;
  }

  // ---------- canvases ----------
  const sky = $("sky"), skyGl = $("sky-gl"), skyTop = $("sky-top"), tl = $("timeline");
  const gl = root.SkyGL ? root.SkyGL.create(skyGl, SKY, HUDF_PLACED) : null;
  const MAPS = {
    cmb_cobe: ["COBE DMR", "COBE DMR 4-year map (53 + 90 GHz), NASA/COBE Science Team, via NASA LAMBDA. About 7° resolution; the dipole is removed and the Milky Way band is filled in."],
    cmb_wmap: ["WMAP", "WMAP 9-year Internal Linear Combination map, NASA/WMAP Science Team, via NASA LAMBDA. About 1° resolution."],
    cmb_planck: ["Planck", "Planck 2015 SMICA CMB map, ESA and the Planck Collaboration, via NASA/IPAC IRSA. 5 arcminute resolution; masked Milky Way pixels are filled in."],
  };
  let cmbMap = "cmb_cobe";
  if (!gl) skyGl.style.display = "none";
  let skyDims = [0, 0], tlDims = [0, 0];
  function fit(cv, dimsRef) {
    const r = cv.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(r.width * dpr); cv.height = Math.round(r.height * dpr);
    cv.getContext("2d").setTransform(dpr, 0, 0, dpr, 0, 0);
    dimsRef[0] = r.width; dimsRef[1] = r.height;
  }
  const resize = () => { fit(sky, skyDims); fit(skyTop, [0, 0]); fit(tl, tlDims); };
  window.addEventListener("resize", resize);

  const rgb = (c, a = 1) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`;
  function cmbColor(v) {
    return v < 0 ? [lerp(255, 40, -v), lerp(240, 90, -v), lerp(220, 230, -v)] : [255, lerp(240, 120, v), lerp(220, 40, v)];
  }

  function drawSky(now) {
    const [W, H] = skyDims, ctx = sky.getContext("2d");
    ctx.clearRect(0, 0, W, H);
    const t = 10 ** logt, la = C.lnScaleFactor(t) / Math.LN10, a = 10 ** la, fade = galFade(logt);
    const cx = W / 2, cy = H / 2 + 14, R = Math.max(1.5, track(SIZE, la) * Math.min(W, H) * 0.42);
    const base = track(BASE, logt), glow = track(GLOW, logt), speckle = track(SPECKLE, logt);
    const cmb = cmbLevel(logt);
    const lambda = logt > 10 ? C.densityFractions(a).dark_energy : 0;

    if (glow > 0) {
      const g = ctx.createRadialGradient(cx, cy, R * 0.6, cx, cy, R * (1.6 + glow));
      g.addColorStop(0, rgb(base, 0.55 * glow)); g.addColorStop(1, rgb(base, 0));
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, R * (1.6 + glow), 0, 6.283); ctx.fill();
    }
    if (lambda > 0.05) {
      const g = ctx.createRadialGradient(cx, cy, R, cx, cy, R * 1.25);
      g.addColorStop(0, `rgba(150,120,255,${0.35 * lambda})`); g.addColorStop(1, "rgba(150,120,255,0)");
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, R * 1.25, 0, 6.283); ctx.fill();
    }
    const body = ctx.createRadialGradient(cx - R * 0.3, cy - R * 0.35, R * 0.05, cx, cy, R);
    body.addColorStop(0, rgb(base.map((c) => Math.min(255, c * 1.15 + 20))));
    body.addColorStop(1, rgb(base.map((c) => c * 0.75)));
    ctx.fillStyle = body; ctx.beginPath(); ctx.arc(cx, cy, R, 0, 6.283); ctx.fill();

    ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, R, 0, 6.283); ctx.clip();
    const rot = now * 0.00006, cr = Math.cos(rot), sr = Math.sin(rot), tilt = 0.4, ct = Math.cos(tilt), st = Math.sin(tilt);
    const project = (p) => {
      const x = p[0] * cr + p[2] * sr, z0 = -p[0] * sr + p[2] * cr;
      const y = p[1] * ct - z0 * st, z = p[1] * st + z0 * ct;
      return [cx + R * x, cy + R * y, z];
    };
    if (R > 6 && (speckle > 0 || cmb > 0)) {
      for (const s of SPOTS) {
        const [x, y, z] = project(s.p);
        if (z <= 0) continue;
        if (speckle > 0) {
          const fl = 0.5 + 0.5 * Math.sin(now * 0.006 * s.sp + s.ph);
          ctx.fillStyle = `rgba(255,255,255,${speckle * 0.35 * fl * z})`;
          ctx.beginPath(); ctx.arc(x, y, R * 0.035 * (0.6 + z), 0, 6.283); ctx.fill();
        }
        if (cmb > 0 && !gl) {
          ctx.fillStyle = rgb(cmbColor(s.v), cmb * 0.55 * z);
          ctx.beginPath(); ctx.arc(x, y, R * 0.085 * (0.4 + 0.6 * z), 0, 6.283); ctx.fill();
        }
      }
    }
    for (const s of STARS) {
      const on = ramp(logt, s.born, s.born + 0.15) * (1 - 0.6 * ramp(logt, 16.4, 17)) * fade;
      if (on <= 0) continue;
      const [x, y, z] = project(s.p);
      if (z <= 0) continue;
      const r = Math.max(1, R * 0.006);
      ctx.fillStyle = `rgba(200,220,255,${on * z})`;
      ctx.shadowColor = "#9cc0ff"; ctx.shadowBlur = 6;
      ctx.beginPath(); ctx.arc(x, y, r, 0, 6.283); ctx.fill();
    }
    ctx.shadowBlur = 0;
    for (const g of gl ? [] : GALAXIES) {
      const on = ramp(logt, g.born, g.born + 0.25) * fade;
      if (on <= 0) continue;
      const [x, y, z] = project(g.p);
      if (z <= 0) continue;
      const r = Math.max(1.2, R * 0.028 * g.size);
      ctx.save(); ctx.translate(x, y); ctx.rotate(g.ang); ctx.scale(1, g.spiral ? 0.35 + 0.5 * z : 0.7);
      const gr = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
      const col = g.spiral ? [190, 210, 255] : [255, 222, 175];
      gr.addColorStop(0, rgb([255, 250, 235], on * z)); gr.addColorStop(0.35, rgb(col, 0.8 * on * z)); gr.addColorStop(1, rgb(col, 0));
      ctx.fillStyle = gr; ctx.beginPath(); ctx.arc(0, 0, r, 0, 6.283); ctx.fill();
      ctx.restore();
    }
    drawFuture(ctx, cx, cy, R);
    if (!gl) {
      const limb = ctx.createRadialGradient(cx, cy, R * 0.55, cx, cy, R);
      limb.addColorStop(0, "rgba(0,0,0,0)"); limb.addColorStop(1, "rgba(0,0,0,0.55)");
      ctx.fillStyle = limb; ctx.fillRect(cx - R, cy - R, 2 * R, 2 * R);
    }
    ctx.restore();
    if (gl) {
      const d = window.devicePixelRatio || 1;
      gl.draw({
        W: Math.round(W * d), H: Math.round(H * d), cx: cx * d, cy: cy * d, R: R * d,
        M: [cr, 0, sr, st * sr, ct, -st * cr, -ct * sr, st, ct * cr],
        map: cmbMap, cmb: cmb * 0.92, warm: 1 - ramp(logt, 13.1, 13.9), contrast: ramp(logt, 12.95, 13.2),
        logt, zNow: 1 / a - 1, fade,
      });
    }
    const tctx = skyTop.getContext("2d");
    tctx.clearRect(0, 0, W, H);
    if (window.EraInset && $("show-inset").checked) EraInset.draw(tctx, { W, H, cx, cy, R, era: C.era(t).id, logt, alpha: 1, now });
    if (window.PeaksChart && $("show-inset").checked) PeaksChart.draw(tctx, { W, H, alpha: ramp(logt, 12.95, 13.05) * (1 - ramp(logt, 15.2, 15.6)) });
  }

  // The merged Milky Way and Andromeda ("Milkomeda") stays in view after the other galaxies
  // leave; its stars die, the galaxy evaporates, and its central black hole finally pops.
  function drawFuture(ctx, cx, cy, R) {
    const on = ramp(logt, 17.9, 18.6), stars = 1 - ramp(logt, 20.6, 21.6), left = 1 - ramp(logt, 26.2, 26.8);
    const glow = on * (0.25 + 0.75 * stars) * left;
    if (glow > 0.01) {
      const r = Math.max(3, R * 0.07), col = [lerp(255, 150, 1 - stars), lerp(236, 50, 1 - stars), lerp(205, 40, 1 - stars)];
      ctx.save(); ctx.translate(cx, cy); ctx.rotate(-0.5); ctx.scale(1, 0.62);
      const g = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
      g.addColorStop(0, rgb([255, 248, 230].map((c, i) => lerp(c, col[i], 1 - stars)), glow));
      g.addColorStop(0.3, rgb(col, 0.6 * glow)); g.addColorStop(1, rgb(col, 0));
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(0, 0, r, 0, 6.283); ctx.fill();
      ctx.restore();
    }
    const bh = ramp(logt, 21, 22) * (1 - ramp(logt, 105.5, 107.5)), flash = ramp(logt, 105.5, 107.5) * (1 - ramp(logt, 107.5, 109.5));
    if (bh > 0.01) {
      ctx.fillStyle = "#000"; ctx.strokeStyle = `rgba(190,150,255,${0.7 * bh})`; ctx.lineWidth = 1.5;
      ctx.beginPath(); ctx.arc(cx, cy, Math.max(2.5, R * 0.012), 0, 6.283); ctx.fill(); ctx.stroke(); ctx.lineWidth = 1;
    }
    if (flash > 0.01) {
      const r = R * 0.08, g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
      g.addColorStop(0, `rgba(255,255,255,${flash})`); g.addColorStop(1, "rgba(200,170,255,0)");
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, r, 0, 6.283); ctx.fill();
    }
  }

  // ---------- timeline ----------
  // The past is log10(t) on the left 70%. The future is log10(1 + τ / 10⁸ yr), with τ the time
  // after today, squeezed in stages so the far future fits the last 30%.
  const TAU0 = 1e8 * C.YEAR;
  const qOf = (lt) => (lt <= LOG_NOW ? lt : LOG_NOW + Math.log10(1 + (10 ** lt - C.AGE) / TAU0));
  const ltOfQ = (q) => (q <= LOG_NOW ? q : Math.log10(C.AGE + (10 ** (q - LOG_NOW) - 1) * TAU0));
  const qYr = (yr) => qOf(Math.log10(C.fromNow(yr)));
  const KNOTS = [[LOG_MIN, 0], [LOG_NOW, 0.7], [qYr(1e19), 0.88], [qYr(1e100), 0.97], [qYr(1e110), 1]];
  function pos(lt) {
    const q = qOf(lt);
    return track(KNOTS, q);
  }
  function unpos(p) {
    return ltOfQ(track(KNOTS.map(([q, f]) => [f, q]), p));
  }
  const PAD = 18;
  const xOf = (lt, W) => PAD + pos(lt) * (W - 2 * PAD);
  const ltOf = (x, W) => unpos((x - PAD) / (W - 2 * PAD));
  const TICKS = [[-30, "10⁻³⁰ s"], [-20, "10⁻²⁰ s"], [-10, "10⁻¹⁰ s"], [0, "1 s"], [Math.log10(3600), "1 hr"],
    [Math.log10(C.YEAR), "1 yr"], [Math.log10(1e3 * C.YEAR), "1 kyr"], [Math.log10(1e6 * C.YEAR), "1 Myr"], [Math.log10(1e9 * C.YEAR), "1 Gyr"],
    ...[[1e12, "+1 Tyr"], [1e15, "+10¹⁵ yr"], [1e20, "+10²⁰ yr"], [1e60, "+10⁶⁰ yr"], [1e100, "+10¹⁰⁰ yr"]].map(([y, l]) => [Math.log10(C.fromNow(y)), l])];
  const ERA_COLORS = ["#3b2f6b", "#5b3d7a", "#6b3f5e", "#7a4a3c", "#7a5a32", "#6e6630", "#7a4f24", "#8a3d2a", "#1f2645", "#2b3d6e", "#2f4f6e", "#3d3570", "#2d2850", "#3a2230", "#1c1530", "#101020"];
  const eventX = (e, W) => xOf(Math.log10(e.t), W);
  const BAND_Y = 30, BAND_H = 34, EV_Y = 92;

  function drawTimeline() {
    const [W, H] = tlDims, ctx = tl.getContext("2d");
    ctx.clearRect(0, 0, W, H);
    C.ERAS.forEach((e, i) => {
      const x0 = xOf(Math.log10(e.start), W), x1 = i + 1 < C.ERAS.length ? xOf(Math.log10(C.ERAS[i + 1].start), W) : xOf(LOG_MAX, W);
      ctx.fillStyle = ERA_COLORS[i]; ctx.fillRect(x0, BAND_Y, x1 - x0, BAND_H);
    });
    ctx.font = "11px system-ui, sans-serif"; ctx.textBaseline = "middle";
    const SHORT = { inflation: "Inflation", radiation: "Radiation", quark_gluon_plasma: "Quark–gluon plasma", hadrons: "Hadrons", leptons: "ν", nuclei: "", opaque_plasma: "Opaque", transparent: "", dark_ages: "Dark ages", first_stars: "Stars", galaxies: "Galaxies", dark_energy: "", alone: "Alone", last_stars: "Dead stars", black_holes: "Black holes", empty: "" };
    C.ERAS.forEach((e, i) => {
      const x0 = xOf(Math.log10(e.start), W), x1 = i + 1 < C.ERAS.length ? xOf(Math.log10(C.ERAS[i + 1].start), W) : xOf(LOG_MAX, W);
      const label = SHORT[e.id];
      if (label && ctx.measureText(label).width < x1 - x0 - 6) { ctx.fillStyle = "#e8ecff"; ctx.fillText(label, x0 + 4, BAND_Y + BAND_H / 2); }
    });
    ctx.fillStyle = "#8590b8"; ctx.textBaseline = "alphabetic";
    for (const [lt, lab] of TICKS) {
      const x = xOf(lt, W);
      ctx.fillRect(x, BAND_Y - 6, 1, 6);
      ctx.fillText(lab, x - ctx.measureText(lab).width / 2, BAND_Y - 10);
    }
    const xNow = xOf(LOG_NOW, W);
    ctx.fillStyle = "rgba(255,255,255,0.55)"; ctx.fillRect(xNow - 0.5, BAND_Y - 4, 1, BAND_H + 8);
    ctx.strokeStyle = "#2a3563"; ctx.beginPath(); ctx.moveTo(PAD, EV_Y); ctx.lineTo(W - PAD, EV_Y); ctx.stroke();
    for (const e of C.EVENTS) {
      const x = eventX(e, W);
      ctx.fillStyle = STATUS[e.status][1];
      ctx.beginPath(); ctx.arc(x, EV_Y, e === selected ? 7 : 5, 0, 6.283); ctx.fill();
      if (e === selected) { ctx.strokeStyle = "#fff"; ctx.lineWidth = 2; ctx.stroke(); ctx.lineWidth = 1; }
    }
    const xc = xOf(logt, W);
    ctx.fillStyle = "#ffffff"; ctx.fillRect(xc - 1, BAND_Y - 4, 2, EV_Y - BAND_Y + 18);
    ctx.beginPath(); ctx.moveTo(xc - 6, H - 22); ctx.lineTo(xc + 6, H - 22); ctx.lineTo(xc, H - 30); ctx.fill();
  }
  function eventAt(x, y) {
    if (Math.abs(y - EV_Y) > 12) return null;
    let best = null, bd = 10;
    for (const e of C.EVENTS) { const d = Math.abs(eventX(e, tlDims[0]) - x); if (d < bd) { bd = d; best = e; } }
    return best;
  }
  tl.addEventListener("click", (ev) => {
    const r = tl.getBoundingClientRect(), x = ev.clientX - r.left, y = ev.clientY - r.top, e = eventAt(x, y);
    if (e) jumpTo(e); else setLogt(ltOf(x, tlDims[0]));
  });
  tl.addEventListener("mousemove", (ev) => {
    const r = tl.getBoundingClientRect(), x = ev.clientX - r.left, y = ev.clientY - r.top, e = eventAt(x, y), tip = $("tip");
    if (!e) { tip.style.display = "none"; return; }
    tip.textContent = `${e.name} · ${fmtTime(e.t)} · ${STATUS[e.status][0]}`;
    tip.style.display = "block";
    tip.style.left = `${Math.min(x + 12, tlDims[0] - tip.offsetWidth - 4)}px`; tip.style.top = `${y + 14}px`;
  });
  tl.addEventListener("mouseleave", () => ($("tip").style.display = "none"));
  $("keys").innerHTML = Object.values(STATUS).map(([n, c]) => `<span><i style="background:${c}"></i>${n}</span>`).join("")
    + "<span>Click a dot for its prediction and evidence, or anywhere on the bar to jump in time.</span>";

  // ---------- panel ----------
  function updatePanel() {
    const t = 10 ** logt, la = C.lnScaleFactor(t) / Math.LN10, a = 10 ** la, e = C.era(t), inflating = t < C.INF_END;
    $("ov-era").textContent = e.label; $("ov-time").textContent = fmtTime(t);
    $("state-label").textContent = e.label; $("state-detail").textContent = e.detail;
    const src = sourceNote(logt);
    $("state-source").textContent = src ? src[1] : ""; $("ov-src").textContent = src ? src[0] : "";
    $("r-time").textContent = fmtTime(t);
    const T = C.temperature(a), floor = T < C.T_DE_SITTER, big = la > 300;
    $("r-temp").textContent = inflating ? "≈ 0 K (energy is in the inflaton)"
      : floor ? `${sci(C.T_DE_SITTER)} K (de Sitter horizon; the CMB is colder)` : `${num(T)} K`;
    $("r-kt").textContent = inflating ? "—" : fmtEnergy(8.617333e-5 * Math.max(T, C.T_DE_SITTER));
    $("r-z").textContent = num(1 / a - 1);
    $("r-a").textContent = big ? `10^(${num(la)})` : num(a);
    const lsize = la + Math.log10(OBSERVABLE_RADIUS_M / LY_M);
    $("r-size").textContent = lsize > 300 ? `10^(${num(lsize)}) light-years` : fmtLength(OBSERVABLE_RADIUS_M * a);
    const f = C.densityFractions(a);
    const rows = inflating ? [["Inflaton field", 1, "#c084fc"]]
      : [["Radiation", f.radiation, "#fbbf24"], ["Dark matter", f.dark_matter, "#3b82f6"],
        ["Ordinary matter", f.ordinary_matter, "#93c5fd"], ["Dark energy Λ", f.dark_energy, "#a78bfa"]];
    const q = C.deceleration(a);
    $("r-exp").textContent = inflating || q < 0 ? "Speeding up" : "Slowing down";
    $("r-q").textContent = inflating ? "≈ −1" : q.toFixed(2).replace("-", "−");
    $("bars").innerHTML = rows.map(([n, v, c]) => `<div class="bar"><span>${n}</span><div class="track"><div class="fill" style="width:${(100 * v).toFixed(1)}%;background:${c}"></div></div><span class="pct">${v < 0.001 && v > 0 ? "<0.1" : (100 * v).toFixed(1)}%</span></div>`).join("");
    if (inflating) {
      $("eq-h").innerHTML = "H ≈ constant, a ∝ e<sup>Ht</sup>";
      $("eq-t").textContent = "During inflation the Friedmann equation is dominated by the inflaton's vacuum-like energy.";
    } else {
      const ai = 1 / a;
      $("eq-h").innerHTML = `H² = H₀²(Ω<sub>r</sub>a⁻⁴ + Ω<sub>m</sub>a⁻³ + Ω<sub>Λ</sub>)`;
      $("eq-t").innerHTML = `= H₀²(${num(C.Or * ai ** 4)} + ${num(C.Om * ai ** 3)} + ${C.Ol.toFixed(3)}) → H = ${num(C.hubble(a))} km/s/Mpc`;
    }
    const ev = selected || [...C.EVENTS].reverse().find((x) => x.t <= t * 1.0001) || C.EVENTS[0];
    $("event-name").textContent = `${ev.name} (${fmtTime(ev.t)})`;
    $("event-badge").textContent = STATUS[ev.status][0]; $("event-badge").style.background = STATUS[ev.status][1];
    $("event-pred").textContent = ev.predicted; $("event-evid").textContent = ev.evidence;
    $("t-slider").value = pos(logt);
  }

  function sourceNote(lt) {
    const cmb = cmbLevel(lt);
    if (gl && cmb > 0.05) {
      const [name, credit] = MAPS[cmbMap], range = SKY[cmbMap].rangeMicroK;
      const parts = Math.round(C.T_CMB0 * 1e6 / range / 1000) * 1000;
      return [`CMB image: ${name}`, `Image: ${credit} Colours span ±${range} μK, a ripple of about 1 part in ${parts.toLocaleString()}, greatly exaggerated.`];
    }
    let n = 0;
    while (n < HUDF_BORN.length && HUDF_BORN[n] <= lt) n++;
    if (gl && n > 0 && galFade(lt) > 0.05) {
      return ["Galaxies: Hubble Ultra Deep Field",
        `Galaxies: Hubble Ultra Deep Field (ESA/Hubble heic0406a), NASA, ESA, S. Beckwith (STScI) and the HUDF Team, CC BY 4.0; redshifts from Rafelski et al. 2015. ${n} of ${HUDF_BORN.length} have appeared: each shows up when the light Hubble saw left it, in Hubble's colours, and looks bluer nearer the present as its redshift shrinks. Their places on the sphere are illustrative.`];
    }
    return null;
  }

  function setLogt(v, keepSelection = false) {
    logt = Math.min(LOG_MAX, Math.max(LOG_MIN, v));
    if (!keepSelection) selected = null;
    updatePanel();
  }
  function jumpTo(e) { selected = e; setLogt(Math.log10(e.t), true); }
  function step(dir) {
    const cur = 10 ** logt, list = dir > 0 ? C.EVENTS.filter((e) => e.t > cur * 1.001) : C.EVENTS.filter((e) => e.t < cur / 1.001).reverse();
    if (list.length) jumpTo(list[0]);
  }

  $("t-slider").addEventListener("input", (e) => setLogt(unpos(Number(e.target.value))));
  $("play").onclick = () => {
    if (!playing && logt >= LOG_MAX - 1e-6) setLogt(LOG_MIN);
    playing = !playing; $("play").textContent = playing ? "Pause" : "Play";
  };
  $("prev").onclick = () => step(-1);
  $("next").onclick = () => step(1);
  const syncSpeed = () => ($("speed-v").textContent = `${Number($("speed").value).toFixed(1)} decades/s`);
  $("speed").addEventListener("input", syncSpeed);

  function loop(now) {
    const dt = Math.min(0.1, (now - lastT) / 1000); lastT = now;
    if (playing) {
      // Playback runs in decades of the timeline coordinate and pauses once at today.
      const was = logt;
      setLogt(ltOfQ(qOf(logt) + dt * Number($("speed").value)));
      if (was < LOG_NOW && logt >= LOG_NOW) setLogt(LOG_NOW);
      if (logt >= LOG_MAX || logt === LOG_NOW) { playing = false; $("play").textContent = "Play"; }
    }
    drawSky(now); drawTimeline();
    requestAnimationFrame(loop);
  }

  const hash = new URLSearchParams(location.hash.slice(1)), fromHash = Number(hash.get("t"));
  if (hash.has("t") && Number.isFinite(fromHash)) logt = Math.min(LOG_MAX, Math.max(LOG_MIN, fromHash));
  if (MAPS[`cmb_${hash.get("map")}`]) cmbMap = `cmb_${hash.get("map")}`;
  const pickMap = $("cmb-map");
  if (gl) {
    pickMap.value = cmbMap.slice(4);
    pickMap.addEventListener("change", () => { cmbMap = `cmb_${pickMap.value}`; gl.map(cmbMap); updatePanel(); });
    gl.map(cmbMap);
  } else pickMap.parentElement.style.display = "none";
  resize(); syncSpeed(); updatePanel();
  requestAnimationFrame(loop);
})();
