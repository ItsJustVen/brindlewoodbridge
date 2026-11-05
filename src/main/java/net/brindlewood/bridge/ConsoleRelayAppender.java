package net.brindlewood.bridge;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.function.Consumer;

/**
 * Attaches to the server's root Log4j2 logger and forwards each formatted
 * line to the bridge as a "console" message. Attached/detached manually
 * from BrindleWoodBridgePlugin (see onEnable/onDisable) rather than via
 * Log4j's plugin-annotation discovery, since we only ever need one instance
 * wired to one running plugin.
 */
public class ConsoleRelayAppender extends AbstractAppender {
    private final Consumer<String> onLine;

    protected ConsoleRelayAppender(String name, Filter filter, Consumer<String> onLine) {
        super(name, filter, PatternLayout.newBuilder().withPattern("[%d{HH:mm:ss}] [%t/%level]: %msg").build(), false, null);
        this.onLine = onLine;
    }

    public static ConsoleRelayAppender create(Consumer<String> onLine) {
        ConsoleRelayAppender appender = new ConsoleRelayAppender("BrindleWoodBridgeConsoleRelay", null, onLine);
        appender.start();
        return appender;
    }

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    private static final java.util.regex.Pattern SECRET_CMD = java.util.regex.Pattern.compile(
            "(?i)(/|\\s)(login|l|register|reg|changepassword|changepass|password|passwd|authme|2fa|totp)\\s");

    /** Hides player IP addresses and drops lines that may carry passwords. Returns null to drop the line. */
    public static String redact(String line) {
        if (SECRET_CMD.matcher(line).find() && line.toLowerCase().contains("issued server command")) return null;
        return IPV4.matcher(line).replaceAll("x.x.x.x");
    }

    @Override
    public void append(LogEvent event) {
        // Skip our own bridge log lines to avoid an infinite relay loop.
        String logger = event.getLoggerName();
        if (logger != null && logger.contains("BrindleWoodBridge")) return;
        String formatted = new String(getLayout().toByteArray(event));
        onLine.accept(formatted);
    }
}
