(function() {
  "use strict";

  var canvas = document.getElementById('c');
  var gl = canvas.getContext('webgl', { alpha: false, antialias: true })
        || canvas.getContext('experimental-webgl', { alpha: false, antialias: true });
  if (!gl) return;

  // ── Constants (match GalaxyScene.java) ──
  var PI = 3.1415, TWO_PI = 6.283;
  var GALAXY_RADIUS = 300;
  var ELLIPSE_RATIO = 0.892;
  var PARTICLE_COUNT = 12000;
  var ALPHA_MULTIPLIER = 1.0;

  // ── State ──
  var width, height;
  var xOffset = 0.5;
  var bgProgram, particleProgram, lightProgram;
  var texBg, texFlare, texLight;
  var particlePosBuf, particleColorBuf;
  var bgBuf, lightBuf;
  var projMatrix = new Float32Array(16);
  var particlePositions, particleColors, particleSpeeds;
  var animId;

  // ── Matrix helpers ──
  function mat4Identity(out) {
    out.fill(0); out[0]=out[5]=out[10]=out[15]=1; return out;
  }

  function mat4Multiply(out, a, b) {
    for (var i=0;i<4;i++) {
      var ai0=a[i], ai1=a[i+4], ai2=a[i+8], ai3=a[i+12];
      out[i]=ai0*b[0]+ai1*b[1]+ai2*b[2]+ai3*b[3];
      out[i+4]=ai0*b[4]+ai1*b[5]+ai2*b[6]+ai3*b[7];
      out[i+8]=ai0*b[8]+ai1*b[9]+ai2*b[10]+ai3*b[11];
      out[i+12]=ai0*b[12]+ai1*b[13]+ai2*b[14]+ai3*b[15];
    }
    return out;
  }

  function mat4Frustum(out, l, r, b, t, n, f) {
    out.fill(0);
    out[0]=2*n/(r-l); out[5]=2*n/(t-b);
    out[8]=(r+l)/(r-l); out[9]=(t+b)/(t-b);
    out[10]=-(f+n)/(f-n); out[11]=-1;
    out[14]=-2*f*n/(f-n);
    return out;
  }

  function mat4Translate(out, m, v) {
    for (var i=0;i<16;i++) out[i]=m[i];
    out[12]+=v[0]; out[13]+=v[1]; out[14]+=v[2];
    return out;
  }

  function mat4Scale(out, m, v) {
    for (var i=0;i<16;i++) out[i]=m[i];
    out[0]*=v[0]; out[5]*=v[1]; out[10]*=v[2];
    return out;
  }

  function mat4RotateX(out, m, rad) {
    var s=Math.sin(rad), c=Math.cos(rad);
    var m4=m[4],m5=m[5],m6=m[6],m7=m[7],m8=m[8],m9=m[9],m10=m[10],m11=m[11];
    for (var i=0;i<16;i++) out[i]=m[i];
    out[4]=m4*c+m8*s; out[5]=m5*c+m9*s; out[6]=m6*c+m10*s; out[7]=m7*c+m11*s;
    out[8]=m8*c-m4*s; out[9]=m9*c-m5*s; out[10]=m10*c-m6*s; out[11]=m11*c-m7*s;
    return out;
  }

  function mat4RotateY(out, m, rad) {
    var s=Math.sin(rad), c=Math.cos(rad);
    var m0=m[0],m1=m[1],m2=m[2],m3=m[3],m8=m[8],m9=m[9],m10=m[10],m11=m[11];
    for (var i=0;i<16;i++) out[i]=m[i];
    out[0]=m0*c-m8*s; out[1]=m1*c-m9*s; out[2]=m2*c-m10*s; out[3]=m3*c-m11*s;
    out[8]=m0*s+m8*c; out[9]=m1*s+m9*c; out[10]=m2*s+m10*c; out[11]=m3*s+m11*c;
    return out;
  }

  function mat4Rotate(out, m, rad, x, y, z) {
    var len=Math.sqrt(x*x+y*y+z*z);
    if (len<1e-6) { for (var i=0;i<16;i++) out[i]=m[i]; return out; }
    x/=len; y/=len; z/=len;
    var s=Math.sin(rad), c=Math.cos(rad), t=1-c;
    var r00=x*x*t+c, r01=x*y*t-z*s, r02=x*z*t+y*s;
    var r10=y*x*t+z*s, r11=y*y*t+c, r12=y*z*t-x*s;
    var r20=z*x*t-y*s, r21=z*y*t+x*s, r22=z*z*t+c;
    var m0=m[0],m1=m[1],m2=m[2],m3=m[3],m4=m[4],m5=m[5],m6=m[6],m7=m[7],m8=m[8],m9=m[9],m10=m[10],m11=m[11],m12=m[12],m13=m[13],m14=m[14],m15=m[15];
    out[0]=m0*r00+m4*r01+m8*r02; out[1]=m1*r00+m5*r01+m9*r02; out[2]=m2*r00+m6*r01+m10*r02; out[3]=m3*r00+m7*r01+m11*r02;
    out[4]=m0*r10+m4*r11+m8*r12; out[5]=m1*r10+m5*r11+m9*r12; out[6]=m2*r10+m6*r11+m10*r12; out[7]=m3*r10+m7*r11+m11*r12;
    out[8]=m0*r20+m4*r21+m8*r22; out[9]=m1*r20+m5*r21+m9*r22; out[10]=m2*r20+m6*r21+m10*r22; out[11]=m3*r20+m7*r21+m11*r22;
    out[12]=m12; out[13]=m13; out[14]=m14; out[15]=m15;
    return out;
  }

  // ── Random (seeded simple) ──
  function gaussRandom() {
    var x1, x2, w = 2;
    while (w >= 1) {
      x1 = Math.random() * 2 - 1;
      x2 = Math.random() * 2 - 1;
      w = x1 * x1 + x2 * x2;
    }
    w = Math.sqrt(-2 * Math.log(w) / w);
    return x1 * w;
  }

  function mapf(minStart, minStop, maxStart, maxStop, value) {
    return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
  }

  function buildParticles() {
    var halfWidth = Math.max(1, width * 0.5);
    var scale = GALAXY_RADIUS / halfWidth;
    var pos = new Float32Array(PARTICLE_COUNT * 3);
    var col = new Float32Array(PARTICLE_COUNT * 4);
    var spd = new Float32Array(PARTICLE_COUNT);

    for (var i = 0; i < PARTICLE_COUNT; i++) {
      var d = Math.abs(gaussRandom()) * GALAXY_RADIUS * 0.5 + Math.random() * 64;
      var id = d / GALAXY_RADIUS;
      var z = gaussRandom() * 0.4 * (1 - id);

      var ci = i * 4;
      if (d < GALAXY_RADIUS * 0.33) {
        col[ci] = (220 + id * 35) / 255;
        col[ci + 1] = 220 / 255;
        col[ci + 2] = 220 / 255;
      } else {
        col[ci] = 180 / 255;
        col[ci + 1] = 180 / 255;
        col[ci + 2] = Math.min(140 + id * 115, 255) / 255;
      }
      col[ci + 3] = (Math.random() * 0.9 + 1.2) * 6.0;

      if (d > GALAXY_RADIUS * 0.15) {
        z *= 0.6 * (1 - id);
      } else {
        z *= 0.72;
      }

      var mappedDist = mapf(-4, GALAXY_RADIUS + 4, 0, scale, d);
      var angle = Math.random() * TWO_PI;

      var pi = i * 3;
      pos[pi] = angle;
      pos[pi + 1] = mappedDist;
      pos[pi + 2] = z / 5;

      spd[i] = (Math.random() * 0.001 + 0.0015) * (0.5 + scale / mappedDist) * 0.8;
    }

    return { pos: pos, col: col, spd: spd };
  }

  // ── Shaders ──
  function createShader(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      console.error('Shader error: ' + gl.getShaderInfoLog(s));
      gl.deleteShader(s); return null;
    }
    return s;
  }

  function createProgram(vsSrc, fsSrc) {
    var vs = createShader(gl.VERTEX_SHADER, vsSrc);
    var fs = createShader(gl.FRAGMENT_SHADER, fsSrc);
    if (!vs || !fs) return 0;
    var p = gl.createProgram();
    gl.attachShader(p, vs); gl.attachShader(p, fs);
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      console.error('Link error: ' + gl.getProgramInfoLog(p));
      gl.deleteProgram(p); return 0;
    }
    return p;
  }

  function initShaders() {
    bgProgram = createProgram(
      'attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vTexCoord = aTexCoord; }',
      'precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture; void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }'
    );

    particleProgram = createProgram(
      'uniform mat4 uMVPMatrix; uniform float uAlphaMultiplier;' +
      'attribute vec3 aPosition; attribute vec4 aColor; varying vec4 vColor;' +
      'void main() {' +
      '  float dist = aPosition.y; float angle = aPosition.x;' +
      '  float x = dist * sin(angle); float y = dist * cos(angle) * ' + ELLIPSE_RATIO.toFixed(6) + ';' +
      '  float p = dist * 5.5; float s = cos(p); float t = sin(p);' +
      '  vec4 pos;' +
      '  pos.x = t * x + s * y;' +
      '  pos.y = s * x - t * y;' +
      '  pos.z = aPosition.z; pos.w = 1.0;' +
      '  gl_Position = uMVPMatrix * pos;' +
      '  gl_PointSize = aColor.a;' +
      '  vColor.rgb = aColor.rgb; vColor.a = uAlphaMultiplier;' +
      '}',
      'precision mediump float; uniform sampler2D uTexture; varying vec4 vColor;' +
      'void main() { vec4 t = texture2D(uTexture, gl_PointCoord); gl_FragColor = vColor * t; }'
    );

    lightProgram = createProgram(
      'uniform mat4 uMVPMatrix; attribute vec3 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord;' +
      'void main() { gl_Position = uMVPMatrix * vec4(aPosition, 1.0); vTexCoord = aTexCoord; }',
      'precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture;' +
      'void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }'
    );
  }

  // ── Textures ──
  function loadTexture(url, callback) {
    var tex = gl.createTexture();
    var img = new Image();
    img.onload = function() {
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
      if (callback) callback();
    };
    img.src = url;
    return tex;
  }

  // ── Update projection (matches updateProjectionMatrix) ──
  function updateProj() {
    var tmpA = new Float32Array(16);
    var tmpB = new Float32Array(16);
    if (width > height) {
      var aspect = width / Math.max(1, height);
      mat4Frustum(tmpA, -aspect, aspect, -1, 1, 1, 100);
    } else {
      var aspect = height / Math.max(1, width);
      mat4Frustum(tmpA, -1, 1, -aspect, aspect, 1, 100);
    }
    mat4RotateY(tmpB, mat4Identity(new Float32Array(16)), Math.PI); // 180 around Y
    mat4Multiply(tmpA, tmpA, tmpB);
    mat4Scale(tmpA, tmpA, [-2, 2, 1]);
    for (var i=0;i<16;i++) projMatrix[i]=tmpA[i];
  }

  // ── Calc MVP (matches calcMatrix) ──
  function calcMVP(out, offset) {
    var angle = 50;
    var a = offset * angle;
    var absA = Math.abs(a);
    var wide = width > height;

    var m = mat4Identity(new Float32Array(16));
    mat4Translate(m, m, [0, 0, 10 - 6 * absA / 50]);
    mat4Scale(m, m, wide ? [12.6, 12.0, 1] : [6.6, 6.0, 1]);
    mat4RotateX(m, m, absA * (Math.PI / 180));
    mat4Rotate(m, m, a * (Math.PI / 180), 0, 0.4, 0.1);
    mat4Multiply(out, projMatrix, m);
  }

  // ── Resize ──
  function resize() {
    var dpr = window.devicePixelRatio || 1;
    width = window.innerWidth;
    height = window.innerHeight;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    canvas.style.width = width + 'px';
    canvas.style.height = height + 'px';
    gl.viewport(0, 0, canvas.width, canvas.height);
    updateProj();
  }

  function uploadParticleColorBuffer() {
    if (!particleColorBuf) particleColorBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, particleColorBuf);
    gl.bufferData(gl.ARRAY_BUFFER, particleColors, gl.STATIC_DRAW);
  }

  // ── Draw ──
  function drawBackground() {
    gl.useProgram(bgProgram);
    var fsv = new Float32Array([-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0]);
    if (!bgBuf) bgBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, bgBuf);
    gl.bufferData(gl.ARRAY_BUFFER, fsv, gl.DYNAMIC_DRAW);

    var posL = gl.getAttribLocation(bgProgram, 'aPosition');
    var texL = gl.getAttribLocation(bgProgram, 'aTexCoord');
    gl.enableVertexAttribArray(posL);
    gl.enableVertexAttribArray(texL);
    gl.vertexAttribPointer(posL, 2, gl.FLOAT, false, 16, 0);
    gl.vertexAttribPointer(texL, 2, gl.FLOAT, false, 16, 8);

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texBg);
    gl.uniform1i(gl.getUniformLocation(bgProgram, 'uTexture'), 0);

    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.disableVertexAttribArray(posL);
    gl.disableVertexAttribArray(texL);
  }

  function drawParticles(mvp) {
    gl.useProgram(particleProgram);

    if (!particlePosBuf) particlePosBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, particlePosBuf);
    gl.bufferData(gl.ARRAY_BUFFER, particlePositions, gl.DYNAMIC_DRAW);
    var posL = gl.getAttribLocation(particleProgram, 'aPosition');
    gl.enableVertexAttribArray(posL);
    gl.vertexAttribPointer(posL, 3, gl.FLOAT, false, 0, 0);

    gl.bindBuffer(gl.ARRAY_BUFFER, particleColorBuf);
    var colL = gl.getAttribLocation(particleProgram, 'aColor');
    gl.enableVertexAttribArray(colL);
    gl.vertexAttribPointer(colL, 4, gl.FLOAT, false, 0, 0);

    gl.uniformMatrix4fv(gl.getUniformLocation(particleProgram, 'uMVPMatrix'), false, mvp);
    gl.uniform1f(gl.getUniformLocation(particleProgram, 'uAlphaMultiplier'), ALPHA_MULTIPLIER);

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texFlare);
    gl.uniform1i(gl.getUniformLocation(particleProgram, 'uTexture'), 0);

    gl.drawArrays(gl.POINTS, 0, PARTICLE_COUNT);

    gl.disableVertexAttribArray(posL);
    gl.disableVertexAttribArray(colL);
  }

  function drawLights(mvp) {
    gl.useProgram(lightProgram);

    var sx = (512 / Math.max(1, width)) * 1.1;
    var sy = (512 / Math.max(1, width)) * 1.2;
    var verts = new Float32Array([
      -sx, -sy, 0, 0, 0,
       sx, -sy, 0, 1, 0,
      -sx,  sy, 0, 0, 1,
       sx,  sy, 0, 1, 1
    ]);

    if (!lightBuf) lightBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, lightBuf);
    gl.bufferData(gl.ARRAY_BUFFER, verts, gl.DYNAMIC_DRAW);

    var posL = gl.getAttribLocation(lightProgram, 'aPosition');
    var texL = gl.getAttribLocation(lightProgram, 'aTexCoord');
    gl.enableVertexAttribArray(posL);
    gl.enableVertexAttribArray(texL);
    gl.vertexAttribPointer(posL, 3, gl.FLOAT, false, 20, 0);
    gl.vertexAttribPointer(texL, 2, gl.FLOAT, false, 20, 12);

    gl.uniformMatrix4fv(gl.getUniformLocation(lightProgram, 'uMVPMatrix'), false, mvp);

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texLight);
    gl.uniform1i(gl.getUniformLocation(lightProgram, 'uTexture'), 0);

    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

    gl.disableVertexAttribArray(posL);
    gl.disableVertexAttribArray(texL);
  }

  function updateParticles() {
    for (var i = 0; i < PARTICLE_COUNT; i++) {
      particlePositions[i * 3] += particleSpeeds[i];
    }
  }

  // ── Animation ──
  var mvp = new Float32Array(16);
  var targetXOffset = 0.5;
  var currentXOffset = 0.5;

  function animate() {
    animId = requestAnimationFrame(animate);

    // Smooth xOffset transition
    currentXOffset += (targetXOffset - currentXOffset) * 0.05;

    updateParticles();
    calcMVP(mvp, currentXOffset * 2 - 1); // map [0,1] to [-1,1]

    gl.clear(gl.COLOR_BUFFER_BIT);
    drawBackground();
    drawParticles(mvp);
    drawLights(mvp);
  }

  // ── Input ──
  canvas.addEventListener('mousemove', function(e) { targetXOffset = e.clientX / width; });
  canvas.addEventListener('touchmove', function(e) {
    e.preventDefault();
    targetXOffset = e.touches[0].clientX / width;
  }, { passive: false });

  // ── Bootstrap ──
  var texturesLoaded = 0;
  function onTextureLoad() {
    texturesLoaded++;
    if (texturesLoaded === 3) {
      var data = buildParticles();
      particlePositions = data.pos;
      particleColors = data.col;
      particleSpeeds = data.spd;
      uploadParticleColorBuffer();
      requestAnimationFrame(animate);
    }
  }

  resize();
  initShaders();
  texBg = loadTexture('bg.jpg', onTextureLoad);
  texFlare = loadTexture('flare.png', onTextureLoad);
  texLight = loadTexture('light.jpg', onTextureLoad);

  gl.clearColor(0, 0, 0, 1);
  gl.disable(gl.DEPTH_TEST);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE);

  window.addEventListener('resize', function() {
    resize();
    var data = buildParticles();
    particlePositions = data.pos;
    particleColors = data.col;
    particleSpeeds = data.spd;
    uploadParticleColorBuffer();
  });
})();
