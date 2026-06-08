const canvas = document.getElementById('c');
const gl = canvas.getContext('webgl', { alpha: false, premultipliedAlpha: false });

// ==================== Constants (exact match FallScene.java) ====================
const LEAF_SIZE       = 0.28;
const LEAVES_COUNT    = 14;
const LEAVES_TEX_COUNT = 20;
const LEAVES_ATLAS_COLS = 10;
const MAX_DROPS = 40;
const MESH_RESOLUTION  = 48;

// ==================== Shaders ====================

const waterVS = `
attribute vec2 a_pos;
attribute vec2 a_tex;
varying vec2 v_tex0;
varying vec4 v_color;
uniform vec4 u_drop[16];
uniform vec4 u_bgUV;
uniform float u_dxMul;
uniform vec2 u_offset;

vec2 addDrop(vec4 d, vec2 pos, float dxMul) {
    vec2 ret = vec2(0.0, 0.0);
    vec2 delta = d.xy - pos;
    delta.x *= dxMul * 2.2;
    float dist = length(delta);
    if (dist < d.w) {
        float amp = d.z * dist;
        amp /= d.w * d.w;
        amp *= sin(d.w - dist);
        ret = delta * amp;
        ret.x /= 2.2;
    }
    return ret;
}

void main() {
    vec2 pos = a_pos;
    gl_Position = vec4(pos.x, pos.y, 0.0, 1.0);
    float dxMul = u_dxMul;
    vec2 ripplePos = pos * vec2(25.0, 55.0) + vec2(25.0, 55.0);
    v_tex0 = a_tex + u_offset * 0.015;
    v_color = vec4(1.0, 1.0, 1.0, 1.0);
    v_tex0 += addDrop(u_drop[0], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[1], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[2], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[3], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[4], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[5], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[6], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[7], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[8], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[9], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[10], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[11], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[12], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[13], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[14], ripplePos, dxMul);
    v_tex0 += addDrop(u_drop[15], ripplePos, dxMul);
}`;

const waterFS = `
precision mediump float;
varying vec2 v_tex0;
varying vec4 v_color;
uniform sampler2D u_tex;
void main() {
    gl_FragColor = texture2D(u_tex, v_tex0) * v_color;
}`;

const leafVS = `
attribute vec2 a_pos;
attribute vec2 a_uv;
varying vec2 v_uv;
uniform mat3 u_mat;
uniform float u_ratio;
void main() {
    vec3 p = u_mat * vec3(a_pos, 1.0);
    p.x /= u_ratio;
    gl_Position = vec4(p.xy, 0.0, 1.0);
    v_uv = a_uv;
}`;

const leafFS = `
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform vec4 u_color;
void main() {
    gl_FragColor = texture2D(u_tex, v_uv) * u_color;
}`;

// ==================== GL helpers ====================
function compileShader(type, src) {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) console.error(gl.getShaderInfoLog(s));
    return s;
}
function createProgram(vs, fs) {
    const p = gl.createProgram();
    gl.attachShader(p, compileShader(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, compileShader(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(p);
    return p;
}
function createTexture(img) {
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.bindTexture(gl.TEXTURE_2D, null);
    return t;
}
function loadImage(src) {
    return new Promise((resolve) => { const img = new Image(); img.onload = () => resolve(img); img.src = src; });
}

// ==================== mat3 ====================
function mat3() { return [1,0,0, 0,1,0, 0,0,1]; }
function mat3Translate(m, x, y) { m[6]+=x; m[7]+=y; return m; }
function mat3Scale(m, sx, sy) { m[0]*=sx; m[4]*=sy; return m; }
function mat3Rotate(m, deg) {
    const a = deg * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
    const r0 = m[0]*c+m[1]*s, r1 = -m[0]*s+m[1]*c;
    const r2 = m[3]*c+m[4]*s, r3 = -m[3]*s+m[4]*c;
    m[0]=r0; m[1]=r1; m[3]=r2; m[4]=r3; return m;
}

// ==================== Drop (exact Android: exponential decay) ====================
class Drop {
    constructor() { this.ampS = 0; this.ampE = 0; this.spread = 1; this.x = 0; this.y = 0; }
    update(dt) {
        if (this.ampS > 0) {
            this.spread += 30 * dt;
            this.ampE = this.ampS * Math.exp(-0.02 * this.spread) / (1 + 0.01 * this.spread);
        }
    }
    isDead() { return this.ampE < 0.001; }
    reset() { this.ampS = 0; this.ampE = 0; this.spread = 1; }
    activate(x, y, amp) { this.x = x; this.y = y; this.ampS = amp; this.spread = 0; this.ampE = amp; }
}

// ==================== Leaf (exact Android: FallScene.Leaf.init) ====================
class Leaf {
    reset(startAboveWater) {
        const r = Math.random;
        this.x = (r() - 0.5) * 4;
        this.y = (r() - 0.5) * 3.333;
        this.scale = 0.4 + r() * 0.1;
        this.angle = r() * 360;
        this.spin = (r() - 0.5) * 0.016;
        this.altitude = startAboveWater ? 0.7 : -1;
        this.deltaX = (r() - 0.5) * 0.02;
        this.deltaY = -(0.036 + r() * 0.008);
        this.leafTextureIndex = Math.floor(r() * LEAVES_TEX_COUNT);
        this.rippled = !startAboveWater;
    }
}

// ==================== Main ====================
async function main() {
    const [pondImg, leavesImg] = await Promise.all([
        loadImage('pond.jpg'), loadImage('leaves_atlas.png')
    ]);
    const pondTex   = createTexture(pondImg);
    const leavesTex = createTexture(leavesImg);

    const waterProg = createProgram(waterVS, waterFS);
    const leafProg  = createProgram(leafVS, leafFS);

    const wU_tex    = gl.getUniformLocation(waterProg, 'u_tex');
    const wU_drop   = gl.getUniformLocation(waterProg, 'u_drop');
    const wU_bgUV   = gl.getUniformLocation(waterProg, 'u_bgUV');
    const wU_dxMul  = gl.getUniformLocation(waterProg, 'u_dxMul');
    const wU_offset = gl.getUniformLocation(waterProg, 'u_offset');
    const lU_mat    = gl.getUniformLocation(leafProg, 'u_mat');
    const lU_ratio  = gl.getUniformLocation(leafProg, 'u_ratio');
    const lU_tex    = gl.getUniformLocation(leafProg, 'u_tex');
    const lU_color  = gl.getUniformLocation(leafProg, 'u_color');

    // Water mesh (rectangular, matching Android: wRes=M+2, hRes=M*h/w/2+2)
    let meshW = 0, meshH = 0, meshIndexCount = 0;
    let wPosBuf, wTexBuf, wIdxBuf;

    function rebuildMesh(rw, rh) {
        const ratio = rw / rh;
        meshW = MESH_RESOLUTION + 2;
        meshH = Math.max(2, Math.floor(MESH_RESOLUTION * ratio / 2) + 2);

        const verts = [], texs = [], idxs = [];
        const bgZoom = 0.72; // < 1 = zoom in
        for (let y = 0; y <= meshH; y++) {
            const v = (y / meshH) * 2 - 1;
            for (let x = 0; x <= meshW; x++) {
                verts.push((x / meshW) * 2 - 1, v);
                texs.push(0.5 + ((x / meshW) - 0.5) * bgZoom,
                          0.5 + ((y / meshH) - 0.5) * bgZoom);
            }
        }
        for (let y = 0; y < meshH; y++) {
            const off = y * (meshW + 1);
            for (let x = 0; x < meshW; x++) {
                const a = off + x, b = a + 1, c = a + meshW + 1, d = c + 1;
                if ((y & 1) === 0) idxs.push(a,b,c, b,d,c);
                else             idxs.push(a,d,c, a,b,d);
            }
        }

        meshIndexCount = idxs.length;
        if (wPosBuf) gl.deleteBuffer(wPosBuf);
        if (wTexBuf) gl.deleteBuffer(wTexBuf);
        if (wIdxBuf) gl.deleteBuffer(wIdxBuf);
        wPosBuf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, wPosBuf);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(verts), gl.STATIC_DRAW);
        wTexBuf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, wTexBuf);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(texs), gl.STATIC_DRAW);
        wIdxBuf = gl.createBuffer(); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, wIdxBuf);
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(idxs), gl.STATIC_DRAW);
    }

    // Leaf geometry (20-leaf atlas, 2 rows x 10 cols)
    const lv = LEAF_SIZE;
    const stride = 4 * 4;
    const leafBufs = [];
    for (let i = 0; i < LEAVES_TEX_COUNT; i++) {
        const col = i % LEAVES_ATLAS_COLS;
        const row = Math.floor(i / LEAVES_ATLAS_COLS);
        const u1 = col / LEAVES_ATLAS_COLS;
        const u2 = (col + 1) / LEAVES_ATLAS_COLS;
        const v1 = row / 2;
        const v2 = (row + 1) / 2;
        const d = new Float32Array([
            -lv,-lv,u1,v2, lv,-lv,u2,v2, lv,lv,u2,v1,
            -lv,-lv,u1,v2, lv,lv,u2,v1, -lv,lv,u1,v1
        ]);
        const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b);
        gl.bufferData(gl.ARRAY_BUFFER, d, gl.STATIC_DRAW);
        leafBufs.push(b);
    }

    // State
    const leaves = Array.from({length: LEAVES_COUNT}, () => { const l = new Leaf(); l.reset(false); return l; });
    // Android: mDrops (random) + mWaterDrops (leaf/touch) — two separate systems
    const meshDrops = Array.from({length: MAX_DROPS}, () => new Drop());
    const dropUniform = new Float32Array(MAX_DROPS * 4);

    function genLeafDrop(leaf, amp) {
        let best = 0, minA = Infinity;
        for (let i = 0; i < MAX_DROPS; i++) {
            if (meshDrops[i].ampE < minA) { best = i; minA = meshDrops[i].ampE; }
        }
        meshDrops[best].activate(leaf.x * 25 + 25, leaf.y * 55 + 55, amp);
    }
    function addTapDrop(x, y, amp) {
        let best = 0, minA = Infinity;
        for (let i = 0; i < MAX_DROPS; i++) {
            if (meshDrops[i].ampE < minA) { best = i; minA = meshDrops[i].ampE; }
        }
        meshDrops[best].activate(x, y, amp);
    }

    // Input — matches Android ACTION_DOWN + ACTION_MOVE with 42px distance threshold
    let offsetX = 0, offsetY = 0;
    let lastDropX = -1, lastDropY = -1, dragging = false;
    const DROP_DIST_THRESHOLD = 42; // TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX

    function screenToDrop(cx, cy) {
        return [cx / canvas.width * 50, (1 - cy / canvas.height) * 110];
    }
    function tryDrop(cx, cy) {
        const [dx, dy] = screenToDrop(cx, cy);
        if (lastDropX < 0 || lastDropY < 0 ||
            Math.hypot(cx - lastDropX, cy - lastDropY) >= DROP_DIST_THRESHOLD) {
            addTapDrop(dx, dy, 2);
            lastDropX = cx; lastDropY = cy;
        }
    }

    canvas.addEventListener('mousedown', e => {
        dragging = true; lastDropX = -1; lastDropY = -1;
        tryDrop(e.clientX, e.clientY);
    });
    document.addEventListener('mousemove', e => {
        offsetX = (e.clientX / window.innerWidth - 0.5) * 2;
        offsetY = (e.clientY / window.innerHeight - 0.5) * 2;
        if (dragging) tryDrop(e.clientX, e.clientY);
    });
    document.addEventListener('mouseup', () => { dragging = false; });

    canvas.addEventListener('touchstart', e => {
        e.preventDefault();
        dragging = true; lastDropX = -1; lastDropY = -1;
        tryDrop(e.touches[0].clientX, e.touches[0].clientY);
    });
    canvas.addEventListener('touchmove', e => {
        e.preventDefault();
        if (dragging) tryDrop(e.touches[0].clientX, e.touches[0].clientY);
    });
    document.addEventListener('touchend', () => { dragging = false; });

    let lastTime = performance.now();
    let meshBuilt = false;

    function frame() {
        requestAnimationFrame(frame);
        const now = performance.now();
        let dt = (now - lastTime) * 0.001;
        if (dt > 0.2) dt = 0.1;
        lastTime = now;

        const w = canvas.width = window.innerWidth;
        const h = canvas.height = window.innerHeight;
        const ratio = w / h;
        gl.viewport(0, 0, w, h);

        if (!meshBuilt) { rebuildMesh(w, h); meshBuilt = true; }

        gl.clearColor(0, 0, 0, 1);
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

        // Mesh drops (Android: mWaterDrops, leaf/touch generated)
        for (const d of meshDrops) d.update(dt);

        // Leaf physics (exact Android: FallScene.updateLeaves)
        for (const l of leaves) {
            if (l.altitude <= 0) {
                if (!l.rippled) {
                    genLeafDrop(l, 1.5);
                    l.rippled = true;
                    l.spin *= 0.25;
                }
                l.x += l.deltaX * dt;
                l.y += l.deltaY * dt;
                l.angle += l.spin;
                const margin = LEAF_SIZE * 0.6;
                const scrBtm = -1 - margin;
                const scrTop =  1 + margin;
                if (l.y < scrBtm || l.y > scrTop) l.reset(true);
            } else {
                l.altitude -= 0.15 * dt;
                l.angle += l.spin * 2;
            }
        }

        // Uniform: 5 random drops + 5 mesh drops (10 slots total)
        for (let i = 0; i < MAX_DROPS; i++) {
            const d = meshDrops[i];
            dropUniform[i*4]   = d.x;
            dropUniform[i*4+1] = d.y;
            dropUniform[i*4+2] = d.ampE * 0.04;
            dropUniform[i*4+3] = d.spread;
        }

        // ---- Water pass ----
        gl.useProgram(waterProg);
        gl.uniform4fv(wU_drop, dropUniform);
        gl.uniform1f(wU_dxMul, canvas.width / canvas.height);
        gl.uniform2f(wU_offset, offsetX * 0.015, offsetY * 0.015);
        gl.uniform4f(wU_bgUV, 0, 0, 1, 1);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, pondTex); gl.uniform1i(wU_tex, 0);
        gl.bindBuffer(gl.ARRAY_BUFFER, wPosBuf); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0); gl.enableVertexAttribArray(0);
        gl.bindBuffer(gl.ARRAY_BUFFER, wTexBuf); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 0, 0); gl.enableVertexAttribArray(1);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, wIdxBuf);
        gl.drawElements(gl.TRIANGLES, meshIndexCount, gl.UNSIGNED_SHORT, 0);
        gl.disableVertexAttribArray(0); gl.disableVertexAttribArray(1);

        // ---- Leaf pass (exact Android: FallGL.drawLeaf) ----
        gl.useProgram(leafProg);
        gl.uniform1f(lU_ratio, ratio);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, leavesTex); gl.uniform1i(lU_tex, 0);

        for (const l of leaves) {
            const buf = leafBufs[l.leafTextureIndex % LEAVES_TEX_COUNT];

            // Shadow (altitude > 0, black 15% alpha, altitude offset)
            if (l.altitude > 0) {
                let sa = 1;
                if (l.altitude >= 0.4) sa = Math.max(0, 1 - (l.altitude - 0.4) / 0.1);
                sa = Math.max(0, Math.min(1, sa)) * 0.15;
                const so = l.altitude * 0.2;
                const m = mat3();
                mat3Translate(m, l.x - so, l.y - so);
                mat3Scale(m, l.scale, l.scale);
                mat3Rotate(m, l.angle);
                gl.uniformMatrix3fv(lU_mat, false, m);
                gl.uniform4f(lU_color, 0, 0, 0, sa);
                gl.bindBuffer(gl.ARRAY_BUFFER, buf);
                gl.vertexAttribPointer(0, 2, gl.FLOAT, false, stride, 0); gl.enableVertexAttribArray(0);
                gl.vertexAttribPointer(1, 2, gl.FLOAT, false, stride, 8); gl.enableVertexAttribArray(1);
                gl.drawArrays(gl.TRIANGLES, 0, 6);
                gl.disableVertexAttribArray(0); gl.disableVertexAttribArray(1);
            }

            // Main leaf
            let la = 1;
            if (l.altitude > 0) {
                if (l.altitude >= 0.4) la = Math.max(0, 1 - (l.altitude - 0.4) / 0.1);
                la = Math.max(0, Math.min(1, la));
            }
            const m = mat3();
            mat3Translate(m, l.x, l.y);
            mat3Scale(m, l.scale, l.scale);
            mat3Rotate(m, l.angle);
            gl.uniformMatrix3fv(lU_mat, false, m);
            gl.uniform4f(lU_color, 1, 1, 1, la);
            gl.bindBuffer(gl.ARRAY_BUFFER, buf);
            gl.vertexAttribPointer(0, 2, gl.FLOAT, false, stride, 0); gl.enableVertexAttribArray(0);
            gl.vertexAttribPointer(1, 2, gl.FLOAT, false, stride, 8); gl.enableVertexAttribArray(1);
            gl.drawArrays(gl.TRIANGLES, 0, 6);
            gl.disableVertexAttribArray(0); gl.disableVertexAttribArray(1);
        }
    }
    frame();
}
main();
