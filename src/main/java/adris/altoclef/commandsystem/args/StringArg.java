package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.StringReader;

import java.util.stream.Stream;

public class StringArg extends Arg<String> {

    public StringArg(String name) {
        super(name);
    }

    public StringArg(String name, String defaultValue) {
        super(name, defaultValue);
    }

    public StringArg(String name, String defaultValue, boolean showDefault) {
        super(name, defaultValue, showDefault);
    }


    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return Stream.empty();
    }

    @Override
    protected StringParser<String> getParser() {
        return StringReader::next;
    }

    @Override
    public String getTypeName() {
        return "String";
    }

    @Override
    public Class<String> getType() {
        return String.class;
    }

}
