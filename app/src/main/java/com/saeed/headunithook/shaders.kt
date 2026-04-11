package com.saeed.headunithook

object Shaders {
    val vertexShaderCode = """
    uniform mat4 uMVPMatrix;
    uniform float uTime;
    uniform float uSpeedTotal; 
    uniform float uSideOffset; 
    uniform float uRoadGap; 
    uniform float uFlowDir; 
    uniform float uPattern; 
    uniform float uColor;   
    uniform float uPointSizeScale;
    uniform float uRainbowStart;
    uniform float uRainbowEnd;
    uniform vec3 uAurora0;
    uniform vec3 uAurora1;
    uniform vec3 uAurora2;
    uniform vec3 uAurora3;
    uniform vec3 uAurora4;
    uniform vec3 uAurora5;

    attribute vec4 aPosition; 
    varying vec3 vColor;
    varying float vAlpha;

    void main() {
    
    
   
        
        
    float x = aPosition.x + uSideOffset;
    float z = aPosition.z; 
    float sZ = z - uSpeedTotal;
    float animTime = -uTime;
    // Speed factor: 0 at stop, 1 at 30km/h, 2 at 100km/h
    float speedFactor = clamp(uSpeedTotal * 0.0003, 0.0, 0.8);
    float h = 0.0;

    // --- NORMALIZED SPEED/WAVES ---
    
    if (uPattern < 0.5) {
        float side = sign(uSideOffset) * 0.7;
        h = sin(x * 0.004 + animTime * (0.3 + speedFactor * 0.4) + side) * 28.0
          + sin(sZ * 0.005 + animTime * (0.2 + speedFactor * 0.3) - side * 0.5) * 22.0
          + sin(x * 0.007 - sZ * 0.003 + animTime * (0.15 + speedFactor * 0.2) + side * 1.2) * 12.0;
    
    } else if (uPattern < 1.5) {
        float side = uSideOffset * 0.00015;
        h = sin(x * 0.003 + sZ * 0.002 + animTime * (0.15 + speedFactor * 0.2) + side) * 42.0
          + sin(x * 0.007 - sZ * 0.004 + animTime * (0.25 + speedFactor * 0.3) - side * 0.8) * 22.0
          + sin(x * 0.002 + sZ * 0.008 + animTime * (0.1  + speedFactor * 0.15) + side * 0.4) * 16.0
          + cos(sZ * 0.005 - x * 0.002 + animTime * (0.18 + speedFactor * 0.2)) * 10.0;
    
    } else if (uPattern < 2.5) {
        float drift = sign(uSideOffset) * 0.4;
        h = sin(x * 0.002 + animTime * (0.12 + speedFactor * 0.15 + drift * 0.05)) * 52.0
          + sin(x * 0.005 - animTime * (0.08 + speedFactor * 0.1  - drift * 0.03)) * 32.0
          + cos(sZ * 0.003 + animTime * (0.06 + speedFactor * 0.08) + drift) * 22.0
          + sin(sZ * 0.007 - animTime * (0.04 + speedFactor * 0.06) - drift * 0.5) * 12.0;
    
    } else if (uPattern < 3.5) {
        float phase = uSideOffset * 0.0008;
        h = sin(x * 0.0025 + sZ * 0.002 + animTime * (0.18 + speedFactor * 0.2) + phase) * 48.0
          + sin(x * 0.004  - sZ * 0.003 + animTime * (0.22 + speedFactor * 0.25) - phase * 0.6) * 32.0
          + sin(x * 0.006  + sZ * 0.005 - animTime * (0.14 + speedFactor * 0.18) + phase * 0.3) * 18.0
          + cos(x * 0.002  + sZ * 0.007 + animTime * (0.1  + speedFactor * 0.12)) * 10.0;
    
    } else if (uPattern < 4.5) {
        float timeShift = sign(uSideOffset) * 0.8;
        float d = length(vec2(x, sZ)) * 0.006;
        h = sin(d - animTime * (0.6 + speedFactor * 0.8) + timeShift) * 55.0
          + sin(d * 0.5 - animTime * (0.3 + speedFactor * 0.4) - timeShift * 0.5) * 28.0
          + sin(d * 1.5 - animTime * (0.9 + speedFactor * 1.0) + timeShift * 0.3) * 15.0;
    
    } else if (uPattern < 5.5) {
        float dir = sign(uSideOffset);
        h = sin(x * 0.003 + sZ * 0.003 * dir + animTime * (0.2  + speedFactor * 0.25))  * 38.0
          + sin(x * 0.005 - sZ * 0.002 * dir + animTime * (0.15 + speedFactor * 0.2))   * 28.0
          + cos(x * 0.002 + sZ * 0.006 * dir - animTime * (0.1  + speedFactor * 0.15))  * 20.0
          + sin(x * 0.008 + sZ * 0.001 * dir + animTime * (0.25 + speedFactor * 0.3))   * 12.0;
    
    } else if (uPattern < 6.5) {
        float lag = uSideOffset * 0.0005;
        h = sin(x * 0.004 + animTime * (0.1  + speedFactor * 0.15 + lag))            * 32.0
          + sin(x * 0.009 + sZ * 0.006 + animTime * (0.3 + speedFactor * 0.35) - lag) * 18.0
          + cos(sZ * 0.004 - animTime * (0.15 + speedFactor * 0.2  + lag * 0.5))      * 28.0
          + sin(x * 0.002 - sZ * 0.003 + animTime * (0.08 + speedFactor * 0.1))       * 14.0;
    
    } else if (uPattern < 7.5) {
        float s = uSideOffset * 0.0006;
        h = sin(x * 0.003 + sZ * 0.004 + animTime * (0.12 + speedFactor * 0.15) + s)       * 42.0
          + sin(x * 0.007 + sZ * 0.002 - animTime * (0.18 + speedFactor * 0.2)  - s)       * 26.0
          + cos(x * 0.004 - sZ * 0.005 + animTime * (0.08 + speedFactor * 0.1)  + s * 0.5) * 22.0
          + sin(x * 0.001 + sZ * 0.007 + animTime * (0.22 + speedFactor * 0.25) - s * 0.3) * 16.0
          + cos(x * 0.006 - sZ * 0.001 - animTime * (0.14 + speedFactor * 0.18))            * 10.0;
        h *= 3.0;
    
    } else if (uPattern < 8.5) {
        float weight = 0.6 + sign(uSideOffset) * 0.15;
        h = (sin(x * 0.005 + animTime * (0.08 + speedFactor * 0.12))             * 38.0
           + cos(sZ * 0.004 + animTime * (0.11 + speedFactor * 0.14))             * 32.0
           + sin((x + sZ) * 0.003 + animTime * (0.06 + speedFactor * 0.1))        * 28.0
           + sin(x * 0.002 - sZ * 0.004 + animTime * (0.09 + speedFactor * 0.12)) * 15.0) * weight;
    
    } else if (uPattern < 9.5) {
        float wind = sign(uSideOffset);
        h = sin(x * 0.006 + sZ * 0.001 * wind + animTime * (0.5  + speedFactor * 0.6))  * 22.0
          + sin(x * 0.003 + sZ * 0.008 * wind + animTime * (0.35 + speedFactor * 0.45)) * 22.0
          + sin(x * 0.009 - sZ * 0.003 * wind + animTime * (0.45 + speedFactor * 0.55)) * 16.0
          + cos(x * 0.004 + sZ * 0.005 * wind - animTime * (0.28 + speedFactor * 0.35)) * 12.0;
    
    } else if (uPattern < 10.5) {
        float tilt = uSideOffset * 0.00008;
        h = sin(x * 0.004 + animTime * (0.12 + speedFactor * 0.15) + tilt)          * 18.0
          + cos(sZ * 0.004 + animTime * (0.09 + speedFactor * 0.12) - tilt * 0.7)   * 16.0
          + sin((x - sZ) * 0.003 + animTime * (0.07 + speedFactor * 0.1))            * 13.0
          + cos((x + sZ) * 0.002 - animTime * (0.05 + speedFactor * 0.08) + tilt)    * 9.0;
    
    } else if (uPattern < 11.5) {
        float phase = sign(uSideOffset) * 0.6;
        h = sin(sZ * 0.005 + uTime * (0.25 + speedFactor * 0.3) + phase)             * 52.0
          + sin(sZ * 0.008 + x * 0.002 + uTime * (0.3 + speedFactor * 0.35) - phase) * 28.0
          + sin(x  * 0.003 - uTime * (0.1 + speedFactor * 0.12) + phase * 0.4)        * 16.0
          + cos(sZ * 0.003 - x * 0.001 - uTime * (0.18 + speedFactor * 0.2))          * 12.0;
    
    } else if (uPattern < 12.5) {
        float strand1 = sin(sZ * 0.008 + animTime * (0.3 + speedFactor * 0.4)) * 60.0;
        float strand2 = sin(sZ * 0.008 + animTime * (0.3 + speedFactor * 0.4) + 3.14159) * 60.0;
        float blendF  = smoothstep(-20.0, 20.0, x - strand1);
        h = mix(strand1, strand2, blendF) * 0.8
          + sin(x * 0.004 + sZ * 0.003 + animTime * (0.15 + speedFactor * 0.2)) * 15.0;
    
    } else if (uPattern < 13.5) {
        float cable = floor(x / 40.0);
        float seed  = fract(sin(cable * 127.1) * 43758.5);
        float bend  = sin(sZ * 0.005 + seed * 6.28 + animTime * (0.1  + seed * 0.15 + speedFactor * 0.2)) * 70.0;
        float slack = cos(sZ * 0.003 - seed * 3.14 + animTime * (0.08 + speedFactor * 0.15)) * 90.0;
        h = bend + slack + seed * 20.0;
    
    } else if (uPattern < 14.5) {
        float scanSpeed = animTime * (0.4 + speedFactor * 0.5);
        float scanPos   = mod(sZ * 0.01 + scanSpeed, 6.28);
        float beam      = exp(-pow(fract(scanPos / 6.28) - 0.5, 2.0) * 30.0);
        h = beam * 80.0
          + sin(x * 0.005 + sZ * 0.003 + animTime * (0.12 + speedFactor * 0.15)) * 20.0
          + cos(x * 0.003 - sZ * 0.004 + animTime * (0.08 + speedFactor * 0.1))  * 15.0;
    
    } else if (uPattern < 15.5) {
        float well1 = -70.0 / (1.0 + pow(length(vec2(x - sin(animTime * (0.1 + speedFactor * 0.1)) * 200.0, sZ + 400.0))  * 0.006, 2.0));
        float well2 = -50.0 / (1.0 + pow(length(vec2(x + cos(animTime * (0.08 + speedFactor * 0.08)) * 150.0, sZ + 800.0)) * 0.007, 2.0));
        float well3 = -40.0 / (1.0 + pow(length(vec2(x - sin(animTime * (0.12 + speedFactor * 0.1)) * 100.0, sZ + 1200.0)) * 0.008, 2.0));
        h = well1 + well2 + well3
          + sin(x * 0.003 + sZ * 0.002 + animTime * (0.1 + speedFactor * 0.12)) * 12.0;
    
    } else if (uPattern < 16.5) {
        float a  = sin(x  * 0.006 + animTime * (0.2  + speedFactor * 0.25)) * 30.0;
        float b  = sin(sZ * 0.005 + animTime * (0.15 + speedFactor * 0.2))  * 30.0;
        float c  = sin((x + sZ) * 0.004 + animTime * (0.18 + speedFactor * 0.22)) * 25.0;
        float d2 = sin(sqrt(x * x + sZ * sZ) * 0.005 - animTime * (0.25 + speedFactor * 0.3)) * 20.0;
        h = (sin(a + b) + sin(b + c) + sin(c + d2)) * 18.0;
    
    } else {
        float sideWeight = clamp(x / 800.0, -1.0, 1.0);
        float calm  = sin(x * 0.002 + sZ * 0.001 + animTime * (0.05 + speedFactor * 0.06)) * 10.0;
        float storm = sin(x * 0.008 + sZ * 0.007 + animTime * (0.35 + speedFactor * 0.45)) * 50.0
                    + cos(x * 0.006 - sZ * 0.005 + animTime * (0.28 + speedFactor * 0.35)) * 35.0
                    + sin(x * 0.004 + sZ * 0.009 - animTime * (0.22 + speedFactor * 0.28)) * 25.0;
        h = mix(calm, storm, sideWeight * 0.5 + 0.5);
    }

        // Road Gap flattening (Keeps the road area flat)
         h *= smoothstep(0.0, 300.0, abs(x) - (uRoadGap/2.0)); 
        gl_Position = uMVPMatrix * vec4(x, h - 60.0, z, 1.0);
        
        
        // ✅ NEW COLOR LOGIC
        float zP = clamp(abs(z) / 2200.0, 0.0, 1.0);
        float depth = 1.0 - zP; // 0=horizon, 1=foreground
        vColor = vec3(1.0, 0.0, 0.0);

        if (uColor < 0.5) { // Cyberpunk
            vec3 cA = vec3(1.0, 0.0, 0.5); vec3 cB = vec3(0.0, 1.0, 1.0);
            vColor = mix(cB, cA, depth);
        } else if (uColor < 1.5) { // Toxic Aurora
            vec3 cA = vec3(0.0, 1.0, 0.0); vec3 cB = vec3(0.0, 0.5, 1.0);
            vColor = mix(cB, cA, depth);
        } else if (uColor < 2.5) { // Blood Moon
            vec3 cA = vec3(1.0, 0.1, 0.0); vec3 cB = vec3(0.3, 0.0, 0.1);
            vColor = mix(cB, cA, depth);
        } else if (uColor < 3.5) { // Deep Sea
            vec3 cA = vec3(0.0, 1.0, 0.8); vec3 cB = vec3(0.0, 0.1, 0.4);
            vColor = mix(cB, cA, depth);
        } else if (uColor < 4.5) { // Gold Rush
            vec3 cA = vec3(1.0, 0.8, 0.0); vec3 cB = vec3(0.4, 0.2, 0.0);
            vColor = mix(cB, cA, depth);
        } else if (uColor < 5.5) { // Midnight
            vec3 cA = vec3(0.5, 0.0, 1.0); vec3 cB = vec3(0.0, 0.5, 1.0);
            vColor = mix(cB, cA, depth);
        } else if (uColor < 6.5) { // RAINBOW
            float hue = mod(uRainbowStart + (uRainbowEnd - uRainbowStart) * (1.0 - depth), 360.0);
            float h6 = hue / 60.0;
            float f = h6 - floor(h6);
            float q = 1.0 - f;
            if (h6 < 1.0)      vColor = vec3(1.0, f,   0.0);
            else if (h6 < 2.0) vColor = vec3(q,   1.0, 0.0);
            else if (h6 < 3.0) vColor = vec3(0.0, 1.0, f  );
            else if (h6 < 4.0) vColor = vec3(0.0, q,   1.0);
            else if (h6 < 5.0) vColor = vec3(f,   0.0, 1.0);
            else               vColor = vec3(1.0, 0.0, q  );
        } else { // AURORA
            float p = 1.0 - depth;
            vec3 bDim = uAurora5 * 0.3;
            if (p < 0.45)      vColor = uAurora0;
            else if (p < 0.60) vColor = mix(uAurora0, uAurora1, (p-0.45)/0.15);
            else if (p < 0.75) vColor = mix(uAurora1, uAurora2, (p-0.60)/0.15);
            else if (p < 0.85) vColor = mix(uAurora2, uAurora3, (p-0.75)/0.10);
            else if (p < 0.92) vColor = mix(uAurora3, uAurora4, (p-0.85)/0.07);
            else if (p < 0.97) vColor = mix(uAurora4, uAurora5, (p-0.92)/0.05);
            else               vColor = mix(uAurora5, bDim,     (p-0.97)/0.03);
        }

        vAlpha = smoothstep(-2800.0, -400.0, z) * (1.0 - smoothstep(600.0, 1800.0, abs(x)));
        
        // Multiply base size by our new scale
        float baseSize = mix(1.0, 5.0, vAlpha);  // 1px at horizon, 5px at foreground
        gl_PointSize = baseSize * uPointSizeScale;
    }
""".trimIndent()

    val fragmentShaderCode = """
        precision mediump float;
        varying vec3 vColor;
        varying float vAlpha;
        void main() {
        float d = distance(gl_PointCoord, vec2(0.5, 0.5));
        if (d > 0.5) discard;
        float edge = smoothstep(0.5, 0.49, d);
        gl_FragColor = vec4(vColor, vAlpha * edge);

        }
    """.trimIndent()


    val lineVertexShader = """
        uniform mat4 uMVPMatrix;
        uniform float uScrollZ;   
        uniform float uSideOffset; 
        attribute vec4 aPosition;
        varying float vAlpha;

        void main() {
            vec3 pos = aPosition.xyz;
            pos.x += uSideOffset;
            
            // Loop lines backward toward the car
            pos.z += uScrollZ; 

            gl_Position = uMVPMatrix * vec4(pos, 1.0);
            vAlpha = clamp(1.0 - (abs(pos.z) / 2000.0), 0.0, 1.0);
        }
    """.trimIndent()

    val lineFragmentShader = """
    precision mediump float;
    varying float vAlpha;
    void main() {
        gl_FragColor = vec4(0.9, 0.95, 1.0, vAlpha * 0.9);
    }
""".trimIndent()

}