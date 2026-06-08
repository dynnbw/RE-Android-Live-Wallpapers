        (function(){
            // --------------------------------------------------------------
            // 1. 常量与噪声函数 (移植自 grass.rs)
            // --------------------------------------------------------------
            const TESSELATION = 0.5;
            const HALF_TESSELATION = 0.25;
            const MAX_BEND = 0.09;
            const SECONDS_IN_DAY = 86400.0;
            const PI = Math.PI;
            const HALF_PI = PI / 2;
            
            const B = 0x100;
            const BM = 0xff;
            const N_val = 0x1000;
            
            let p = new Array(B + B + 2);
            let g2 = new Array(B + B + 2);
            let g1 = new Array(B + B + 2);
            
            function noiseSCurve(t) { return t * t * (3.0 - 2.0 * t); }
            function normalize2(v) { let s = Math.hypot(v[0], v[1]); v[0] /= s; v[1] /= s; }
            
            function initNoise() {
                for (let i = 0; i < B; i++) {
                    p[i] = i;
                    g1[i] = (Math.random() * (B * 2) - B) / B;
                    g2[i] = [ (Math.random() * (B * 2) - B) / B, (Math.random() * (B * 2) - B) / B ];
                    normalize2(g2[i]);
                }
                for (let i = B-1; i >= 0; i--) {
                    let k = p[i];
                    let j = Math.floor(Math.random() * B);
                    p[i] = p[j];
                    p[j] = k;
                }
                for (let i = 0; i < B + 2; i++) {
                    p[B + i] = p[i];
                    g1[B + i] = g1[i];
                    g2[B + i] = [...g2[i]];
                }
            }
            
            function noisef2(x, y) {
                let t = x + N_val;
                let bx0 = Math.floor(t) & BM;
                let bx1 = (bx0 + 1) & BM;
                let rx0 = t - Math.floor(t);
                let rx1 = rx0 - 1.0;
                
                t = y + N_val;
                let by0 = Math.floor(t) & BM;
                let by1 = (by0 + 1) & BM;
                let ry0 = t - Math.floor(t);
                let ry1 = ry0 - 1.0;
                
                let i = p[bx0];
                let j = p[bx1];
                let b00 = p[i + by0];
                let b10 = p[j + by0];
                let b01 = p[i + by1];
                let b11 = p[j + by1];
                
                let sx = noiseSCurve(rx0);
                let sy = noiseSCurve(ry0);
                
                let q = g2[b00];
                let u = rx0 * q[0] + ry0 * q[1];
                q = g2[b10];
                let v = rx1 * q[0] + ry0 * q[1];
                let a = u + sx * (v - u);
                
                q = g2[b01];
                u = rx0 * q[0] + ry1 * q[1];
                q = g2[b11];
                v = rx1 * q[0] + ry1 * q[1];
                let b_val = u + sx * (v - u);
                
                return 1.5 * (a + sy * (b_val - a));
            }
            
            function turbulencef2(x, y, octaves) {
                let t = 0.0;
                for (let f = 1.0; f <= octaves; f *= 2) {
                    t += Math.abs(noisef2(f * x, f * y)) / f;
                }
                return t;
            }
            
            // --------------------------------------------------------------
            // 2. 日出日落计算器 (本地时间 0-24)
            // --------------------------------------------------------------
            class SunCalculator {
                constructor(lat, lng, timezoneOffsetHours) {
                    this.lat = lat;
                    this.lng = lng;
                    this.tzOffset = timezoneOffsetHours;
                }
                static getDayOfYear(date) {
                    const start = new Date(date.getFullYear(), 0, 0);
                    const diff = date - start;
                    return Math.floor(diff / 86400000);
                }
                getBaseLongitudeHour() { return this.lng / 15.0; }
                getLongitudeHour(dayOfYear, isSunrise) {
                    let offset = isSunrise ? 6 : 18;
                    let dividend = offset - this.getBaseLongitudeHour();
                    let addend = dividend / 24.0;
                    return dayOfYear + addend;
                }
                static getMeanAnomaly(longitudeHour) { return 0.9856 * longitudeHour - 3.289; }
                static getSunTrueLongitude(meanAnomaly) {
                    let meanRad = meanAnomaly * PI / 180.0;
                    let sinMean = Math.sin(meanRad);
                    let sinDouble = Math.sin(2 * meanRad);
                    let trueLong = meanAnomaly + 1.916 * sinMean + 0.020 * sinDouble + 282.634;
                    trueLong = trueLong % 360;
                    if (trueLong < 0) trueLong += 360;
                    return trueLong;
                }
                static getRightAscension(sunTrueLong) {
                    let tanL = Math.tan(sunTrueLong * PI / 180.0);
                    let inner = Math.atan(0.91764 * tanL) * 180.0 / PI;
                    if (inner < 0) inner += 360;
                    let longitudeQuadrant = Math.floor(sunTrueLong / 90) * 90;
                    let raQuadrant = Math.floor(inner / 90) * 90;
                    let ra = inner + (longitudeQuadrant - raQuadrant);
                    return ra / 15.0;
                }
                getSinSunDeclination(sunTrueLong) { return Math.sin(sunTrueLong * PI / 180.0) * 0.39782; }
                getCosineSunDeclination(sinDecl) { return Math.cos(Math.asin(sinDecl)); }
                getCosineSunLocalHour(sunTrueLong, zenithDeg) {
                    let sinDecl = this.getSinSunDeclination(sunTrueLong);
                    let cosDecl = this.getCosineSunDeclination(sinDecl);
                    let zenithRad = zenithDeg * PI / 180.0;
                    let latRad = this.lat * PI / 180.0;
                    let cosZenith = Math.cos(zenithRad);
                    let sinLat = Math.sin(latRad);
                    let cosLat = Math.cos(latRad);
                    let dividend = cosZenith - sinDecl * sinLat;
                    let divisor = cosDecl * cosLat;
                    return dividend / divisor;
                }
                static getSunLocalHour(cosSunLocalHour, isSunrise) {
                    let acosVal = Math.acos(Math.min(1, Math.max(-1, cosSunLocalHour)));
                    let localHour = acosVal * 180.0 / PI;
                    if (!isSunrise) localHour = 360.0 - localHour;
                    return localHour / 15.0;
                }
                getLocalMeanTime(sunTrueLong, longitudeHour, sunLocalHour) {
                    let ra = SunCalculator.getRightAscension(sunTrueLong);
                    let lMeanTime = sunLocalHour + ra - 0.06571 * longitudeHour - 6.622;
                    lMeanTime = lMeanTime % 24;
                    if (lMeanTime < 0) lMeanTime += 24;
                    return lMeanTime;
                }
                convertToLocalTime(localMeanTime, date) {
                    const longitudeOffset = this.lng / 15.0;
                    let localTime = localMeanTime + (this.tzOffset - longitudeOffset);
                    localTime = localTime % 24;
                    if (localTime < 0) localTime += 24;
                    // 粗略夏令时: 3月第二个周日~11月第一个周日
                    const month = date.getMonth();
                    const day = date.getDate();
                    let isDST = false;
                    if (month > 2 && month < 10) isDST = true;
                    else if (month === 2) isDST = (day > 14 && date.getDay() === 0) || (day - date.getDay() > 7);
                    else if (month === 10) isDST = (day < 8 && date.getDay() === 0) || (day + 7 - date.getDay() < 8);
                    if (isDST && Math.abs(this.tzOffset) <= 12) localTime += 1;
                    localTime = localTime % 24;
                    return localTime;
                }
                computeSolarEventTime(zenithDeg, date, isSunrise) {
                    let dayOfYear = SunCalculator.getDayOfYear(date);
                    let longitudeHour = this.getLongitudeHour(dayOfYear, isSunrise);
                    let meanAnomaly = SunCalculator.getMeanAnomaly(longitudeHour);
                    let sunTrueLong = SunCalculator.getSunTrueLongitude(meanAnomaly);
                    let cosHour = this.getCosineSunLocalHour(sunTrueLong, zenithDeg);
                    if (Math.abs(cosHour) > 1) return isSunrise ? 0 : 24;
                    let sunLocalHour = SunCalculator.getSunLocalHour(cosHour, isSunrise);
                    let localMeanTime = this.getLocalMeanTime(sunTrueLong, longitudeHour, sunLocalHour);
                    let localTime = this.convertToLocalTime(localMeanTime, date);
                    return localTime;
                }
                computeSunrise(date) { return this.computeSolarEventTime(90.8333, date, true); }
                computeSunset(date) { return this.computeSolarEventTime(90.8333, date, false); }
            }
            
            function timeToDayFraction(hours) { return (hours % 24) / 24.0; }
            
            // --------------------------------------------------------------
            // 3. 草叶数据结构
            // --------------------------------------------------------------
            class Blade {
                constructor(width, height, randX) {
                    const rand = Math.random;
                    const sizeVal = rand() * 4.0 + 4.0;
                    this.angle = 0.0;
                    this.size = Math.max(2, Math.floor(sizeVal / TESSELATION)) * 2;
                    this.xPos = (randX !== undefined) ? randX : rand() * (width + 300) - 150;
                    this.yPos = height;
                    this.offset = rand() * 0.2 - 0.1;
                    this.scale = 4.0 / (sizeVal / TESSELATION) + (rand() * 0.6 + 0.2) * TESSELATION;
                    this.lengthX = (rand() * 4.5 + 3.0) * TESSELATION * sizeVal * 0.75;
                    this.lengthY = (rand() * 5.5 + 2.0) * TESSELATION * sizeVal * 0.75;
                    this.hardness = (rand() * 1.0 + 0.2) * TESSELATION;
                    this.h = rand() * 0.02 + 0.2;
                    this.s = rand() * 0.22 + 0.78;
                    this.b = rand() * 0.65 + 0.35;
                    this.turbulencex = this.xPos * 0.006;
                }
            }
            
            function hsbToRgb(h, s, b) {
                let hf = (h - Math.floor(h)) * 6.0;
                let ihf = Math.floor(hf);
                let f = hf - ihf;
                let pv = b * (1.0 - s);
                let qv = b * (1.0 - s * f);
                let tv = b * (1.0 - s * (1.0 - f));
                let r = 0, g = 0, bl = 0;
                switch (ihf) {
                    case 0: r = b; g = tv; bl = pv; break;
                    case 1: r = qv; g = b; bl = pv; break;
                    case 2: r = pv; g = b; bl = tv; break;
                    case 3: r = pv; g = qv; bl = b; break;
                    case 4: r = tv; g = pv; bl = b; break;
                    case 5: r = b; g = pv; bl = qv; break;
                    default: r = b; g = tv; bl = pv;
                }
                return [r, g, bl];
            }
            
            // --------------------------------------------------------------
            // 4. WebGL 全局变量
            // --------------------------------------------------------------
            let canvas, gl;
            let screenWidth = window.innerWidth;
            let screenHeight = window.innerHeight;
            
            let textures = { night: null, sunrise: null, sky: null, sunset: null };
            let texturesLoaded = { night: false, sunrise: false, sky: false, sunset: false };
            let alphaTexture = null;     // 抗锯齿纹理
            let bgProgram, grassProgram;
            let bgVAO, bgVBO, bgIBO;
            let grassPosBuffer, grassColorBuffer, grassTexCoordBuffer, grassIndexBuffer;
            let grassIndexCount = 0;
            
            let bladesList = [];
            const BLADES_COUNT = 400;
            
            // 时间参数
            let gDawn = 0.25, gMorning = 0.33, gAfternoon = 0.67, gDusk = 0.75;
            let latitude = 39.9042, longitude = 116.4074; // 北京坐标，可修改
            
            // --------------------------------------------------------------
            // 5. 辅助纹理生成
            // --------------------------------------------------------------
            function createSolidTexture(r, g, b) {
                const canvasTex = document.createElement('canvas');
                canvasTex.width = 2; canvasTex.height = 2;
                const ctx = canvasTex.getContext('2d');
                ctx.fillStyle = `rgb(${r*255},${g*255},${b*255})`;
                ctx.fillRect(0,0,2,2);
                const tex = gl.createTexture();
                gl.bindTexture(gl.TEXTURE_2D, tex);
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvasTex);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                return tex;
            }
            
            function loadTexture(url, name, fallbackColor) {
                const img = new Image();
                img.crossOrigin = "Anonymous";
                img.onload = () => {
                    const tex = gl.createTexture();
                    gl.bindTexture(gl.TEXTURE_2D, tex);
                    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                    textures[name] = tex;
                    texturesLoaded[name] = true;
                };
                img.onerror = () => {
                    console.warn(`纹理加载失败: ${url}, 使用fallback`);
                    textures[name] = createSolidTexture(fallbackColor[0], fallbackColor[1], fallbackColor[2]);
                    texturesLoaded[name] = true;
                };
                img.src = url;
            }
            
            // AA alpha gradient: LINEAR in-level + NEAREST between levels = sharp at all distances
            function createAlphaTexture() {
                const tex = gl.createTexture();
                gl.bindTexture(gl.TEXTURE_2D, tex);
                // 4px: sharp transition, LINEAR filters within this for anti-aliasing
                const alphaData = new Uint8Array([0, 255, 255, 255, 255, 0, 0, 0]);
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.ALPHA, 8, 1, 0, gl.ALPHA, gl.UNSIGNED_BYTE, alphaData);
                gl.generateMipmap(gl.TEXTURE_2D);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_NEAREST);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                return tex;
            }
            
            function initTextures() {
                loadTexture('./night.jpg', 'night', [0.05, 0.06, 0.18]);
                loadTexture('./sunrise.jpg', 'sunrise', [1.0, 0.67, 0.4]);
                loadTexture('./sky.jpg', 'sky', [0.53, 0.81, 0.92]);
                loadTexture('./sunset.jpg', 'sunset', [1.0, 0.5, 0.29]);
                alphaTexture = createAlphaTexture();
            }
            
            // --------------------------------------------------------------
            // 6. 着色器 (背景 + 草地 带抗锯齿)
            // --------------------------------------------------------------
            const bgVertexShader = `
                attribute vec2 a_position;
                attribute vec2 a_texCoord;
                varying vec2 v_texCoord;
                void main() {
                    v_texCoord = a_texCoord;
                    gl_Position = vec4(a_position, 0.0, 1.0);
                }
            `;
            const bgFragmentShader = `
                precision mediump float;
                uniform sampler2D u_texture;
                uniform float u_alpha;
                varying vec2 v_texCoord;
                void main() {
                    vec4 texColor = texture2D(u_texture, v_texCoord);
                    gl_FragColor = vec4(texColor.rgb, texColor.a * u_alpha);
                }
            `;
            
            // 草地顶点着色器: 增加了 a_texCoord (s 坐标) 和 varying v_texCoord
            const grassVertexShader = `
                attribute vec2 a_position;
                attribute vec3 a_color;
                attribute float a_texCoord;
                varying vec3 v_color;
                varying float v_texCoord;
                uniform vec2 u_resolution;
                void main() {
                    v_color = a_color;
                    v_texCoord = a_texCoord;
                    vec2 clipPos = a_position / u_resolution * 2.0 - 1.0;
                    clipPos.y = -clipPos.y;
                    gl_Position = vec4(clipPos, 0.0, 1.0);
                }
            `;
            // 草地片段着色器: 采样 alpha 纹理，与颜色相乘
            const grassFragmentShader = `
                precision mediump float;
                varying vec3 v_color;
                varying float v_texCoord;
                uniform sampler2D u_alphaTex;
                void main() {
                    float alpha = texture2D(u_alphaTex, vec2(v_texCoord, 0.5)).a;
                    gl_FragColor = vec4(v_color, alpha * 0.95);
                }
            `;
            
            function createShader(gl, src, type) {
                const shader = gl.createShader(type);
                gl.shaderSource(shader, src);
                gl.compileShader(shader);
                if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
                    console.error(gl.getShaderInfoLog(shader));
                }
                return shader;
            }
            
            function createProgram(vsSrc, fsSrc) {
                const vs = createShader(gl, vsSrc, gl.VERTEX_SHADER);
                const fs = createShader(gl, fsSrc, gl.FRAGMENT_SHADER);
                const prog = gl.createProgram();
                gl.attachShader(prog, vs);
                gl.attachShader(prog, fs);
                gl.linkProgram(prog);
                if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
                    console.error(gl.getProgramInfoLog(prog));
                }
                return prog;
            }
            
            function initBackgroundQuad() {
                const vertices = new Float32Array([
                    -1, -1,  0, 1,
                     1, -1,  1, 1,
                     1,  1,  1, 0,
                    -1,  1,  0, 0
                ]);
                const indices = new Uint16Array([0,1,2, 0,2,3]);
                bgVBO = gl.createBuffer();
                gl.bindBuffer(gl.ARRAY_BUFFER, bgVBO);
                gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.STATIC_DRAW);
                bgIBO = gl.createBuffer();
                gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, bgIBO);
                gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, indices, gl.STATIC_DRAW);
                
                if (gl.createVertexArray) {
                    bgVAO = gl.createVertexArray();
                    gl.bindVertexArray(bgVAO);
                    gl.bindBuffer(gl.ARRAY_BUFFER, bgVBO);
                    const posLoc = gl.getAttribLocation(bgProgram, "a_position");
                    const texLoc = gl.getAttribLocation(bgProgram, "a_texCoord");
                    gl.enableVertexAttribArray(posLoc);
                    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 16, 0);
                    gl.enableVertexAttribArray(texLoc);
                    gl.vertexAttribPointer(texLoc, 2, gl.FLOAT, false, 16, 8);
                    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, bgIBO);
                    gl.bindVertexArray(null);
                }
            }
            
            function drawBackground(nowFraction) {
                if (!texturesLoaded.night || !texturesLoaded.sunrise || !texturesLoaded.sky || !texturesLoaded.sunset) {
                    gl.clearColor(0.2, 0.3, 0.5, 1);
                    gl.clear(gl.COLOR_BUFFER_BIT);
                    return;
                }
                gl.useProgram(bgProgram);
                if (bgVAO) gl.bindVertexArray(bgVAO);
                else {
                    gl.bindBuffer(gl.ARRAY_BUFFER, bgVBO);
                    const posLoc = gl.getAttribLocation(bgProgram, "a_position");
                    const texLoc = gl.getAttribLocation(bgProgram, "a_texCoord");
                    gl.enableVertexAttribArray(posLoc);
                    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 16, 0);
                    gl.enableVertexAttribArray(texLoc);
                    gl.vertexAttribPointer(texLoc, 2, gl.FLOAT, false, 16, 8);
                    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, bgIBO);
                }
                
                let alphaNight = 0, alphaSunrise = 0, alphaSky = 0, alphaSunset = 0;
                if (nowFraction >= 0 && nowFraction < gDawn) alphaNight = 1.0;
                else if (nowFraction >= gDawn && nowFraction <= gMorning) {
                    let half = gDawn + (gMorning - gDawn) * 0.5;
                    if (nowFraction <= half) {
                        let t = (nowFraction - gDawn) / (half - gDawn);
                        alphaNight = 1.0 - t;
                        alphaSunrise = t;
                    } else {
                        let t = (nowFraction - half) / (gMorning - half);
                        alphaSunrise = 1.0 - t;
                        alphaSky = t;
                    }
                } else if (nowFraction > gMorning && nowFraction < gAfternoon) alphaSky = 1.0;
                else if (nowFraction >= gAfternoon && nowFraction <= gDusk) {
                    let half = gAfternoon + (gDusk - gAfternoon) * 0.5;
                    if (nowFraction <= half) {
                        let t = (nowFraction - gAfternoon) / (half - gAfternoon);
                        alphaSky = 1.0 - t;
                        alphaSunset = t;
                    } else {
                        let t = (nowFraction - half) / (gDusk - half);
                        alphaSunset = 1.0 - t;
                        alphaNight = t;
                    }
                } else if (nowFraction > gDusk) alphaNight = 1.0;
                
                gl.enable(gl.BLEND);
                gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
                const alphaLoc = gl.getUniformLocation(bgProgram, "u_alpha");
                const texLoc = gl.getUniformLocation(bgProgram, "u_texture");
                const drawLayer = (tex, alpha) => {
                    if (alpha <= 0.001) return;
                    gl.uniform1f(alphaLoc, alpha);
                    gl.activeTexture(gl.TEXTURE0);
                    gl.bindTexture(gl.TEXTURE_2D, tex);
                    gl.uniform1i(texLoc, 0);
                    gl.drawElements(gl.TRIANGLES, 6, gl.UNSIGNED_SHORT, 0);
                };
                drawLayer(textures.night, alphaNight);
                drawLayer(textures.sunrise, alphaSunrise);
                drawLayer(textures.sky, alphaSky);
                drawLayer(textures.sunset, alphaSunset);
                gl.disable(gl.BLEND);
                if (bgVAO) gl.bindVertexArray(null);
            }
            
            // Wind field: sample 8 points, interpolate (matching GrassWindField.java)
            const WIND_SAMPLES = 8;
            let windSamples = new Array(WIND_SAMPLES);

            function updateGrassGeometry(nowTime, brightness, nightDesat) {
                let positions = [];
                let colors = [];
                let texCoords = [];
                let indices = [];
                let vertexOffset = 0;

                // Sample wind field: find min/max turbulenceX, sample along range, interpolate
                let minTx = Infinity, maxTx = -Infinity;
                for (let b of bladesList) {
                    if (b.turbulencex < minTx) minTx = b.turbulencex;
                    if (b.turbulencex > maxTx) maxTx = b.turbulencex;
                }
                let range = maxTx - minTx;
                if (range < 0.01) range = 0.01;
                for (let i = 0; i < WIND_SAMPLES; i++) {
                    let x = minTx + range * i / (WIND_SAMPLES - 1);
                    windSamples[i] = (turbulencef2(x, nowTime, 4.0) - 0.5) * 0.5;
                }
                let sampleStep = range / (WIND_SAMPLES - 1);

                for (let blade of bladesList) {
                    // Interpolate wind angle from samples
                    let idx = Math.floor((blade.turbulencex - minTx) / sampleStep);
                    idx = Math.max(0, Math.min(WIND_SAMPLES - 2, idx));
                    let t = ((blade.turbulencex - minTx) / sampleStep) - idx;
                    let newAngle = windSamples[idx] * (1 - t) + windSamples[idx + 1] * t;
                    newAngle = isNaN(newAngle) ? 0 : newAngle;
                    let angleDelta = (newAngle + blade.offset - blade.angle) * 0.15;
                    let angle = blade.angle + angleDelta;
                    angle = isNaN(angle) ? 0 : angle;
                    angle = Math.min(MAX_BEND, Math.max(-MAX_BEND, angle));
                    blade.angle = angle;

                    // Grass color: night desaturation matching Android GrassRenderDataBuilder
                    let v = blade.b * brightness;
                    let s = blade.s;
                    if (nightDesat > 0) s = s * (1 - nightDesat);
                    let finalB = Math.min(1, Math.max(0, v));
                    let finalS = Math.min(1, Math.max(0, s));
                    let rgb = hsbToRgb(blade.h, finalS, finalB);
                    
                    let scale = blade.scale;
                    let xpos = blade.xPos;
                    let size = blade.size;
                    let currentAngleRad = HALF_PI;
                    let bottomX = xpos;
                    let bottomY = blade.yPos;
                    let d = angle * blade.hardness;
                    let si = size * scale;
                    let bottomWidth = si * 1.2;  // wider base
                    let bottomLeft = bottomX - bottomWidth;
                    let bottomRight = bottomX + bottomWidth;
                    let bottom = bottomY + HALF_TESSELATION;
                    
                    // 底部四边形 (两个三角形)
                    positions.push(bottomLeft, bottom);
                    positions.push(bottomRight, bottom);
                    colors.push(...rgb, ...rgb);
                    texCoords.push(0.0, 1.0);
                    vertexOffset += 2;  // bottom vertices occupy slots 0 and 1

                    let lastX = bottomX, lastY = bottomY;
                    for (let seg = 0; seg < size; seg++) {
                        let segRad = currentAngleRad + d * (seg + 1);
                        let topX = lastX - Math.cos(segRad) * blade.lengthX;
                        let topY = lastY - Math.sin(segRad) * blade.lengthY;
                        let remainingSize = size - seg;
                        let segScale = remainingSize * scale;
                        // Taper toward tip — tip is 20% of base width
                        let tipFactor = 0.2 + 0.8 * remainingSize / size;
                        let w = segScale * tipFactor;
                        let leftX = topX - w;
                        let rightX = topX + w;
                        
                        positions.push(leftX, topY);
                        positions.push(rightX, topY);
                        colors.push(...rgb, ...rgb);
                        texCoords.push(0.0, 1.0);
                        
                        let curIdx = vertexOffset;
                        let prevIdx = curIdx - 2;
                        indices.push(prevIdx, prevIdx+1, curIdx);
                        indices.push(prevIdx+1, curIdx+1, curIdx);
                        vertexOffset += 2;
                        lastX = topX;
                        lastY = topY;
                    }
                }
                
                grassIndexCount = indices.length;
                if (grassIndexCount === 0) return;
                
                gl.bindBuffer(gl.ARRAY_BUFFER, grassPosBuffer);
                gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(positions), gl.DYNAMIC_DRAW);
                gl.bindBuffer(gl.ARRAY_BUFFER, grassColorBuffer);
                gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(colors), gl.DYNAMIC_DRAW);
                gl.bindBuffer(gl.ARRAY_BUFFER, grassTexCoordBuffer);
                gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(texCoords), gl.DYNAMIC_DRAW);
                gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, grassIndexBuffer);
                gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(indices), gl.DYNAMIC_DRAW);
            }
            
            function drawGrass() {
                if (grassIndexCount === 0) return;
                gl.useProgram(grassProgram);
                const resLoc = gl.getUniformLocation(grassProgram, "u_resolution");
                gl.uniform2f(resLoc, screenWidth, screenHeight);
                
                // 位置属性
                gl.bindBuffer(gl.ARRAY_BUFFER, grassPosBuffer);
                const posLoc = gl.getAttribLocation(grassProgram, "a_position");
                gl.enableVertexAttribArray(posLoc);
                gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);
                
                // 颜色属性
                gl.bindBuffer(gl.ARRAY_BUFFER, grassColorBuffer);
                const colLoc = gl.getAttribLocation(grassProgram, "a_color");
                gl.enableVertexAttribArray(colLoc);
                gl.vertexAttribPointer(colLoc, 3, gl.FLOAT, false, 0, 0);
                
                // 纹理坐标属性 (s)
                gl.bindBuffer(gl.ARRAY_BUFFER, grassTexCoordBuffer);
                const texCoordLoc = gl.getAttribLocation(grassProgram, "a_texCoord");
                gl.enableVertexAttribArray(texCoordLoc);
                gl.vertexAttribPointer(texCoordLoc, 1, gl.FLOAT, false, 0, 0);
                
                // 绑定 alpha 纹理
                gl.activeTexture(gl.TEXTURE0);
                gl.bindTexture(gl.TEXTURE_2D, alphaTexture);
                gl.uniform1i(gl.getUniformLocation(grassProgram, "u_alphaTex"), 0);
                
                gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, grassIndexBuffer);
                gl.enable(gl.BLEND);
                gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
                gl.drawElements(gl.TRIANGLES, grassIndexCount, gl.UNSIGNED_SHORT, 0);
                gl.disable(gl.BLEND);
            }
            
            // 日出日落更新
            function updateSunTimes() {
                const now = new Date();
                const tzOffset = -now.getTimezoneOffset() / 60;
                const calculator = new SunCalculator(latitude, longitude, tzOffset);
                let sunrise = calculator.computeSunrise(now);
                let sunset = calculator.computeSunset(now);
                if (isNaN(sunrise) || sunrise < 3 || sunrise > 11) sunrise = 6.0;
                if (isNaN(sunset) || sunset < 15 || sunset > 23) sunset = 18.0;
                gDawn = timeToDayFraction(sunrise);
                gDusk = timeToDayFraction(sunset);
                gMorning = gDawn + 1.0 / 12.0;
                gAfternoon = gDusk - 1.0 / 12.0;
                if (gMorning > 1) gMorning = 1;
                if (gAfternoon < 0) gAfternoon = 0;
            }
            
            function getCurrentTimeFraction() {
                const now = new Date();
                const totalSecs = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds();
                return totalSecs / SECONDS_IN_DAY;
            }
            
            function computeGrassBrightness(nowFrac) {
                if (nowFrac >= 0 && nowFrac < gDawn) return 0.0;
                else if (nowFrac >= gDawn && nowFrac <= gMorning) {
                    let half = gDawn + (gMorning - gDawn) * 0.5;
                    if (nowFrac <= half) return (nowFrac - gDawn) / (half - gDawn);
                    else return 1.0;
                } else if (nowFrac > gMorning && nowFrac < gAfternoon) return 1.0;
                else if (nowFrac >= gAfternoon && nowFrac <= gDusk) {
                    let half = gAfternoon + (gDusk - gAfternoon) * 0.5;
                    if (nowFrac <= half) return 1.0 - (nowFrac - gAfternoon) / (half - gAfternoon);
                    else return 0.0;
                } else return 0.0;
            }
            
            function regenerateBlades() {
                bladesList = [];
                for (let i = 0; i < BLADES_COUNT; i++) {
                    let xPos = Math.random() * (screenWidth + 300) - 150;
                    bladesList.push(new Blade(screenWidth, screenHeight, xPos));
                }
            }
            
            function resizeCanvas() {
                screenWidth = window.innerWidth;
                screenHeight = window.innerHeight;
                canvas.width = screenWidth;
                canvas.height = screenHeight;
                gl.viewport(0, 0, screenWidth, screenHeight);
                regenerateBlades();
            }
            
            function animate() {
                updateSunTimes();
                const timeFrac = getCurrentTimeFraction();
                const brightness = computeGrassBrightness(timeFrac);
                // Night desaturation: when dark, grass loses color
                const nightDesat = brightness < 0.2 ? (0.2 - brightness) / 0.2 : 0.0;
                const now = new Date();
                const hours = now.getHours().toString().padStart(2,'0');
                const minutes = now.getMinutes().toString().padStart(2,'0');
                document.getElementById('time-status').innerHTML = `${hours}:${minutes} | 亮度:${brightness.toFixed(2)} | 黎明 ${Math.floor(gDawn*24)}:${Math.floor((gDawn*24)%1*60)} 黄昏 ${Math.floor(gDusk*24)}:${Math.floor((gDusk*24)%1*60)}`;
                
                drawBackground(timeFrac);
                const flowTime = performance.now() / 1000 * 0.04;
                updateGrassGeometry(flowTime, brightness, nightDesat);
                drawGrass();
                
                requestAnimationFrame(animate);
            }
            
            function initWebGL() {
                canvas = document.createElement('canvas');
                canvas.style.position = 'fixed';
                canvas.style.top = '0';
                canvas.style.left = '0';
                canvas.style.width = '100%';
                canvas.style.height = '100%';
                canvas.style.display = 'block';
                document.body.appendChild(canvas);
                gl = canvas.getContext('webgl', { alpha: false, antialias: true });
                if (!gl) { alert("浏览器不支持WebGL"); return; }
                
                resizeCanvas();
                window.addEventListener('resize', () => resizeCanvas());
                
                initNoise();
                regenerateBlades();
                initTextures();
                
                bgProgram = createProgram(bgVertexShader, bgFragmentShader);
                grassProgram = createProgram(grassVertexShader, grassFragmentShader);
                
                initBackgroundQuad();
                
                grassPosBuffer = gl.createBuffer();
                grassColorBuffer = gl.createBuffer();
                grassTexCoordBuffer = gl.createBuffer();
                grassIndexBuffer = gl.createBuffer();
                
                updateSunTimes();
                
                gl.clearColor(0,0,0,1);
                animate();
            }
            
            initWebGL();
        })();
