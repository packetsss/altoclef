package adris.altoclef.butler;

import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.serialization.IListConfigFile;

import java.util.HashSet;
import java.util.function.Consumer;

public class UserListFile implements IListConfigFile {

    private final HashSet<String> users = new HashSet<>();

    public static void load(String path, Consumer<UserListFile> onLoad) {
        ConfigHelper.loadListConfig(path, UserListFile::new, onLoad);
    }

    public boolean containsUser(String username) {
        return users.contains(username);
    }

    @Override
    public void onLoadStart() {
        users.clear();
    }

    @Override
    public void addLine(String line) {
        users.add(line);
    }
}
