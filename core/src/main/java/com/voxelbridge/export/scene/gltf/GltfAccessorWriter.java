package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import de.javagl.jgltf.impl.v2.Accessor;
import de.javagl.jgltf.impl.v2.BufferView;
import de.javagl.jgltf.impl.v2.GlTF;

import java.util.Arrays;
import java.util.List;

/** Owns glTF buffer-view/accessor creation and numeric bounds. */
final class GltfAccessorWriter {
    private final GlTF gltf;

    GltfAccessorWriter(GlTF gltf) {
        this.gltf = gltf;
    }

    int addView(int bufferIndex, int byteOffset, int byteLength, int target) {
        BufferView view = new BufferView();
        view.setBuffer(bufferIndex);
        view.setByteOffset(byteOffset);
        view.setByteLength(byteLength);
        view.setTarget(target);

        List<de.javagl.jgltf.impl.v2.Buffer> buffers = gltf.getBuffers();
        if (buffers != null && bufferIndex < buffers.size()) {
            Integer bufferSize = buffers.get(bufferIndex).getByteLength();
            if (bufferSize != null) {
                long viewEnd = (long) byteOffset + byteLength;
                if (viewEnd > bufferSize) {
                    VoxelBridgeLogger.error(LogModule.GLTF, String.format(
                        "[GltfAccessorWriter][ERROR] BufferView exceeds buffer bounds: buffer[%d] size=%d, offset=%d, length=%d, end=%d",
                        bufferIndex, bufferSize, byteOffset, byteLength, viewEnd));
                }
            }
        }

        gltf.addBufferViews(view);
        return gltf.getBufferViews().size() - 1;
    }

    int addAccessor(int bufferView, int count, String type, int componentType, float[] min, float[] max) {
        Accessor accessor = new Accessor();
        accessor.setBufferView(bufferView);
        accessor.setComponentType(componentType);
        accessor.setCount(count);
        accessor.setType(type);
        if (min != null) accessor.setMin(numbers(min));
        if (max != null) accessor.setMax(numbers(max));
        gltf.addAccessors(accessor);
        return gltf.getAccessors().size() - 1;
    }

    static float[] min(float[] data, int stride) {
        float[] result = new float[stride];
        Arrays.fill(result, Float.MAX_VALUE);
        for (int offset = 0; offset < data.length; offset += stride) {
            for (int component = 0; component < stride; component++) {
                result[component] = Math.min(result[component], data[offset + component]);
            }
        }
        return result;
    }

    static float[] max(float[] data, int stride) {
        float[] result = new float[stride];
        Arrays.fill(result, -Float.MAX_VALUE);
        for (int offset = 0; offset < data.length; offset += stride) {
            for (int component = 0; component < stride; component++) {
                result[component] = Math.max(result[component], data[offset + component]);
            }
        }
        return result;
    }

    private static Number[] numbers(float[] values) {
        Number[] result = new Number[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }
}
