// Flat Lambda-CDM timeline in seconds after the Big Bang, with the Planck 2018 values of
// Lambda_CDM/src/core.py. Below A_LOW the universe is radiation dominated:
// t = a^2 / (2 H0 sqrt(Omega_r)). Inflation is drawn as 60 e-folds ending at 1e-32 s.
// After today radiation is negligible and the exact matter + Lambda solution is used:
// a^(3/2) = sqrt(Om/Ol) sinh(3/2 H_L tau), H_L = H0 sqrt(Ol). Far-future a overflows a double,
// so lnScaleFactor is the primary function there.
// Mirrored in Lambda_CDM/src/history.py; tests/test_history.py compares the two.
(function (root) {
  const MPC_KM = 3.085677581e19, YEAR = 3.15576e7, T_CMB0 = 2.7255;
  const H0 = 67.4, Om = 0.315, Or = 9.2e-5, Ol = 1 - Om - Or;
  // Planck 2018: Omega_b h^2 = 0.0224, so ordinary matter is 0.0493 and dark matter the rest of Om.
  const Ob = 0.0493;
  const h0 = H0 / MPC_KM;
  const A_LOW = 1e-12, INF_START = 1e-36, INF_END = 1e-32, EFOLDS = 60;

  const E = (a) => Math.sqrt(Or / a ** 4 + Om / a ** 3 + Ol);
  const tRad = (a) => (a * a) / (2 * h0 * Math.sqrt(Or));
  const hubble = (a) => H0 * E(a);

  // Cumulative Simpson integral of dt = d(ln a) / H from A_LOW to a = 1.
  const N = 4000, LN_LO = Math.log(A_LOW), DL = -LN_LO / N;
  const LN_T = new Float64Array(N + 1);
  const dtdl = (l) => 1 / (h0 * E(Math.exp(l)));
  let acc = tRad(A_LOW);
  LN_T[0] = Math.log(acc);
  for (let i = 0; i < N; i++) {
    const l = LN_LO + i * DL;
    acc += (DL / 6) * (dtdl(l) + 4 * dtdl(l + DL / 2) + dtdl(l + DL));
    LN_T[i + 1] = Math.log(acc);
  }
  const AGE = acc;

  const hL = h0 * Math.sqrt(Ol), RATIO = Math.sqrt(Ol / Om);
  const tML = (a) => (2 / (3 * hL)) * Math.asinh(RATIO * a ** 1.5);
  const TML1 = tML(1);

  const A_INF_END = Math.sqrt(2 * h0 * Math.sqrt(Or) * INF_END);

  function cosmicTime(a) {
    if (a < A_INF_END) return Math.max(0, INF_END - (Math.log(A_INF_END / a) / EFOLDS) * (INF_END - INF_START));
    if (a <= A_LOW) return tRad(a);
    if (a > 1) return AGE + tML(a) - TML1;
    const x = Math.min(N - 1e-9, (Math.log(a) - LN_LO) / DL), i = Math.floor(x);
    return Math.exp(LN_T[i] + (x - i) * (LN_T[i + 1] - LN_T[i]));
  }
  const timeOfRedshift = (z) => cosmicTime(1 / (1 + z));

  const A_ACC = Math.cbrt(Om / (2 * Ol));
  const A_LAMBDA_EQ = Math.cbrt(Om / Ol);

  function lnScaleFactor(t) {
    if (t <= AGE) return Math.log(scaleFactor(t));
    const x = 1.5 * hL * (t - AGE + TML1);
    return (2 / 3) * (x + Math.log((1 - Math.exp(-2 * x)) / 2) - Math.log(RATIO));
  }

  function scaleFactor(t) {
    if (t < INF_END) return A_INF_END * Math.exp((-EFOLDS * (INF_END - t)) / (INF_END - INF_START));
    if (t <= tRad(A_LOW)) return Math.sqrt(2 * h0 * Math.sqrt(Or) * t);
    if (t > AGE) return Math.exp(lnScaleFactor(t));
    if (t === AGE) return 1;
    const lt = Math.log(t);
    let lo = 0, hi = N;
    while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (LN_T[mid] <= lt) lo = mid; else hi = mid; }
    const f = (lt - LN_T[lo]) / (LN_T[hi] - LN_T[lo]);
    return Math.exp(LN_LO + (lo + f) * DL);
  }

  const temperature = (a) => T_CMB0 / a;
  // Hawking-like temperature of the de Sitter horizon, hbar H_L / (2 pi k_B): the floor the
  // universe cools toward once the CMB has redshifted away.
  const T_DE_SITTER = (1.054571817e-34 * hL) / (2 * Math.PI * 1.380649e-23);
  const fromNow = (yr) => AGE + yr * YEAR;

  function densityFractions(a) {
    const r = Or / a ** 4, m = Om / a ** 3, total = r + m + Ol;
    return {
      radiation: r / total, matter: m / total, dark_matter: (m * (1 - Ob / Om)) / total,
      ordinary_matter: (m * Ob / Om) / total, dark_energy: Ol / total,
    };
  }

  // Deceleration parameter q = -a a'' / a'^2 = Omega_r + Omega_m / 2 - Omega_Lambda (shares at a):
  // positive while gravity slows the expansion, negative once dark energy speeds it up.
  function deceleration(a) {
    const f = densityFractions(a);
    return f.radiation + f.matter / 2 - f.dark_energy;
  }

  const ERAS = [
    ["inflation", INF_START, "Pure energy: inflation",
      "Space doubles in size every ~10⁻³⁴ s. All the energy is in the inflaton field; there are no particles yet."],
    ["radiation", INF_END, "Pure energy: radiation soup",
      "Inflation ends and its energy turns into a hot bath of massless particles moving at light speed."],
    ["quark_gluon_plasma", 1e-12, "Quark–gluon plasma",
      "The Higgs field switches on and particles gain mass, but it is too hot for quarks to bind together."],
    ["hadrons", 1e-5, "Particles coalesce: protons and neutrons",
      "The plasma cools enough for quarks to lock into protons and neutrons."],
    ["leptons", 1, "Neutrinos break free",
      "Neutrinos stop interacting and stream away; electrons and positrons annihilate into light."],
    ["nuclei", 180, "Nuclei form: hydrogen, helium, lithium",
      "Protons and neutrons fuse into the first nuclei. After ~20 minutes it is too cool and thin to continue."],
    ["opaque_plasma", 1200, "Opaque plasma",
      "Nuclei, free electrons and light. Photons scatter off electrons constantly, so the universe is a glowing fog."],
    ["transparent", timeOfRedshift(1300), "Transparent: atoms form, CMB released",
      "Electrons join nuclei to make neutral atoms. Light travels freely; that light is the cosmic microwave background."],
    ["dark_ages", timeOfRedshift(800), "Dark ages",
      "Neutral hydrogen and dark matter, no stars. Gravity slowly pulls the denser spots together."],
    ["first_stars", 1e8 * YEAR, "First stars light up",
      "Gas collapses in dark-matter clumps and ignites the first stars, whose light re-ionizes the hydrogen."],
    ["galaxies", 1e9 * YEAR, "Galaxies grow and merge",
      "Galaxies build up along a web of dark-matter filaments. Star formation peaks around 3 billion years."],
    ["dark_energy", cosmicTime(A_LAMBDA_EQ), "Dark energy takes over",
      "Matter has thinned out enough that dark energy dominates, and the expansion is speeding up."],
    ["alone", fromNow(1.5e11), "Alone: other galaxies pass out of view",
      "Expansion has carried every galaxy beyond the merged Milky Way and Andromeda past our horizon. Space doubles in size every 12 billion years."],
    ["last_stars", fromNow(1e14), "The last stars burn out",
      "The gas is used up and the last red dwarfs fade. White dwarfs, neutron stars and black holes are all that remain."],
    ["black_holes", fromNow(1e20), "Black holes rule",
      "Close encounters have flung most dead stars out of the galaxy, and the rest have fallen into its central black hole."],
    ["empty", fromNow(1e100), "Empty space, expanding forever",
      "The last black holes have evaporated into faint Hawking radiation. Nearly empty de Sitter space keeps expanding, at about 2 × 10⁻³⁰ K."],
  ].map(([id, start, label, detail]) => ({ id, start, label, detail }));

  function era(t) {
    let cur = ERAS[0];
    // Tolerance so an event placed on an era boundary survives the round trip through log10(t).
    for (const e of ERAS) if (t >= e.start * (1 - 1e-12)) cur = e;
    return cur;
  }

  const EVENTS = [
    { id: "inflation", t: 1e-34, name: "Inflation", status: "partial",
      predicted: "Space is flat; ripples are nearly the same strength on all scales (n_s just below 1); a faint background of primordial gravitational waves.",
      evidence: "Planck: curvature Ω_k = 0.001 ± 0.002 and n_s = 0.965 ± 0.004, as predicted. The gravitational-wave B-mode signal has not been detected (BICEP/Keck 2021: r < 0.036)." },
    { id: "higgs", t: 1e-12, name: "Electroweak symmetry breaking", status: "lab",
      predicted: "The Higgs field gives the W and Z bosons and the fermions mass, and there is a Higgs boson.",
      evidence: "Higgs boson found at the LHC by ATLAS and CMS (2012). The cosmic transition itself cannot be observed directly." },
    { id: "qgp", t: 1e-5, name: "Quarks bind into protons and neutrons", status: "lab",
      predicted: "Above ~2 trillion K, quarks and gluons form a free plasma.",
      evidence: "Quark–gluon plasma recreated in heavy-ion collisions at RHIC (announced 2005) and the LHC." },
    { id: "neutrinos", t: 1, name: "Neutrino decoupling", status: "partial",
      predicted: "A cosmic neutrino background, now at 1.95 K, adding N_eff = 3.044 species' worth of radiation.",
      evidence: "Planck infers N_eff = 2.99 ± 0.17 from the CMB. The relic neutrinos themselves have never been detected directly." },
    { id: "bbn", t: 180, name: "Big Bang nucleosynthesis", status: "partial",
      predicted: "About 25% of ordinary mass in helium-4, deuterium D/H ≈ 2.5 × 10⁻⁵, and a trace of lithium-7.",
      evidence: "Helium and deuterium measured in pristine gas match. Lithium-7 in old stars is about 3× lower than predicted (the lithium problem)." },
    { id: "equality", t: timeOfRedshift(3400), name: "Matter overtakes radiation", status: "verified",
      predicted: "Matter outweighs radiation from z ≈ 3400, setting the shape of the CMB peaks and the galaxy power spectrum.",
      evidence: "Planck measures z_eq = 3402 ± 26." },
    { id: "cmb", t: timeOfRedshift(1090), name: "Cosmic microwave background", status: "verified",
      predicted: "Light released at z ≈ 1090, now cooled to a few kelvin (Alpher and Herman, 1948: about 5 K), a perfect blackbody with 1-in-100,000 ripples and acoustic peaks.",
      evidence: "Found by Penzias and Wilson (1965). COBE (1990–92) measured a 2.725 K blackbody to 50 parts per million and detected the ripples. WMAP (2003) and Planck (2013–18) mapped the acoustic peaks, fit by ΛCDM with six parameters." },
    { id: "bao", t: timeOfRedshift(1060), name: "Baryon acoustic oscillations", status: "verified",
      predicted: "Sound waves frozen in at this moment leave a preferred ~150 Mpc spacing between galaxies.",
      evidence: "Detected in galaxy clustering by SDSS and 2dFGRS (2005). DESI (2024) measures it to sub-percent precision." },
    { id: "darkmatter", t: timeOfRedshift(100), name: "Dark matter seeds structure", status: "partial",
      predicted: "Cold matter that does not interact with light, about 5.4 times the ordinary matter. Its clumps kept growing while the plasma was opaque, so once the CMB is released the gas falls into them and galaxies can form in time.",
      evidence: "Cluster motions (Zwicky, 1933), galaxy rotation curves (Rubin and Ford, 1970s), gravitational lensing and the Bullet Cluster (2006), and the CMB's third peak (Planck: Ω_c h² = 0.120 ± 0.001). What it is remains unknown: no dark-matter particle has been detected (LZ, XENONnT, LHC)." },
    { id: "firststars", t: 1.5e8 * YEAR, name: "First stars", status: "partial",
      predicted: "Population III stars of pure hydrogen and helium form in dark-matter halos about 100–200 million years in.",
      evidence: "JWST sees galaxies by ~290 million years (JADES-GS-z14-0, z = 14.3), brighter than many models expected. Pure Population III stars are not yet confirmed." },
    { id: "21cm", t: timeOfRedshift(17), name: "Cosmic-dawn 21 cm signal", status: "untested",
      predicted: "Once the first stars shine, cold hydrogen absorbs CMB light at 21 cm, leaving a faint radio dip now near 78 MHz.",
      evidence: "EDGES (2018) reported a dip deeper than predicted; SARAS-3 (2022) did not confirm it. Not settled." },
    { id: "reionization", t: timeOfRedshift(6), name: "Reionization complete", status: "verified",
      predicted: "Starlight re-ionizes the intergalactic hydrogen by z ≈ 6.",
      evidence: "Gunn–Peterson trough in distant quasars (SDSS, 2001); Planck optical depth τ = 0.054 ± 0.007." },
    { id: "web", t: timeOfRedshift(2), name: "Cosmic web and cosmic noon", status: "verified",
      predicted: "Cold dark matter gathers galaxies into filaments and clusters; star formation peaks near z ≈ 2.",
      evidence: "Galaxy surveys (2dF, SDSS) map the cosmic web, and the star-formation history peaks near z ≈ 2. Weak lensing finds slightly less clumping than predicted (the S₈ tension)." },
    { id: "acceleration", t: cosmicTime(A_ACC), name: "Expansion starts accelerating", status: "verified",
      predicted: `With Ω_Λ ≈ 0.69 the expansion starts speeding up at z ≈ ${(1 / A_ACC - 1).toFixed(2)}.`,
      evidence: "Type Ia supernovae (Riess et al. and Perlmutter et al., 1998–99; Nobel Prize 2011), since confirmed by BAO and the CMB." },
    { id: "today", t: AGE, name: "Today", status: "partial",
      predicted: `Age ${(AGE / YEAR / 1e9).toFixed(2)} billion years and H₀ = 67.4 km/s/Mpc, fixed by early-universe data.`,
      evidence: "The oldest stars (~12–13 billion years) fit inside this age. Local Cepheid and supernova distances give H₀ ≈ 73 km/s/Mpc, a >4σ disagreement (the Hubble tension)." },
    { id: "andromeda", t: fromNow(4.5e9), name: "Milky Way and Andromeda collide", status: "future",
      predicted: "Andromeda, 2.5 million light-years away and closing at 110 km/s, falls into the Milky Way; the pair merge into one elliptical galaxy. This is local gravity, not cosmology: the Local Group is bound and does not expand.",
      evidence: "Hubble and Gaia proper motions put the first close pass about 4–5 billion years ahead. A 2025 study (Sawala et al.) gives roughly even odds of a full merger within 10 billion years." },
    { id: "horizon", t: fromNow(1.5e11), name: "Other galaxies leave our view", status: "future",
      predicted: "With constant dark energy, every galaxy outside the Local Group is carried beyond our cosmic event horizon; its light fades and redshifts until it cannot be seen (Loeb 2002; Krauss and Scherrer 2007).",
      evidence: "Follows from the measured Ω_Λ ≈ 0.69 and w ≈ −1. If dark energy evolves, as DESI (2024–25) hints, the timing and even the outcome could change." },
    { id: "cmbfade", t: fromNow(1e12), name: "The CMB fades out", status: "future",
      predicted: "Space has grown about 10²⁴ times since today. The CMB has cooled to ~10⁻²⁴ K, with waves longer than a galaxy, so observers then could not detect it.",
      evidence: "A direct consequence of T = T₀/a and the measured expansion. Observers inside the merged galaxy would see no evidence of the Big Bang." },
    { id: "laststars", t: fromNow(1e14), name: "The last stars die", status: "future",
      predicted: "Star formation ends as gas runs out; the longest-lived red dwarfs burn out after about 10¹⁴ years (Adams and Laughlin 1997).",
      evidence: "Based on observed star-formation decline and stellar-evolution models; red dwarfs are the commonest stars today." },
    { id: "evaporate", t: fromNow(1e19), name: "The galaxy evaporates", status: "future",
      predicted: "Over ~10¹⁹ years, chance close passes kick most stellar remnants out of the galaxy; the rest spiral into the central black hole.",
      evidence: "Standard stellar-dynamics estimate. It assumes protons are stable; if protons decay (not yet seen, lifetime > 10³⁴ years), the remnants dissolve too." },
    { id: "stellarbh", t: fromNow(1e67), name: "Stellar black holes evaporate", status: "future",
      predicted: "A black hole of one solar mass loses its mass to Hawking radiation in about 10⁶⁷ years, once the CMB is colder than it is.",
      evidence: "Hawking radiation has never been seen from a real black hole; analogue versions have been made in the lab (Steinhauer, 2016–19)." },
    { id: "lastbh", t: fromNow(1e100), name: "The last black holes evaporate", status: "future",
      predicted: "The biggest supermassive black holes, ~10¹¹ solar masses, take up to ~10¹⁰⁰ years. Afterwards the universe is almost empty space at the de Sitter temperature, about 2 × 10⁻³⁰ K.",
      evidence: "Untestable; the final de Sitter state follows from a true cosmological constant." },
  ];

  const api = {
    YEAR, T_CMB0, Ob, T_DE_SITTER, AGE, A_LOW, A_ACC, A_LAMBDA_EQ, A_INF_END, INF_START, INF_END, Om, Or, Ol,
    hubble, cosmicTime, timeOfRedshift, scaleFactor, lnScaleFactor, fromNow, temperature, densityFractions, deceleration, era, ERAS, EVENTS,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.Cosmos = api;
})(this);
