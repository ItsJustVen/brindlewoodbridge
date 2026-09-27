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

    @Override
    public void append(LogEvent event) {
        // Skip our own bridge log lines to avoid an infinite relay loop.
        String logger = event.getLoggerName();
        if (logger != null && logger.contains("BrindleWoodBridge")) return;
        String formatted = new String(getLayout().toByteArray(event));
        onLine.accept(formatted);
    }
}
