// CMB acoustic-peak chart: Planck 2018 TT points against the base-LCDM best fit, drawn in the
// stage's lower-left corner while the CMB is on the sphere. Data: assets/cmb_spectrum.js.
(function (root) {
  const PEAKS = [
    [220, "1", "position: space is flat"],
    [535, "2", "height: ordinary matter, 4.9%"],
    [815, "3", "height: dark matter, 26%"],
  ];
  // Log in l below 30 on the first 12% of the axis, linear above, as in Planck's plots.
  const xf = (l) => (l < 30 ? 0.12 * (Math.log(l / 2) / Math.log(15)) : 0.12 + (0.88 * (l - 30)) / 2470);

  // o: { W, H, alpha }
  function draw(ctx, o) {
    const S = (root.SKY_ASSETS || {}).cmb_spectrum;
    if (!S || o.alpha <= 0.01 || o.W < 560) return;
    const s = Math.max(1, Math.min(o.W, o.H) / 700), bw = Math.min(380 * s, o.W * 0.34), bh = bw * 0.9;
    const bx = 16 * s, by = o.H - bh - 16 * s, pad = 12 * s;
    const gx = bx + pad + 36 * s, gy = by + 30 * s, gw = bw - 2 * pad - 40 * s, gh = bh - 116 * s;
    const X = (l) => gx + xf(l) * gw, Y = (d) => gy + gh * (1 - d / 6500);
    ctx.save(); ctx.globalAlpha = o.alpha;
    ctx.fillStyle = "rgba(8,10,24,0.86)"; ctx.strokeStyle = "rgba(255,255,255,0.35)"; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.roundRect(bx, by, bw, bh, 10 * s); ctx.fill(); ctx.stroke();
    ctx.fillStyle = "#e8eefc"; ctx.font = `600 ${13 * s}px system-ui, sans-serif`; ctx.textBaseline = "top";
    ctx.fillText("CMB acoustic peaks: ΛCDM vs Planck", bx + pad, by + pad * 0.8);

    ctx.font = `${10 * s}px system-ui, sans-serif`; ctx.fillStyle = "#8590b8"; ctx.strokeStyle = "rgba(133,144,184,0.25)";
    for (const d of [0, 2000, 4000, 6000]) {
      ctx.beginPath(); ctx.moveTo(gx, Y(d)); ctx.lineTo(gx + gw, Y(d)); ctx.stroke();
      ctx.textBaseline = "middle"; ctx.fillText(String(d), gx - ctx.measureText(String(d)).width - 4 * s, Y(d));
    }
    ctx.textBaseline = "top";
    for (const l of [2, 30, 500, 1000, 1500, 2000, 2500]) {
      const t = String(l); ctx.fillText(t, X(l) - ctx.measureText(t).width / 2, gy + gh + 3 * s);
    }
    ctx.fillText("multipole ℓ (smaller angles →)", gx + gw / 2 - 64 * s, gy + gh + 15 * s);
    ctx.save(); ctx.translate(bx + 5 * s, gy + gh / 2); ctx.rotate(-Math.PI / 2);
    ctx.textBaseline = "top"; ctx.fillText("Dℓ (μK²)", -22 * s, 0); ctx.restore();

    ctx.strokeStyle = "#f97316"; ctx.lineWidth = 2 * s; ctx.beginPath();
    S.theory.forEach(([l, d], i) => (i ? ctx.lineTo(X(l), Y(d)) : ctx.moveTo(X(l), Y(d))));
    ctx.stroke();
    ctx.strokeStyle = "rgba(232,238,252,0.85)"; ctx.fillStyle = "#e8eefc"; ctx.lineWidth = 1;
    for (const [l, d, lo, hi] of S.points) {
      const x = X(l);
      ctx.beginPath(); ctx.moveTo(x, Y(Math.max(0, d - lo))); ctx.lineTo(x, Y(Math.min(6500, d + hi))); ctx.stroke();
      ctx.beginPath(); ctx.arc(x, Y(d), 1.6 * s, 0, 6.283); ctx.fill();
    }
    ctx.font = `600 ${10 * s}px system-ui, sans-serif`; ctx.textBaseline = "bottom";
    for (const [l, n] of PEAKS) {
      const d = S.theory.find((p) => p[0] >= l)[1];
      ctx.fillStyle = "#ffd36b"; ctx.fillText(n, X(l) - 3 * s, Y(d) - 4 * s);
    }
    ctx.font = `${10 * s}px system-ui, sans-serif`; ctx.textBaseline = "top"; ctx.fillStyle = "#9aa6c8";
    const lines = [...PEAKS.map(([, n, t]) => `Peak ${n} ${t}`), "Line: ΛCDM fit (6 parameters). Points: Planck 2018, ESA."];
    lines.forEach((t, i) => ctx.fillText(t, bx + pad, gy + gh + 32 * s + i * 13 * s, bw - 2 * pad));
    ctx.restore();
  }

  root.PeaksChart = { draw };
})(this);
