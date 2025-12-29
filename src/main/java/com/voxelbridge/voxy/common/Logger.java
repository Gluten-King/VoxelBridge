package com.voxelbridge.voxy.common;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Logger {
    public static boolean INSERT_CLASS = true;
    public static boolean SHUTUP = false;
    public static boolean SHUTUP_INFO = false;
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("Voxy");


    private static String callClsName() {
        String className = "";
        if (INSERT_CLASS) {
            var stackEntry = new Throwable().getStackTrace()[2];
            className = stackEntry.getClassName();
            var builder = new StringBuilder();
            var parts = className.split("\\.");
            for (int i = 0; i < parts.length; i++) {
                var part = parts[i];
                if (i < parts.length-1) {//-2
                    builder.append(part.charAt(0)).append(part.charAt(part.length()-1));
                } else {
                    builder.append(part);
                }
                if (i!=parts.length-1) {
                    builder.append(".");
                }
            }
            className = builder.toString();
        }
        return className;
    }

    public static void error(Object... args) {
        if (SHUTUP) {
            return;
        }
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }

        String error = (INSERT_CLASS?("["+callClsName()+"]: "):"") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" "));
        LOGGER.error(error, throwable);
        // Simplified: Assumed always in client context or safe to log
        error0(error);
    }

    private static void error0(String error) {
        try {
            var instance = Minecraft.getInstance();
            if (instance != null) {
                instance.executeIfPossible(() -> {
                    var player = Minecraft.getInstance().player;
                    if (player != null) player.displayClientMessage(Component.literal(error), true);
                });
            }
        } catch (Exception e) {
            // Ignore if Minecraft.getInstance() fails (e.g. not initialized)
        }
    }

    public static void warn(Object... args) {
        if (SHUTUP) {
            return;
        }
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        LOGGER.warn((INSERT_CLASS?("["+callClsName()+"]: "):"") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" ")), throwable);
    }

    public static void info(Object... args) {
        if (SHUTUP||SHUTUP_INFO) {
            return;
        }
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        LOGGER.info((INSERT_CLASS?("["+callClsName()+"]: "):"") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" ")), throwable);
    }

    private static String objToString(Object obj) {
        if (obj == null) {
            return "NULL";
        }
        if (obj.getClass().isArray()) {
            return Arrays.deepToString((Object[]) obj);
        }
        return obj.toString();
    }
}
