// WebGL2 layer for the real sky: a CMB map wrapped on the sphere, Hubble Ultra Deep Field
// galaxies as sprites tangent to it, and the limb darkening on top. Textures come from
// viewer/assets/*.js as data: URIs, so the page still works from file://.
// app.js falls back to its procedural 2D drawing when WebGL2 is unavailable.
(function (root) {
  const QUAD_VS = `#version 300 es
  in vec2 pos; void main() { gl_Position = vec4(pos, 0.0, 1.0); }`;

  // uInv is the view rotation M uploaded row-major, so GLSL sees M^T: uInv * view = world.
  const SPHERE_FS = `#version 300 es
  precision highp float;
  uniform vec2 uC; uniform float uR, uH, uAlpha, uWarm, uContrast; uniform mat3 uInv; uniform int uLimb;
  uniform sampler2D uTex;
  out vec4 o;
  vec3 planck(float v) {
    const vec3 c0 = vec3(0.00, 0.00, 1.00), c1 = vec3(0.00, 0.44, 1.00), c2 = vec3(0.00, 0.87, 1.00);
    const vec3 c3 = vec3(1.00, 0.93, 0.85), c4 = vec3(1.00, 0.71, 0.12), c5 = vec3(1.00, 0.29, 0.00);
    const vec3 c6 = vec3(0.39, 0.00, 0.00);
    float x = clamp(v * 3.0 + 3.0, 0.0, 6.0);
    if (x < 1.0) return mix(c0, c1, x);
    if (x < 2.0) return mix(c1, c2, x - 1.0);
    if (x < 3.0) return mix(c2, c3, x - 2.0);
    if (x < 4.0) return mix(c3, c4, x - 3.0);
    if (x < 5.0) return mix(c4, c5, x - 4.0);
    return mix(c5, c6, x - 5.0);
  }
  void main() {
    vec2 q = (vec2(gl_FragCoord.x, uH - gl_FragCoord.y) - uC) / uR;
    float r = length(q);
    if (r >= 1.0) discard;
    float edge = clamp((1.0 - r) * uR, 0.0, 1.0);
    if (uLimb == 1) { float a = 0.55 * smoothstep(0.55, 1.0, r) * edge; o = vec4(0.0, 0.0, 0.0, a); return; }
    float z = sqrt(1.0 - r * r);
    vec3 p = uInv * vec3(q, z);
    vec2 uv = vec2(0.5 + atan(p.x, p.z) / 6.2831853, 0.5 - asin(clamp(-p.y, -1.0, 1.0)) / 3.1415927);
    vec2 uv2 = vec2(fract(uv.x + 0.5), uv.y);
    vec2 dx = dFdx(uv), dy = dFdy(uv), dx2 = dFdx(uv2), dy2 = dFdy(uv2);
    if (abs(dx2.x) + abs(dy2.x) < abs(dx.x) + abs(dy.x)) { dx = dx2; dy = dy2; }
    float v = (textureGrad(uTex, uv, dx, dy).r * 2.0 - 1.0) * uContrast;
    // Freshly released light is a ~3000 K orange glow with slightly hotter and cooler patches;
    // later it is shown in the usual exaggerated map colours.
    vec3 warm = mix(vec3(0.62, 0.17, 0.06), vec3(1.0, 0.86, 0.45), 0.5 + 0.5 * v);
    float a = uAlpha * (0.5 + 0.5 * z) * edge;
    o = vec4(mix(planck(v), warm, uWarm) * a, a);
  }`;

  const GAL_VS = `#version 300 es
  in vec3 aP, aE1, aE2; in vec2 aCorner; in vec4 aUV; in vec3 aBorn;
  uniform mat3 uInv; uniform vec2 uC, uView; uniform float uR, uLogt, uZnow, uFade;
  out vec2 vUV; out float vA, vShift;
  void main() {
    vec3 v = (aP + aCorner.x * aE1 + aCorner.y * aE2) * uInv;
    vec2 s = uC + uR * v.xy;
    gl_Position = vec4(2.0 * s.x / uView.x - 1.0, 1.0 - 2.0 * s.y / uView.y, 0.0, 1.0);
    float zc = (aP * uInv).z;
    vA = smoothstep(aBorn.x - 0.03, aBorn.x + 0.03, uLogt) * smoothstep(0.0, 0.3, zc) * aBorn.z * uFade;
    // At the moment its light left it the galaxy looks as Hubble saw it; nearer the present
    // the cosmological redshift shrinks, so the colours shift back toward blue.
    float zg = aBorn.y;
    vShift = (1.0 - clamp(uZnow / zg, 0.0, 1.0)) * clamp(log(1.0 + zg) / log(4.0), 0.0, 1.0);
    vUV = aUV.xy + (aCorner * 0.5 + 0.5) * aUV.zw;
  }`;
  const GAL_FS = `#version 300 es
  precision mediump float;
  uniform sampler2D uTex;
  in vec2 vUV; in float vA, vShift;
  out vec4 o;
  void main() {
    vec3 c = max(texture(uTex, vUV).rgb - 0.05, 0.0) * 1.05;
    float f = 0.65 * vShift;
    c = vec3(mix(c.r, c.g, f), mix(c.g, c.b, f), mix(c.b, max(c.b, c.r), f)) * vA;
    o = vec4(c, max(c.r, max(c.g, c.b)));
  }`;

  function program(gl, vs, fs) {
    const p = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
      const s = gl.createShader(type);
      gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      gl.attachShader(p, s);
    }
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const u = {}, n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (let i = 0; i < n; i++) { const name = gl.getActiveUniform(p, i).name; u[name] = gl.getUniformLocation(p, name); }
    return { p, u };
  }

  function texture(gl, src, grey, onload) {
    const tex = gl.createTexture(), img = new Image();
    img.onload = () => {
      const max = gl.getParameter(gl.MAX_TEXTURE_SIZE);
      let src = img;
      if (img.width > max || img.height > max) {
        const k = max / Math.max(img.width, img.height);
        src = document.createElement("canvas");
        src.width = Math.floor(img.width * k); src.height = Math.floor(img.height * k);
        src.getContext("2d").drawImage(img, 0, 0, src.width, src.height);
      }
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
      if (grey) gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, gl.RED, gl.UNSIGNED_BYTE, src);
      else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, src);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, grey ? gl.REPEAT : gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      const aniso = gl.getExtension("EXT_texture_filter_anisotropic");
      if (aniso) gl.texParameterf(gl.TEXTURE_2D, aniso.TEXTURE_MAX_ANISOTROPY_EXT, 8);
      onload(tex);
    };
    img.src = src;
  }

  // placements: [{ p, e1, e2, uv: [x, y, w, h] (0..1), born, z, gain }]
  function create(canvas, assets, placements) {
    const gl = canvas.getContext("webgl2", { premultipliedAlpha: true, antialias: true });
    if (!gl || !assets) return null;
    let sphere, gal;
    try { sphere = program(gl, QUAD_VS, SPHERE_FS); gal = program(gl, GAL_VS, GAL_FS); } catch (e) { console.warn(e); return null; }

    const quad = gl.createVertexArray();
    gl.bindVertexArray(quad);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const lp = gl.getAttribLocation(sphere.p, "pos");
    gl.enableVertexAttribArray(lp); gl.vertexAttribPointer(lp, 2, gl.FLOAT, false, 0, 0);

    // Galaxy quads, two triangles each: 3+3+3+2+4+3 = 18 floats per vertex.
    const corners = [[-1, -1], [1, -1], [1, 1], [-1, -1], [1, 1], [-1, 1]], F = 18;
    const data = new Float32Array(placements.length * 6 * F);
    let k = 0;
    for (const g of placements) for (const c of corners) {
      data.set([...g.p, ...g.e1, ...g.e2, ...c, ...g.uv, g.born, g.z, g.gain], k); k += F;
    }
    const galVao = gl.createVertexArray();
    gl.bindVertexArray(galVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
    let off = 0;
    for (const [name, size] of [["aP", 3], ["aE1", 3], ["aE2", 3], ["aCorner", 2], ["aUV", 4], ["aBorn", 3]]) {
      const loc = gl.getAttribLocation(gal.p, name);
      gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, size, gl.FLOAT, false, F * 4, off * 4);
      off += size;
    }
    gl.bindVertexArray(null);

    const maps = {};
    let atlas = null;
    if (assets.hudf_galaxies) texture(gl, assets.hudf_galaxies.src, false, (t) => (atlas = t));
    function map(key) {
      if (!maps[key] && assets[key]) { maps[key] = { tex: null }; texture(gl, assets[key].src, true, (t) => (maps[key].tex = t)); }
      return maps[key] && maps[key].tex;
    }

    // s: { W, H (device px), cx, cy, R (device px), M (row-major 3x3), map, cmb, warm, contrast, logt, zNow }
    function draw(s) {
      if (canvas.width !== s.W || canvas.height !== s.H) { canvas.width = s.W; canvas.height = s.H; }
      gl.viewport(0, 0, s.W, s.H);
      gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
      gl.enable(gl.BLEND);
      const M = new Float32Array(s.M);

      gl.useProgram(sphere.p); gl.bindVertexArray(quad);
      gl.uniform2f(sphere.u.uC, s.cx, s.cy); gl.uniform1f(sphere.u.uR, s.R); gl.uniform1f(sphere.u.uH, s.H);
      gl.uniformMatrix3fv(sphere.u.uInv, false, M);
      const tex = s.cmb > 0.002 ? map(s.map) : null;
      if (tex) {
        gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.uniform1i(sphere.u.uTex, 0); gl.uniform1i(sphere.u.uLimb, 0); gl.uniform1f(sphere.u.uAlpha, s.cmb);
        gl.uniform1f(sphere.u.uWarm, s.warm); gl.uniform1f(sphere.u.uContrast, s.contrast);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      }
      if (atlas && placements.length && s.logt > placements.minBorn - 0.05 && s.fade > 0.002) {
        gl.useProgram(gal.p); gl.bindVertexArray(galVao);
        gl.blendFuncSeparate(gl.ONE, gl.ONE, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, atlas);
        gl.uniform1i(gal.u.uTex, 0);
        gl.uniformMatrix3fv(gal.u.uInv, false, M);
        gl.uniform2f(gal.u.uC, s.cx, s.cy); gl.uniform2f(gal.u.uView, s.W, s.H);
        gl.uniform1f(gal.u.uR, s.R); gl.uniform1f(gal.u.uLogt, s.logt); gl.uniform1f(gal.u.uZnow, s.zNow);
        gl.uniform1f(gal.u.uFade, s.fade);
        gl.drawArrays(gl.TRIANGLES, 0, placements.length * 6);
      }
      gl.useProgram(sphere.p); gl.bindVertexArray(quad);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.uniform1i(sphere.u.uLimb, 1);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }
    return { draw, map };
  }

  root.SkyGL = { create };
})(this);
