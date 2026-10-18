package net.brindlewood.bridge;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Second line of defence for console commands that arrive from the bot. The bot already checks
 * its own allowlist, but this plugin refuses anything not listed in config.yml (console.allowed),
 * so a bug or a stolen bridge secret still cannot run arbitrary commands. Default: nothing allowed.
 */
final class CommandGate {
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]");
    private final List<String> allowed;

    CommandGate(List<String> allowedPrefixes) {
        this.allowed = allowedPrefixes.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    /** Returns null when the command may run, otherwise a short reason. */
    String check(String command) {
        if (command == null || command.isBlank()) return "empty command";
        if (command.length() > 250) return "command too long";
        if (CONTROL.matcher(command).find()) return "control characters are not allowed";
        String c = command.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) return "leading slash is not allowed";
        for (String a : allowed) {
            if (c.equals(a) || c.startsWith(a + " ")) return null;
        }
        return "not on this server's console allowlist";
    }
}
