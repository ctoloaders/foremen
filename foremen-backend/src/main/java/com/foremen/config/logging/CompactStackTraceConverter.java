package com.foremen.config.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/**
 * Logback throwable converter that compresses stack traces to the frames that matter for us.
 * <p>
 * For each throwable in the chain (including every {@code Caused by:} and {@code Suppressed:}),
 * only frames whose class name starts with one of {@link #KEPT_PREFIXES} are printed verbatim.
 * Any contiguous run of non-matching (framework / JDK) frames is collapsed into a single
 * {@code ... N frames omitted} marker, so the useful application frames and the full causal chain
 * stay visible without the noise.
 * <p>
 * The built-in {@code %throwable{exclude=...}} option is <em>not</em> honoured by logback-core's
 * converter (that feature lives in logstash-logback-encoder), which is why a custom converter is
 * used here instead of pattern options.
 * <p>
 * Wired in via {@code logback-spring.xml} as a {@code <conversionRule>} for the {@code %compactEx}
 * word. Adjust {@link #KEPT_PREFIXES} if the application gains modules under other packages.
 */
public class CompactStackTraceConverter extends ThrowableProxyConverter {

    /** Class-name prefixes whose frames are always kept in the printed trace. */
    private static final String[] KEPT_PREFIXES = {"com.foremen."};

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (IThrowableProxy current = tp; current != null; current = current.getCause()) {
            appendThrowable(sb, current, first);
            first = false;
        }
        return sb.toString();
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy tp, boolean first) {
        if (!first) {
            sb.append("Caused by: ");
        }
        sb.append(tp.getClassName());
        if (tp.getMessage() != null) {
            sb.append(": ").append(tp.getMessage());
        }
        sb.append(CoreConstantsNewline.NL);

        StackTraceElementProxy[] frames = tp.getStackTraceElementProxyArray();
        if (frames == null) {
            return;
        }

        int omitted = 0;
        for (StackTraceElementProxy frame : frames) {
            String className = frame.getStackTraceElement().getClassName();
            if (isKept(className)) {
                flushOmitted(sb, omitted);
                omitted = 0;
                sb.append('\t').append(frame.getStackTraceElement().toString()).append(CoreConstantsNewline.NL);
            } else {
                omitted++;
            }
        }
        flushOmitted(sb, omitted);
    }

    private void flushOmitted(StringBuilder sb, int omitted) {
        if (omitted > 0) {
            sb.append("\t... ").append(omitted).append(" frames omitted").append(CoreConstantsNewline.NL);
        }
    }

    private boolean isKept(String className) {
        for (String prefix : KEPT_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Small holder so we do not depend on a specific CoreConstants field name across versions. */
    private static final class CoreConstantsNewline {
        static final String NL = System.lineSeparator();
    }
}
