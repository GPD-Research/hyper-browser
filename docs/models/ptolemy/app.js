(function () {
  "use strict";
  const P = window.Ptolemy;

  const BODIES = {
    Moon: { color: "#c9d1de", synodic: 29.5, trailDays: 30, step: 0.2, hypotheses: 64, size: 4 },
    Mercury: { color: "#7fd1c7", synodic: 116, trailDays: 365, step: 0.5, hypotheses: 166, size: 3.5 },
    Venus: { color: "#f4e7b5", synodic: 584, trailDays: 2922, step: 2, hypotheses: 1079, size: 4.5 },
    Sun: { color: "#ffc93c", synodic: 365.25, trailDays: 365.25, step: 5, hypotheses: 1260, size: 7 },
    Mars: { color: "#ff6b4a", synodic: 780, trailDays: 15 * 365.25, step: 3, hypotheses: 8820, size: 4 },
    Jupiter: { color: "#e8a96b", synodic: 399, trailDays: 12 * 365.25, step: 5, hypotheses: 14187, size: 5.5 },
    Saturn: { color: "#d7c58a", synodic: 378, trailDays: 30 * 365.25, step: 6, hypotheses: 19865, size: 5 },
  };
  const ORDER = Object.keys(BODIES);
  const SIGNS = ["\u2648", "\u2649", "\u264A", "\u264B", "\u264C", "\u264D", "\u264E", "\u264F", "\u2650", "\u2651", "\u2652", "\u2653"]
    .map((g) => g + "\uFE0E");

  // Greatest distance from Earth in parts, used to scale each body to its ring.
  function maxDistance(name) {
    if (name === "Sun") return P.SUN.R + P.SUN.e;
    if (name === "Moon") return P.MOON.R + P.MOON.e + P.MOON.r;
    const p = P.PLANETS.find((q) => q.name === name);
    return p.R + (p.kind === "mercury" ? 3 * p.e : p.e) + p.r;
  }

  const $ = (id) => document.getElementById(id);
  const canvas = $("sky");
  const ctx = canvas.getContext("2d");
  const opt = {};
  ["showDeferent", "showEpicycle", "showEquant", "showSight", "showZodiac"].forEach((id) => {
    opt[id] = $(id).checked;
    $(id).addEventListener("change", () => { opt[id] = $(id).checked; });
  });

  let days = P.daysFromDate(new Date());
  let speed = 1;
  let playing = true;
  let direction = 1;
  let scaleMode = "schematic";
  let focus = "All";
  let zoom = 1;
  let pan = [0, 0];
  let lastFrame = performance.now();
  let trailMode = $("trail").value;
  $("trail").addEventListener("change", () => { trailMode = $("trail").value; });

  const focusSel = $("focus");
  ["All", ...ORDER].forEach((n) => {
    const o = document.createElement("option");
    o.value = n;
    o.textContent = n === "All" ? "All bodies" : "Only " + n;
    focusSel.appendChild(o);
  });

  // ---- view geometry ------------------------------------------------------
  let W = 0, H = 0, dpr = 1;
  function resize() {
    dpr = window.devicePixelRatio || 1;
    W = canvas.clientWidth;
    H = canvas.clientHeight;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
  }
  window.addEventListener("resize", resize);

  const viewRadius = () => Math.min(W, H) / 2 - (opt.showZodiac ? 44 : 16);

  // Pixels per part for a body before zoom.
  function bodyScale(name) {
    const R = viewRadius();
    if (focus !== "All") return (0.97 * R) / maxDistance(name);
    if (scaleMode === "hypotheses") return (BODIES[name].hypotheses / 19865) * R / maxDistance(name);
    const ring = ORDER.indexOf(name) + 1;
    return ((ring / ORDER.length) * R) / maxDistance(name);
  }

  function toScreen(p, k) {
    return [W / 2 + pan[0] + p[0] * k * zoom, H / 2 + pan[1] - p[1] * k * zoom];
  }

  // ---- drawing ------------------------------------------------------------
  function circle(c, radius, k, stroke, width, dash) {
    const s = toScreen(c, k);
    ctx.beginPath();
    ctx.arc(s[0], s[1], radius * k * zoom, 0, 2 * Math.PI);
    ctx.setLineDash(dash || []);
    ctx.strokeStyle = stroke;
    ctx.lineWidth = width;
    ctx.stroke();
    ctx.setLineDash([]);
  }

  function line(a, b, k, stroke, width, dash) {
    const sa = toScreen(a, k), sb = toScreen(b, k);
    ctx.beginPath();
    ctx.moveTo(sa[0], sa[1]);
    ctx.lineTo(sb[0], sb[1]);
    ctx.setLineDash(dash || []);
    ctx.strokeStyle = stroke;
    ctx.lineWidth = width;
    ctx.stroke();
    ctx.setLineDash([]);
  }

  function mark(p, k, color, kind) {
    const [x, y] = toScreen(p, k);
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    if (kind === "plus") { ctx.moveTo(x - 4, y); ctx.lineTo(x + 4, y); ctx.moveTo(x, y - 4); ctx.lineTo(x, y + 4); }
    else if (kind === "cross") { ctx.moveTo(x - 4, y - 4); ctx.lineTo(x + 4, y + 4); ctx.moveTo(x - 4, y + 4); ctx.lineTo(x + 4, y - 4); }
    else { ctx.moveTo(x, y - 4); ctx.lineTo(x + 4, y); ctx.lineTo(x, y + 4); ctx.lineTo(x - 4, y); ctx.closePath(); }
    ctx.stroke();
  }

  function alpha(hex, a) {
    const n = parseInt(hex.slice(1), 16);
    return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`;
  }

  function drawZodiac() {
    const R = viewRadius() + 6;
    const cx = W / 2, cy = H / 2;
    ctx.strokeStyle = "#2a3550";
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.arc(cx, cy, R, 0, 2 * Math.PI); ctx.stroke();
    ctx.beginPath(); ctx.arc(cx, cy, R + 30, 0, 2 * Math.PI); ctx.stroke();
    for (let d = 0; d < 360; d += 5) {
      const a = (d * Math.PI) / 180, len = d % 30 === 0 ? 30 : d % 10 === 0 ? 7 : 4;
      ctx.beginPath();
      ctx.moveTo(cx + R * Math.cos(a), cy - R * Math.sin(a));
      ctx.lineTo(cx + (R + len) * Math.cos(a), cy - (R + len) * Math.sin(a));
      ctx.stroke();
    }
    ctx.fillStyle = "#6f7d9c";
    ctx.font = "16px system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    SIGNS.forEach((g, i) => {
      const a = ((i * 30 + 15) * Math.PI) / 180;
      ctx.fillText(g, cx + (R + 17) * Math.cos(a), cy - (R + 17) * Math.sin(a));
    });
  }

  function drawTrail(name, k) {
    const b = BODIES[name];
    const span = trailMode === "long" ? b.trailDays : b.synodic;
    const start = Math.floor((days - span) / b.step) * b.step;
    ctx.beginPath();
    let first = true;
    for (let t = start; t < days; t += b.step) {
      const s = toScreen(P.bodyState(name, t).pos, k);
      if (first) { ctx.moveTo(s[0], s[1]); first = false; } else ctx.lineTo(s[0], s[1]);
    }
    const s = toScreen(P.bodyState(name, days).pos, k);
    ctx.lineTo(s[0], s[1]);
    ctx.strokeStyle = alpha(b.color, 0.45);
    ctx.lineWidth = 1;
    ctx.stroke();
  }

  function drawBody(st) {
    const name = st.name;
    const b = BODIES[name];
    const k = bodyScale(name);
    const faint = alpha(b.color, 0.35);
    const mid = alpha(b.color, 0.65);
    const origin = [0, 0];

    if (trailMode !== "off") drawTrail(name, k);
    if (opt.showDeferent) {
      circle(st.center, st.R, k, faint, 1);
      mark(st.center, k, mid, "plus");
      if (st.crank) circle(st.crank.center, st.crank.radius, k, faint, 1, [2, 3]);
      if (name === "Moon") circle(origin, P.MOON.e, k, faint, 1, [2, 3]);
    }
    if (opt.showEquant && st.equant) {
      mark(st.equant, k, mid, "cross");
      line(st.equant, st.epi, k, faint, 1, [5, 4]);
    }
    if (opt.showEquant && st.prosneusis) {
      mark(st.prosneusis, k, mid, "diamond");
      line(st.prosneusis, st.epi, k, faint, 1, [5, 4]);
    }
    if (opt.showEpicycle && st.epi) {
      circle(st.epi, st.r, k, mid, 1.2);
      line(st.epi, st.pos, k, mid, 1.2);
      const c = toScreen(st.epi, k);
      ctx.fillStyle = mid;
      ctx.beginPath(); ctx.arc(c[0], c[1], 2, 0, 2 * Math.PI); ctx.fill();
    }
    if (opt.showSight) line(origin, st.pos, k, alpha(b.color, 0.25), 1);

    const s = toScreen(st.pos, k);
    ctx.fillStyle = b.color;
    ctx.shadowColor = b.color;
    ctx.shadowBlur = name === "Sun" ? 14 : 6;
    ctx.beginPath(); ctx.arc(s[0], s[1], b.size, 0, 2 * Math.PI); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = b.color;
    ctx.font = "12px system-ui, sans-serif";
    ctx.textAlign = "left";
    ctx.textBaseline = "bottom";
    ctx.fillText(name, s[0] + b.size + 3, s[1] - 2);
  }

  function drawEarth() {
    const [x, y] = toScreen([0, 0], 1);
    ctx.fillStyle = "#3fa7d6";
    ctx.beginPath(); ctx.arc(x, y, 6, 0, 2 * Math.PI); ctx.fill();
    ctx.strokeStyle = "#7cc6a4";
    ctx.lineWidth = 1.5;
    ctx.stroke();
  }

  function draw(states) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    if (opt.showZodiac) drawZodiac();
    const shown = focus === "All" ? ORDER : [focus];
    shown.forEach((n) => drawBody(states[n]));
    drawEarth();
  }

  // ---- readouts -----------------------------------------------------------
  const pad = (n, w = 2) => String(n).padStart(w, "0");
  function formatDate(d) {
    const y = d.getUTCFullYear();
    const ys = y < 0 ? "-" + pad(-y, 4) : pad(y, 4);
    return `${ys}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())} UTC`;
  }

  function formatLon(lon) {
    const sign = Math.floor(lon / 30) % 12;
    const within = lon - sign * 30;
    let deg = Math.floor(within);
    let min = Math.round((within - deg) * 60);
    if (min === 60) { deg += 1; min = 0; }
    return `${SIGNS[sign]} ${pad(deg)}\u00B0${pad(min)}\u2032`;
  }

  function formatSpeed(x) {
    const units = [[31557600, "yr"], [2629800, "mo"], [604800, "wk"], [86400, "d"], [3600, "h"], [60, "min"], [1, "s"]];
    const [div, unit] = units.find(([d]) => x >= d) || units[units.length - 1];
    const v = x / div;
    const vs = v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2);
    const mult = x < 1000 ? x.toFixed(x < 10 ? 1 : 0) : x.toExponential(1).replace("e+", "e");
    return `1 s = ${vs} ${unit}  (\u00D7${mult})`;
  }

  const rows = {};
  const tbody = $("table");
  ORDER.forEach((n) => {
    const tr = document.createElement("tr");
    tr.className = "body";
    tr.innerHTML = `<td><span class="dot" style="background:${BODIES[n].color}"></span>${n}</td><td class="num"></td><td class="num"></td>`;
    tr.addEventListener("click", () => setFocus(focus === n ? "All" : n));
    tbody.appendChild(tr);
    rows[n] = tr;
  });

  let lastTable = -Infinity;
  function updateReadouts(states, now) {
    const d = P.dateFromDays(days);
    $("date").textContent = isNaN(d.getTime()) ? "out of range" : formatDate(d);
    const jd = days + P.EPOCH_JD;
    $("dateSub").textContent = `JD ${jd.toFixed(3)} \u00B7 Nabonassar day ${days.toFixed(2)} \u00B7 proleptic Gregorian`;
    if (now - lastTable < 100) return;
    lastTable = now;
    ORDER.forEach((n) => {
      const cells = rows[n].children;
      cells[1].textContent = formatLon(states[n].lon);
      const m = P.dailyMotion(n, days);
      cells[2].innerHTML = `${m >= 0 ? "+" : ""}${m.toFixed(3)}\u00B0/d${m < 0 ? ' <span class="retro">R</span>' : ""}`;
    });
  }

  // ---- controls -----------------------------------------------------------
  const speedInput = $("speed");
  function setSpeed(x) {
    speed = Math.min(1e8, Math.max(1, x));
    speedInput.value = String(Math.round(Math.log10(speed) * 100));
    $("speedLabel").textContent = formatSpeed(speed);
  }
  speedInput.addEventListener("input", () => {
    speed = 10 ** (Number(speedInput.value) / 100);
    $("speedLabel").textContent = formatSpeed(speed);
  });
  document.querySelectorAll("[data-speed]").forEach((b) =>
    b.addEventListener("click", () => setSpeed(Number(b.dataset.speed))));

  $("play").addEventListener("click", () => {
    playing = !playing;
    $("play").textContent = playing ? "Pause" : "Play";
    $("play").classList.toggle("on", playing);
  });
  $("reverse").addEventListener("click", () => {
    direction = -direction;
    $("reverse").classList.toggle("on", direction < 0);
  });
  $("now").addEventListener("click", () => { days = P.daysFromDate(new Date()); });

  function jump() {
    const m = $("jump").value.trim().match(/^(-?\d{1,6})-(\d{1,2})-(\d{1,2})$/);
    if (!m) { $("jump").style.borderColor = "#ff7b72"; return; }
    const [y, mo, dd] = [Number(m[1]), Number(m[2]), Number(m[3])];
    const d = new Date(Date.UTC(2000, 0, 1));
    d.setUTCFullYear(y, mo - 1, dd);
    if (isNaN(d.getTime()) || d.getUTCFullYear() !== y || d.getUTCMonth() !== mo - 1 || d.getUTCDate() !== dd) {
      $("jump").style.borderColor = "#ff7b72";
      return;
    }
    $("jump").style.borderColor = "";
    days = P.daysFromDate(d);
  }
  $("go").addEventListener("click", jump);
  $("jump").addEventListener("keydown", (e) => { if (e.key === "Enter") jump(); });

  function resetView() { zoom = 1; pan = [0, 0]; }
  function setFocus(n) {
    focus = n;
    focusSel.value = n;
    resetView();
  }
  focusSel.addEventListener("change", () => setFocus(focusSel.value));
  $("scale").addEventListener("change", () => { scaleMode = $("scale").value; resetView(); });

  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left - W / 2, my = e.clientY - rect.top - H / 2;
    const f = Math.exp(-e.deltaY * 0.0015);
    const nz = Math.min(500, Math.max(0.2, zoom * f));
    const g = nz / zoom;
    pan = [mx - (mx - pan[0]) * g, my - (my - pan[1]) * g];
    zoom = nz;
  }, { passive: false });
  let drag = null;
  canvas.addEventListener("pointerdown", (e) => {
    drag = [e.clientX - pan[0], e.clientY - pan[1]];
    canvas.setPointerCapture(e.pointerId);
    canvas.classList.add("dragging");
  });
  canvas.addEventListener("pointermove", (e) => { if (drag) pan = [e.clientX - drag[0], e.clientY - drag[1]]; });
  canvas.addEventListener("pointerup", () => { drag = null; canvas.classList.remove("dragging"); });
  canvas.addEventListener("dblclick", resetView);

  // ---- loop ---------------------------------------------------------------
  function frame(now) {
    const dt = Math.min(0.1, (now - lastFrame) / 1000);
    lastFrame = now;
    if (playing) days += (direction * speed * dt) / 86400;
    const states = {};
    P.state(days).forEach((s) => { states[s.name] = s; });
    draw(states);
    updateReadouts(states, now);
    requestAnimationFrame(frame);
  }

  resize();
  setSpeed(1);
  requestAnimationFrame(frame);
})();
