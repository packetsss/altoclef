package adris.altoclef.commandsystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.args.Arg;
import adris.altoclef.commandsystem.args.GoToTargetArg;
import adris.altoclef.commandsystem.args.ListArg;
import adris.altoclef.commandsystem.exception.CommandException;

import java.util.stream.Stream;

public abstract class Command {

    private final ArgParser parser;
    private final Arg<?>[] args;
    private final String name;
    private final String description;
    private AltoClef mod;
    private Runnable onFinish = null;
    private int minArgCount = 0;

    public Command(String name, String description, Arg<?>... args) {
        this.name = name;
        this.description = description;
        parser = new ArgParser(args);
        this.args = args;
    }

    public void run(AltoClef mod, String line, Runnable onFinish) throws CommandException {
        this.onFinish = onFinish;
        this.mod = mod;
        parser.loadArgs(line, true);
        call(mod, parser);
    }

    protected void finish() {
        if (onFinish != null)
            //noinspection unchecked
            onFinish.run();
    }

    public String getHelpRepresentation() {
        return getHelpRepresentation(-1);
    }

    public String getHelpRepresentation(int fromArg) {
        StringBuilder sb;
        if (fromArg < 0) {
            fromArg = 0;
            sb = new StringBuilder(name).append(" ");
        } else {
            sb = new StringBuilder();
        }

        Arg<?>[] parserArgs = parser.getArgs();
        for (int i = fromArg; i < parserArgs.length; i++) {
            Arg<?> arg = parserArgs[i];
            sb.append(arg.getHelpRepresentation());

            if (i + 1 < parserArgs.length) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    protected void log(Object message) {
        Debug.logMessage(message.toString());
    }

    protected void logError(Object message) {
        Debug.logError(message.toString());
    }

    protected abstract void call(AltoClef mod, ArgParser parser) throws CommandException;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public final Stream<String> resolveTabCompletions(String line) throws CommandException {
        StringReader reader = new StringReader(line);
        reader.next(); // get rid of the command

        for (int i = 0; i < args.length; i++) {
            Arg<?> arg = args[i];

            Arg.ParseResult result = arg.consumeIfSupplied(reader);
            if (result == Arg.ParseResult.BAD_SYNTAX) {
                return Stream.empty();
            }

            if (result == Arg.ParseResult.NOT_FINISHED) {
                if (reader.hasNext()) {
                    if (i == args.length - 1) {
                        StringReader copy = reader.copy();
                        try {
                            arg.parseArg(copy);
                        } catch (CommandException ignored) {
                        }
                        String wasParsing = reader.peek();

                        // this means the error was not on the last element => suggestions are not valid
                        if (copy.hasNext() || (line.endsWith(" ") && !wasParsing.isBlank() && !(arg instanceof ListArg<?>))) {
                             if (!(arg instanceof GoToTargetArg)) {
                                return Stream.empty();
                            }
                        }
                    }
                }

                return arg.getSuggestions(reader);
            }
        }
        return Stream.empty();
    }

    public Arg<?>[] getArgs() {
        return args;
    }
}
