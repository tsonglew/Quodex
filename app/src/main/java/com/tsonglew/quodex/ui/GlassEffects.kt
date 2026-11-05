package com.tsonglew.quodex.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Each surface owns its shader. Uniforms change; shader compilation never happens in draw. */
@RequiresApi(33)
internal class GlassLensEffect(blurPixels: Float) {
    private val shader = RuntimeShader(SOURCE)
    private val blur = RenderEffect.createBlurEffect(blurPixels, blurPixels, Shader.TileMode.CLAMP)
    private var parameters: List<Float>? = null
    private var inputKey: Any? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
        private set

    fun update(width: Float, height: Float, padding: Float, radius: Float, rim: Float,
        saturation: Float, brightness: Float, split: Float, tokens: GlassTokens, readableInset: Float, contentKey: Any) {
        val next = listOf(width, height, padding, radius, rim, saturation, brightness, split,
            tokens.tint.red, tokens.tint.green, tokens.tint.blue, tokens.tint.alpha, tokens.edgeAlpha, readableInset)
        if (next == parameters && contentKey == inputKey) return
        parameters = next
        inputKey = contentKey
        shader.setFloatUniform("dimensions", width, height)
        shader.setFloatUniform("padding", padding)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("rim", rim)
        shader.setFloatUniform("saturation", saturation)
        shader.setFloatUniform("brightness", brightness)
        shader.setFloatUniform("split", split)
        shader.setFloatUniform("tint", tokens.tint.red, tokens.tint.green, tokens.tint.blue, tokens.tint.alpha)
        shader.setFloatUniform("edgeAlpha", tokens.edgeAlpha)
        shader.setFloatUniform("readableInset", readableInset)
        // Android RenderEffect snapshots the shader's native instance/uniform values.
        // Rebind only when parameters change; the compiled RuntimeShader remains cached.
        effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "scene"), blur)
            .asComposeRenderEffect()
    }

    companion object {
        // The input is the page display list only, never the dialog text or controls.
        private const val SOURCE = """
            uniform shader scene;
            uniform float2 dimensions;
            uniform float padding;
            uniform float radius;
            uniform float rim;
            uniform float saturation;
            uniform float brightness;
            uniform float split;
            uniform float4 tint;
            uniform float edgeAlpha;
            uniform float readableInset;

            half4 corrected(float2 p) {
                half4 c = scene.eval(p);
                float3 rgb = float3(c.rgb) / max(float(c.a), 0.0001);
                float gray = dot(rgb, float3(0.299, 0.587, 0.114));
                rgb = clamp((gray + (rgb - gray) * saturation) * brightness, 0.0, 1.0);
                return half4(half3(rgb * float(c.a)), c.a);
            }
            half4 main(float2 position) {
                float2 p = position - float2(padding) - dimensions * 0.5;
                float2 q = abs(p) - (dimensions * 0.5 - float2(radius));
                float2 outside = max(q, float2(0.0));
                float distance = -(length(outside) + min(max(q.x, q.y), 0.0) - radius);
                float2 normal;
                if (length(outside) > 0.001) normal = normalize(outside) * sign(p);
                else normal = q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y));
                float bend = rim > 0.0 ? 1.1 * rim * pow(1.0 - clamp(distance / max(rim, 0.001), 0.0, 1.0), 2.6) : 0.0;
                float2 displacement = -normal * bend;
                half4 red = corrected(position + displacement * (1.0 + split));
                half4 green = corrected(position + displacement);
                half4 blue = corrected(position + displacement * (1.0 - split));
                // Keep a clear bevel, but reach the contrast-safe tint before content padding begins.
                float alpha = mix(edgeAlpha, tint.a, smoothstep(0.0, readableInset, max(distance, 0.0)));
                half4 backdrop = half4(red.r, green.g, blue.b, green.a);
                return half4(half3(tint.rgb * alpha), half(alpha)) + backdrop * half(1.0 - alpha);
            }
        """
    }
}

internal fun basicGlassBlur(blurPixels: Float): androidx.compose.ui.graphics.RenderEffect? =
    if (Build.VERSION.SDK_INT >= 31) RenderEffect.createBlurEffect(blurPixels, blurPixels,
        Shader.TileMode.CLAMP).asComposeRenderEffect() else null

internal class GlassBlurEffect(private val pixels: Float) {
    private var inputKey: Any? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null
    fun forInput(key: Any): androidx.compose.ui.graphics.RenderEffect? {
        if (key != inputKey) {
            inputKey = key
            effect = basicGlassBlur(pixels)
        }
        return effect
    }
}
