/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.effect;

import static com.google.android.exoplayer2.util.Assertions.checkArgument;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Pair;
import com.google.android.exoplayer2.util.FrameProcessingException;
import com.google.android.exoplayer2.util.GlProgram;
import com.google.android.exoplayer2.util.GlUtil;
import com.google.android.exoplayer2.util.Size;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;

/** Applies zero or more {@link TextureOverlay}s onto each frame. */
/* package */ final class OverlayTextureProcessor extends SingleFrameGlTextureProcessor {

  private static final int MATRIX_OFFSET = 0;

  private final GlProgram glProgram;
  private final ImmutableList<TextureOverlay> overlays;
  private final float[] aspectRatioMatrix;
  private final float[] overlayMatrix;
  private final float[] anchorMatrix;
  private final float[] transformationMatrix;

  private int videoWidth;
  private int videoHeight;

  /**
   * Creates a new instance.
   *
   * @param context The {@link Context}.
   * @param useHdr Whether input textures come from an HDR source. If {@code true}, colors will be
   *     in linear RGB BT.2020. If {@code false}, colors will be in linear RGB BT.709.
   * @throws FrameProcessingException If a problem occurs while reading shader files.
   */
  public OverlayTextureProcessor(
      Context context, boolean useHdr, ImmutableList<TextureOverlay> overlays)
      throws FrameProcessingException {
    super(useHdr);
    checkArgument(!useHdr, "OverlayTextureProcessor does not support HDR colors yet.");
    // TODO: If the limit on the number of overlays ever becomes and issue, investigate expanding it
    //  in relation to common device limits.
    checkArgument(
        overlays.size() <= 8,
        "OverlayTextureProcessor does not more than 8 overlays in the same processor yet.");
    this.overlays = overlays;
    aspectRatioMatrix = GlUtil.create4x4IdentityMatrix();
    overlayMatrix = GlUtil.create4x4IdentityMatrix();
    anchorMatrix = GlUtil.create4x4IdentityMatrix();
    transformationMatrix = GlUtil.create4x4IdentityMatrix();
    try {
      glProgram =
          new GlProgram(createVertexShader(overlays.size()), createFragmentShader(overlays.size()));
    } catch (GlUtil.GlException e) {
      throw new FrameProcessingException(e);
    }

    glProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
  }

  @Override
  public Size configure(int inputWidth, int inputHeight) {
    videoWidth = inputWidth;
    videoHeight = inputHeight;
    Size videoSize = new Size(inputWidth, inputHeight);
    for (TextureOverlay overlay : overlays) {
      overlay.configure(videoSize);
    }
    return videoSize;
  }

  @Override
  public void drawFrame(int inputTexId, long presentationTimeUs) throws FrameProcessingException {
    try {
      glProgram.use();
      if (!overlays.isEmpty()) {
        for (int texUnitIndex = 1; texUnitIndex <= overlays.size(); texUnitIndex++) {
          TextureOverlay overlay = overlays.get(texUnitIndex - 1);

          glProgram.setSamplerTexIdUniform(
              Util.formatInvariant("uOverlayTexSampler%d", texUnitIndex),
              overlay.getTextureId(presentationTimeUs),
              texUnitIndex);

          GlUtil.setToIdentity(aspectRatioMatrix);
          Matrix.scaleM(
              aspectRatioMatrix,
              MATRIX_OFFSET,
              videoWidth / (float) overlay.getTextureSize(presentationTimeUs).getWidth(),
              videoHeight / (float) overlay.getTextureSize(presentationTimeUs).getHeight(),
              /* z= */ 1);
          Matrix.invertM(
              overlayMatrix,
              MATRIX_OFFSET,
              overlay.getOverlaySettings(presentationTimeUs).matrix,
              MATRIX_OFFSET);
          Pair<Float, Float> overlayAnchor = overlay.getOverlaySettings(presentationTimeUs).anchor;
          GlUtil.setToIdentity(anchorMatrix);
          Matrix.translateM(
              anchorMatrix,
              /* mOffset= */ 0,
              overlayAnchor.first
                  * overlay.getTextureSize(presentationTimeUs).getWidth()
                  / videoWidth,
              overlayAnchor.second
                  * overlay.getTextureSize(presentationTimeUs).getHeight()
                  / videoHeight,
              /* z= */ 1);
          Matrix.multiplyMM(
              transformationMatrix,
              MATRIX_OFFSET,
              overlayMatrix,
              MATRIX_OFFSET,
              anchorMatrix,
              MATRIX_OFFSET);
          Matrix.multiplyMM(
              transformationMatrix,
              MATRIX_OFFSET,
              aspectRatioMatrix,
              MATRIX_OFFSET,
              transformationMatrix,
              MATRIX_OFFSET);
          glProgram.setFloatsUniform(
              Util.formatInvariant("uTransformationMatrix%d", texUnitIndex), transformationMatrix);

          glProgram.setFloatUniform(
              Util.formatInvariant("uOverlayAlpha%d", texUnitIndex),
              overlay.getOverlaySettings(presentationTimeUs).alpha);
        }
      }
      glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, /* texUnitIndex= */ 0);
      glProgram.bindAttributesAndUniforms();
      // The four-vertex triangle strip forms a quad.
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      GlUtil.checkGlError();
    } catch (GlUtil.GlException e) {
      throw new FrameProcessingException(e, presentationTimeUs);
    }
  }

  @Override
  public void release() throws FrameProcessingException {
    super.release();
    try {
      glProgram.delete();
    } catch (GlUtil.GlException e) {
      throw new FrameProcessingException(e);
    }
  }

  private static String createVertexShader(int numOverlays) {
    StringBuilder shader =
        new StringBuilder()
            .append("#version 100\n")
            .append("attribute vec4 aFramePosition;\n")
            .append("varying vec2 vVideoTexSamplingCoord0;\n");

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(Util.formatInvariant("uniform mat4 uTransformationMatrix%s;\n", texUnitIndex))
          .append(Util.formatInvariant("varying vec2 vOverlayTexSamplingCoord%s;\n", texUnitIndex));
    }

    shader
        .append("vec2 getTexSamplingCoord(vec2 ndcPosition){\n")
        .append("  return vec2(ndcPosition.x * 0.5 + 0.5, ndcPosition.y * 0.5 + 0.5);\n")
        .append("}\n")
        .append("void main() {\n")
        .append("  gl_Position = aFramePosition;\n")
        .append("  vVideoTexSamplingCoord0 = getTexSamplingCoord(aFramePosition.xy);\n");

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(Util.formatInvariant("  vec4 aOverlayPosition%d = \n", texUnitIndex))
          .append(
              Util.formatInvariant("  uTransformationMatrix%s * aFramePosition;\n", texUnitIndex))
          .append(
              Util.formatInvariant(
                  "  vOverlayTexSamplingCoord%d = getTexSamplingCoord(aOverlayPosition%d.xy);\n",
                  texUnitIndex, texUnitIndex));
    }

    shader.append("}\n");

    return shader.toString();
  }

  private static String createFragmentShader(int numOverlays) {
    StringBuilder shader =
        new StringBuilder()
            .append("#version 100\n")
            .append("precision mediump float;\n")
            .append("uniform sampler2D uVideoTexSampler0;\n")
            .append("varying vec2 vVideoTexSamplingCoord0;\n")
            .append("// Manually implementing the CLAMP_TO_BORDER texture wrapping option\n")
            .append(
                "// (https://open.gl/textures) since it's not implemented until OpenGL ES 3.2.\n")
            .append("vec4 getClampToBorderOverlayColor(\n")
            .append("    sampler2D texSampler, vec2 texSamplingCoord, float alpha){\n")
            .append("  if (texSamplingCoord.x > 1.0 || texSamplingCoord.x < 0.0\n")
            .append("      || texSamplingCoord.y > 1.0 || texSamplingCoord.y < 0.0) {\n")
            .append("    return vec4(0.0, 0.0, 0.0, 0.0);\n")
            .append("  } else {\n")
            .append("    vec4 overlayColor = vec4(texture2D(texSampler, texSamplingCoord));\n")
            .append("    overlayColor.a = alpha * overlayColor.a;\n")
            .append("    return overlayColor;\n")
            .append("  }\n")
            .append("}\n")
            .append("\n")
            .append("float getMixAlpha(float videoAlpha, float overlayAlpha) {\n")
            .append("  if (videoAlpha == 0.0) {\n")
            .append("    return 1.0;\n")
            .append("  } else {\n")
            .append("    return clamp(overlayAlpha/videoAlpha, 0.0, 1.0);\n")
            .append("  }\n")
            .append("}\n");

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(Util.formatInvariant("uniform sampler2D uOverlayTexSampler%d;\n", texUnitIndex))
          .append(Util.formatInvariant("uniform float uOverlayAlpha%d;\n", texUnitIndex))
          .append(Util.formatInvariant("varying vec2 vOverlayTexSamplingCoord%d;\n", texUnitIndex));
    }

    shader
        .append("void main() {\n")
        .append(
            "  vec4 videoColor = vec4(texture2D(uVideoTexSampler0, vVideoTexSamplingCoord0));\n")
        .append("  vec4 fragColor = videoColor;\n");

    for (int texUnitIndex = 1; texUnitIndex <= numOverlays; texUnitIndex++) {
      shader
          .append(
              Util.formatInvariant(
                  "  vec4 overlayColor%d = getClampToBorderOverlayColor(\n", texUnitIndex))
          .append(
              Util.formatInvariant(
                  "    uOverlayTexSampler%d, vOverlayTexSamplingCoord%d, uOverlayAlpha%d);\n",
                  texUnitIndex, texUnitIndex, texUnitIndex))
          .append("  fragColor = mix(\n")
          .append(
              Util.formatInvariant(
                  "    fragColor, overlayColor%d, getMixAlpha(videoColor.a, overlayColor%d.a));\n",
                  texUnitIndex, texUnitIndex));
    }

    shader.append("  gl_FragColor = fragColor;\n").append("}\n");

    return shader.toString();
  }
}
