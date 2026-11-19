/*
 * Ptolemaic kinematics from the Almagest (Toomer's translation).
 *
 * Lengths are in "parts" (deferent radius = 60). Angles are tropical longitudes
 * in degrees, counted eastward. Time is days from the Nabonassar epoch
 * (-746 Feb 26, noon at Alexandria), Ptolemy's zero point for all mean motions.
 * Mirrors src/almagest.py; tests/test_almagest.py checks the two agree.
 */
(function (root) {
  "use strict";

  const sexa = (...d) => d.reduce((acc, v, i) => acc + v / 60 ** i, 0);

  // Julian Day (UT) of the epoch: noon local mean time at Alexandria (29.9 deg E).
  const EPOCH_JD = 1448638.0 - 29.9 / 360;
  // Planetary apogees move with the fixed stars: 1 deg per 100 Egyptian years.
  const APSIDAL_RATE = 1 / 36500;

  const SUN = {
    name: "Sun", kind: "sun", R: 60, e: sexa(2, 30), apogee0: sexa(65, 30),
    meanLon0: sexa(330, 45), meanLonRate: sexa(0, 59, 8, 17, 13, 12, 31),
  };

  const MOON = {
    name: "Moon", kind: "moon", R: sexa(49, 41), e: sexa(10, 19), r: sexa(5, 15),
    meanLon0: sexa(41, 22), meanLonRate: sexa(13, 10, 34, 58, 33, 30, 30),
    anomaly0: sexa(268, 49), anomalyRate: sexa(13, 3, 53, 56, 17, 51, 59),
  };

  // Outer planets: mean longitude + mean anomaly = mean Sun (Almagest IX.3).
  const PLANETS = [
    {
      name: "Mercury", kind: "mercury", R: 60, e: 3, r: sexa(22, 30), apogee0: sexa(181, 10),
      anomaly0: sexa(21, 55), anomalyRate: sexa(3, 6, 24, 6, 59, 35, 50),
    },
    {
      name: "Venus", kind: "inner", R: 60, e: sexa(1, 15), r: sexa(43, 10), apogee0: sexa(46, 10),
      anomaly0: sexa(71, 7), anomalyRate: sexa(0, 36, 59, 25, 53, 11, 28),
    },
    {
      name: "Mars", kind: "outer", R: 60, e: 6, r: sexa(39, 30), apogee0: sexa(106, 40),
      meanLon0: sexa(3, 32), meanLonRate: sexa(0, 31, 26, 36, 53, 51, 33),
    },
    {
      name: "Jupiter", kind: "outer", R: 60, e: sexa(2, 45), r: sexa(11, 30), apogee0: sexa(152, 9),
      meanLon0: sexa(184, 41), meanLonRate: sexa(0, 4, 59, 14, 26, 46, 31),
    },
    {
      name: "Saturn", kind: "outer", R: 60, e: sexa(3, 25), r: sexa(6, 30), apogee0: sexa(224, 10),
      meanLon0: sexa(296, 43), meanLonRate: sexa(0, 2, 0, 33, 31, 28, 51),
    },
  ];

  const rad = (deg) => (deg * Math.PI) / 180;
  const norm = (deg) => ((deg % 360) + 360) % 360;
  const polar = (len, deg) => [len * Math.cos(rad(deg)), len * Math.sin(rad(deg))];
  const add = (a, b) => [a[0] + b[0], a[1] + b[1]];
  const angleOf = (from, to) => norm((Math.atan2(to[1] - from[1], to[0] - from[0]) * 180) / Math.PI);

  // Point where the ray from `origin` at longitude `deg` meets the circle (center, radius).
  function rayCircle(origin, deg, center, radius) {
    const u = polar(1, deg);
    const d = [origin[0] - center[0], origin[1] - center[1]];
    const b = d[0] * u[0] + d[1] * u[1];
    const t = -b + Math.sqrt(b * b - (d[0] * d[0] + d[1] * d[1]) + radius * radius);
    return [origin[0] + t * u[0], origin[1] + t * u[1]];
  }

  function meanSun(days) {
    return norm(SUN.meanLon0 + SUN.meanLonRate * days);
  }

  function sunState(days) {
    const center = polar(SUN.e, SUN.apogee0);
    const meanLon = meanSun(days);
    const pos = add(center, polar(SUN.R, meanLon));
    return { name: SUN.name, center, R: SUN.R, meanLon, pos, lon: angleOf([0, 0], pos) };
  }

  // Almagest V.2: the deferent centre turns westward about the Earth at the mean
  // elongation, the epicycle centre eastward, and the anomaly is counted from the
  // mean apogee aimed at the prosneusis point opposite the deferent centre.
  function moonState(days) {
    const sunLon = meanSun(days);
    const meanLon = norm(MOON.meanLon0 + MOON.meanLonRate * days);
    const elong = norm(meanLon - sunLon);
    const center = polar(MOON.e, sunLon - elong);
    const prosneusis = polar(MOON.e, sunLon - elong + 180);
    const epi = rayCircle([0, 0], meanLon, center, MOON.R);
    const anomaly = norm(MOON.anomaly0 + MOON.anomalyRate * days);
    const apogeeDir = angleOf(prosneusis, epi);
    const pos = add(epi, polar(MOON.r, apogeeDir - anomaly));
    return {
      name: MOON.name, center, R: MOON.R, r: MOON.r, equant: null, prosneusis,
      meanLon, anomaly, epi, apogeeDir, pos, lon: angleOf([0, 0], pos),
    };
  }

  function planetState(p, days) {
    const apogee = norm(p.apogee0 + APSIDAL_RATE * days);
    const meanLon = p.meanLonRate === undefined
      ? meanSun(days)
      : norm(p.meanLon0 + p.meanLonRate * days);
    const anomaly = p.anomalyRate === undefined
      ? norm(meanSun(days) - meanLon)
      : norm(p.anomaly0 + p.anomalyRate * days);
    let equant;
    let center;
    let crank = null;
    if (p.kind === "mercury") {
      // Almagest IX.8: equant 3 from Earth toward the apogee; the deferent centre
      // runs westward on a circle of radius 3 about a point 6 from Earth, at the
      // same rate the epicycle centre runs eastward about the equant.
      equant = polar(p.e, apogee);
      const crankCenter = polar(2 * p.e, apogee);
      center = add(crankCenter, polar(p.e, apogee - (meanLon - apogee)));
      crank = { center: crankCenter, radius: p.e };
    } else {
      equant = polar(2 * p.e, apogee);
      center = polar(p.e, apogee);
    }
    const epi = rayCircle(equant, meanLon, center, p.R);
    const apogeeDir = angleOf(equant, epi);
    const pos = add(epi, polar(p.r, apogeeDir + anomaly));
    return {
      name: p.name, center, R: p.R, r: p.r, equant, crank, apogee,
      meanLon, anomaly, epi, apogeeDir, pos, lon: angleOf([0, 0], pos),
    };
  }

  function state(days) {
    return [sunState(days), moonState(days), ...PLANETS.map((p) => planetState(p, days))];
  }

  function bodyState(name, days) {
    if (name === "Sun") return sunState(days);
    if (name === "Moon") return moonState(days);
    return planetState(PLANETS.find((p) => p.name === name), days);
  }

  // Signed daily motion in longitude (negative = retrograde).
  function dailyMotion(name, days) {
    const a = bodyState(name, days - 0.5).lon;
    const b = bodyState(name, days + 0.5).lon;
    return ((b - a + 540) % 360) - 180;
  }

  const daysFromDate = (date) => date.getTime() / 86400000 + 2440587.5 - EPOCH_JD;
  const dateFromDays = (days) => new Date((days + EPOCH_JD - 2440587.5) * 86400000);

  const api = {
    EPOCH_JD, SUN, MOON, PLANETS, BODY_NAMES: ["Sun", "Moon", ...PLANETS.map((p) => p.name)],
    state, bodyState, dailyMotion, daysFromDate, dateFromDays, norm,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.Ptolemy = api;
})(this);
