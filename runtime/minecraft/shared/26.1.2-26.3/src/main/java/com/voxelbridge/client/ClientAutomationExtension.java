package com.voxelbridge.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Optional classpath extension point used by local automation harnesses.
 *
 * <p>The production mod contains no scenario or golden-test implementation.
 * Unless {@code voxelbridge.clientAutomationClass} is explicitly supplied,
 * this hook is one predictable branch per client tick.</p>
 */
final class ClientAutomationExtension {
    private static final String EXTENSION_CLASS = System.getProperty(
        "voxelbridge.clientAutomationClass", "").trim();
    private static volatile Method tickMethod;

    private ClientAutomationExtension() {}

    static void onClientTick(Object minecraft) {
        if (EXTENSION_CLASS.isEmpty() || minecraft == null) return;
        try {
            Method method = tickMethod;
            if (method == null) {
                synchronized (ClientAutomationExtension.class) {
                    method = tickMethod;
                    if (method == null) {
                        Class<?> extension = Class.forName(
                            EXTENSION_CLASS, true, ClientAutomationExtension.class.getClassLoader());
                        method = extension.getMethod("onClientTick", minecraft.getClass());
                        tickMethod = method;
                    }
                }
            }
            method.invoke(null, minecraft);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Client automation extension failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                "Cannot load client automation extension " + EXTENSION_CLASS, failure);
        }
    }
}
