package io.kensu.logging;

import io.opentracing.contrib.specialagent.Level;
import io.opentracing.contrib.specialagent.Logger;

public class KensuLogger {
    String clsName;
    Logger logger;

    public KensuLogger(final Class<?> cls){
        clsName = cls.getName();
        logger = Logger.getLogger(cls); // this one do not use cls name...
    }

    public void error(final String msg) {
        log(Level.SEVERE, msg, null);
    }

    public void warn(final String msg) {
        log(Level.WARNING, msg, null);
    }

    public void info(final String msg) {
        log(Level.INFO, msg, null);
    }

    public void debug(final String msg) {
        log(Level.FINE, msg, null);
    }

    public void trace(final String msg) {
        log(Level.FINER, msg, null);
    }

    public void error(final String msg, Throwable cause) {
        log(Level.SEVERE, msg, cause);
    }

    public void warn(final String msg, Throwable cause) {
        log(Level.WARNING, msg, cause);
    }

    public void info(final String msg, Throwable cause) {
        log(Level.INFO, msg, cause);
    }

    public void log(final Level level, final String msg, final Throwable thrown) {
        logger.log(level, msg, thrown);
    }

}
