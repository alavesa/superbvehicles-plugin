package fi.alavesa.superbvehicles.hud;

/**
 * Core shader overrides for the shader HUD: minecraft:core/text.vsh and text.fsh of Minecraft 26.3, copied VERBATIM
 * from the 26.3 client jar, with additions that are compiled only for GUI text (IS_GUI) and act only on vertices whose
 * colour carries the HUD marker (HudEncoding). World text (signs, name tags, text displays) never compiles the
 * additions; GUI text without the marker runs the vanilla code path unchanged.
 * <p>
 * 26.3 rules followed (verified against the client jar): #include instead of #moj_import, layout(location) on every
 * varying (vanilla uses 0..3, ours is 4), GL_ARB_separate_shader_objects, the OIT fragment structure kept as is, the
 * Globals block (GameTime) is bound for the text pipelines (TEXT_SNIPPET is built on GLOBALS_SNIPPET).
 * Glyph quads are emitted top-left, bottom-left, bottom-right, top-right (BakedSheetGlyph.render), so gl_VertexID % 4
 * gives the corner.
 */
public final class HudShaders {
    private HudShaders() { }

    public static String vertex(HudLayout layout) {
        return """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            #include <minecraft:fog.glsl>
            #include <minecraft:sample_lightmap.glsl>
            #endif

            #include <minecraft:dynamictransforms.glsl>
            #include <minecraft:projection.glsl>
            #ifdef IS_GUI
            #include <minecraft:globals.glsl>
            #endif

            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            layout(location = 3) in ivec2 UV2;
            #endif

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            uniform sampler2D Sampler2;
            layout(location = 0) out float sphericalVertexDistance;
            layout(location = 1) out float cylindricalVertexDistance;
            #endif

            layout(location = 2) out vec4 vertexColor;
            layout(location = 3) out vec2 texCoord0;

            #ifdef IS_GUI
            layout(location = 4) out vec4 sbvData;   // x: HUD element id (-1 = ordinary text), y: value 0..1, zw: corner in the quad
            """ + layout.glsl() + """

            void sbvHud() {
                ivec3 c = ivec3(round(Color.rgb * 255.0));
                if (c.r != 253 || (c.g & 224) != 160) return;          // not HUD data: vanilla text stays untouched
                int id = c.g & 31;
                float v = float(c.b) / 255.0;
                int corner = gl_VertexID % 4;                           // TL, BL, BR, TR
                vec2 k = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
                vec4 rect = SBV_RECT[id];
                vec2 pivot = SBV_PIVOT[id];
                int kind = SBV_KIND[id];
                int profile = SBV_PROFILE[id];
                vec2 local = k * rect.zw;
                if (kind == SBV_ROTATE || kind == SBV_ROTATE_JITTER) {
                    float rot = v * 6.2831853 - 3.1415927;
                    if (kind == SBV_ROTATE_JITTER) rot += sin(GameTime * 24000.0 * 3.1) * 0.035;
                    vec2 d = local - pivot;
                    float cs = cos(rot), sn = sin(rot);
                    local = vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs) + pivot;   // y points down: positive = clockwise
                } else if (kind == SBV_TRANSLATE_Y) {
                    local.y += (v - 0.5) * 2.0 * SBV_PITCH_RANGE;
                }
                vec2 guiSize = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);
                vec2 gui = guiSize * SBV_ANCHOR[profile] + (SBV_OFFSET[profile] + rect.xy + local) * SBV_SCALE[profile];
                vec4 clip = ProjMat * vec4(gui, 0.0, 1.0);
                gl_Position = vec4(clip.xy * gl_Position.w, gl_Position.z, gl_Position.w);
                vertexColor = vec4(1.0, 1.0, 1.0, Color.a);
                sbvData = vec4(float(id), v, k);
            }
            #endif

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                sphericalVertexDistance = fog_spherical_distance(Position);
                cylindricalVertexDistance = fog_cylindrical_distance(Position);
                vertexColor = Color * sample_lightmap(Sampler2, UV2);
            #else
                vertexColor = Color;
            #endif
                texCoord0 = UV0;
            #ifdef IS_GUI
                sbvData = vec4(-1.0, 0.0, 0.0, 0.0);
                sbvHud();
            #endif
            }
            """;
    }

    public static String fragment(HudLayout layout) {
        return """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            #include <minecraft:fog.glsl>
            #endif

            #include <minecraft:dynamictransforms.glsl>
            #include <minecraft:oit.glsl>
            #ifdef IS_GUI
            #include <minecraft:globals.glsl>
            #endif

            uniform sampler2D Sampler0;

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            layout(location = 0) in float sphericalVertexDistance;
            layout(location = 1) in float cylindricalVertexDistance;
            #endif

            layout(location = 2) in vec4 vertexColor;
            layout(location = 3) in vec2 texCoord0;

            #ifdef IS_GUI
            layout(location = 4) in vec4 sbvData;
            """ + layout.glsl() + """

            vec3 sbvRamp(float v) {
                return v > 0.5 ? mix(vec3(1.0, 0.85, 0.15), vec3(0.25, 0.9, 0.3), (v - 0.5) * 2.0) : mix(vec3(0.95, 0.2, 0.15), vec3(1.0, 0.85, 0.15), v * 2.0);
            }

            vec4 sbvShade(vec4 color) {
                int id = int(sbvData.x + 0.5);
                float v = sbvData.y;
                vec2 k = sbvData.zw;
                int kind = SBV_KIND[id];
                if (kind == SBV_FILL_V) {
                    if (1.0 - k.y > v) color.a = 0.0;
                    color.rgb *= sbvRamp(v);
                } else if (kind == SBV_FILL_ARC) {
                    vec2 d = k - vec2(0.5);
                    float theta = degrees(atan(d.x, -d.y));            // 0 = up, +90 = right (clockwise)
                    float t = (theta + 135.0) / 270.0;
                    if (t < 0.0 || t > 1.0 || t > v) color.a = 0.0;
                    color.rgb *= sbvRamp(v);
                } else if (kind == SBV_BLINK) {
                    color.a *= step(0.5, fract(GameTime * 2400.0));    // 2 Hz
                } else if (kind == SBV_FACE_DARK) {
                    if (v < 0.002) color.rgb *= 0.35;                  // empty tank
                } else if (kind == SBV_ROTATE_JITTER) {
                    color.rgb = mix(color.rgb, vec3(1.0, 0.25, 0.2), 0.6);   // over the redline
                }
                color.a *= SBV_OPACITY[SBV_PROFILE[id]];
                return color;
            }
            #endif

            #ifndef OIT_ALPHA_ONLY
            layout(location = 0) out vec4 fragColor;
            #endif

            vec4 calculateFinalColor(vec4 color) {
                #ifdef OIT_ACCUMULATE
                color = sampleColorForAccumulation(color);
                #endif

                #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

                #ifdef OIT_ACCUMULATE
                vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
                #else
                vec4 fogColor = FogColor;
                #endif

                color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
                #endif

                return color;
            }

            void main() {
                #ifdef IS_GRAYSCALE
                vec4 texColor = texture(Sampler0, texCoord0).rrrr;
                #else
                vec4 texColor = texture(Sampler0, texCoord0);
                #endif

                vec4 color = texColor * vertexColor * ColorModulator;
            #ifdef IS_GUI
                if (sbvData.x > -0.5) color = sbvShade(color);
            #endif

                if (color.a < 0.1) {
                    discard;
                }

                #ifdef OIT_ALPHA_ONLY
                executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
                #else
                fragColor = calculateFinalColor(color);
                #endif
            }
            """;
    }
}
