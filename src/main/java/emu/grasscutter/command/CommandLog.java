package emu.grasscutter.command;

/** Argument-free command descriptions for every logging entry point. */
public final class CommandLog {
    private CommandLog() {}

    public static String describe(String command) {
        if (command == null) return "<empty>";
        String label = command.stripLeading().split("\\s+", 2)[0];
        if (label.startsWith("/")) label = label.substring(1);
        return label.matches("[A-Za-z0-9_-]{1,64}") ? label + " [arguments redacted]" : "<redacted command>";
    }

    static boolean sensitive(CommandHandler handler) {
        return handler.getLabel().equals("account") || handler.getLabel().equals("reload");
    }
}
