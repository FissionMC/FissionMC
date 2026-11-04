package net.fissionmc.craft.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in for Paper's patched {@code LogUtils.getClassLogger()} (vanilla's LogUtils lacks it). */
public final class Loggers {
    private Loggers() {}

    public static Logger classLogger() {
        return LoggerFactory.getLogger(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass());
    }
}
