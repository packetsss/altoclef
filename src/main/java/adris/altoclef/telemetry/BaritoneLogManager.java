package adris.altoclef.telemetry;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.text.Text;

/**
 * Captures Baritone log output into the current telemetry session directory so navigation decisions can be reviewed
 * alongside AltoClef's own logs.
 */
public final class BaritoneLogManager implements AutoCloseable {
    private static final String LOGGER_NAMESPACE = "baritone";
    private static final String APPENDER_NAME = "AltoClefBaritoneCapture";

    private final LoggerContext loggerContext;
    private final FileAppender appender;
    private final boolean createdLoggerConfig;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Level previousLoggerLevel;
    private final Map<String, Level> previousChildLoggerLevels = new HashMap<>();
    private final Set<String> managedLoggerNames = new HashSet<>();
    private Consumer<Text> originalLoggerConsumer;
    private final org.apache.logging.log4j.Logger baritoneLogger = LogManager.getLogger(LOGGER_NAMESPACE + ".events");

    public BaritoneLogManager(AltoClef mod) {
        Path sessionDir = mod.getTelemetrySessionDir();
        if (sessionDir == null) {
            loggerContext = null;
            appender = null;
            createdLoggerConfig = false;
            Debug.logWarning("Baritone log capture disabled: telemetry session directory unavailable.");
            return;
        }

        Path logFile = sessionDir.resolve("baritone.log");
        FileAppender preparedAppender = null;
        LoggerContext context = null;
        boolean ownsLoggerConfig = false;
        Level recoveredLoggerLevel = null;
        try {
            Files.createDirectories(logFile.getParent());

            PatternLayout layout = PatternLayout.newBuilder()
                    .withCharset(StandardCharsets.UTF_8)
                    .withPattern("%d{HH:mm:ss.SSS} [%t] %-5level %logger - %msg%n")
                    .build();

            preparedAppender = FileAppender.newBuilder()
                    .withFileName(logFile.toString())
                    .withAppend(false)
                    .withLocking(false)
                    .withImmediateFlush(true)
                    .setName(APPENDER_NAME)
                    .setLayout(layout)
                    .setIgnoreExceptions(true)
                    .build();

            preparedAppender.start();

            context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            configuration.addAppender(preparedAppender);

            LoggerConfig loggerConfig = configuration.getLoggerConfig(LOGGER_NAMESPACE);
            if (!LOGGER_NAMESPACE.equals(loggerConfig.getName())) {
                LoggerConfig dedicatedLogger = new LoggerConfig(LOGGER_NAMESPACE, Level.ALL, true);
                dedicatedLogger.addAppender(preparedAppender, Level.ALL, null);
                configuration.addLogger(LOGGER_NAMESPACE, dedicatedLogger);
                managedLoggerNames.add(LOGGER_NAMESPACE);
                loggerConfig = dedicatedLogger;
                ownsLoggerConfig = true;
            } else {
                managedLoggerNames.add(loggerConfig.getName());
                recoveredLoggerLevel = loggerConfig.getLevel();
                loggerConfig.setLevel(Level.ALL);
                loggerConfig.addAppender(preparedAppender, Level.ALL, null);
            }

            for (LoggerConfig config : configuration.getLoggers().values()) {
                String name = config.getName();
                if (name == null || name.isEmpty() || managedLoggerNames.contains(name)) {
                    continue;
                }
                if (name.startsWith(LOGGER_NAMESPACE + ".")) {
                    previousChildLoggerLevels.put(name, config.getLevel());
                    config.setLevel(Level.ALL);
                    config.addAppender(preparedAppender, Level.ALL, null);
                    managedLoggerNames.add(name);
                }
            }

            context.updateLoggers();
            enableVerboseBaritoneLogging();
            LogManager.getLogger(LOGGER_NAMESPACE + ".capture").debug("AltoClef Baritone log capture engaged.");
            Debug.logInternal(String.format("Baritone log capture active: %s", logFile));
        } catch (IOException ex) {
            Debug.logWarning(String.format("Failed to prepare Baritone log capture at %s: %s", logFile, ex.getMessage()));
            if (preparedAppender != null) {
                preparedAppender.stop();
            }
            loggerContext = null;
            appender = null;
            createdLoggerConfig = false;
            previousLoggerLevel = null;
            managedLoggerNames.clear();
            previousChildLoggerLevels.clear();
            return;
        }

        loggerContext = context;
        appender = preparedAppender;
        createdLoggerConfig = ownsLoggerConfig;
        previousLoggerLevel = recoveredLoggerLevel;

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    public boolean isActive() {
        return loggerContext != null && appender != null && !closed.get();
    }

    private void enableVerboseBaritoneLogging() {
        Settings baritoneSettings = BaritoneAPI.getSettings();
        if (baritoneSettings == null) {
            return;
        }
        Set<String> toggled = new HashSet<>();

        for (Field field : Settings.class.getFields()) {
            if (!Settings.Setting.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Object rawSetting;
            try {
                rawSetting = field.get(baritoneSettings);
            } catch (IllegalAccessException ex) {
                Debug.logInternal(String.format("Failed to read Baritone setting %s: %s", field.getName(), ex.getMessage()));
                continue;
            }
            if (!(rawSetting instanceof Settings.Setting<?> setting)) {
                continue;
            }
            Object currentValue = setting.value;
            if (!(currentValue instanceof Boolean) || !shouldForceLogging(field.getName())) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Settings.Setting<Boolean> booleanSetting = (Settings.Setting<Boolean>) setting;
            if (!Boolean.TRUE.equals(booleanSetting.value)) {
                booleanSetting.value = Boolean.TRUE;
                toggled.add(field.getName());
            }
        }

        forceLoggingSetting(baritoneSettings, "logger", toggled);
        forceLoggingSetting(baritoneSettings, "chatDebug", toggled);
        forceLoggingSetting(baritoneSettings, "pathDebug", toggled);
        forceLoggingSetting(baritoneSettings, "debugMode", toggled);

        injectLoggerConsumer(baritoneSettings);

        if (!toggled.isEmpty()) {
            Debug.logInternal(String.format("Enabled Baritone logging flags: %s", String.join(", ", toggled)));
        }
    }

    private boolean shouldForceLogging(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        return normalized.contains("log");
    }

    private void injectLoggerConsumer(Settings settings) {
        if (originalLoggerConsumer != null) {
            return;
        }
        Settings.Setting<Consumer<Text>> loggerSetting = settings.logger;
        originalLoggerConsumer = loggerSetting.value;
        Consumer<Text> forwardingConsumer = message -> {
            try {
                String plain = message != null ? message.getString() : "";
                if (plain != null && !plain.isEmpty()) {
                    baritoneLogger.debug(plain);
                } else if (message != null) {
                    baritoneLogger.debug(message);
                }
            } catch (Exception ex) {
                Debug.logInternal(String.format("Failed to forward Baritone log message: %s", ex.getMessage()));
            }
            if (originalLoggerConsumer != null) {
                originalLoggerConsumer.accept(message);
            }
        };
        loggerSetting.value = forwardingConsumer;
    }

    private void forceLoggingSetting(Settings settings, String fieldName, Set<String> toggled) {
        try {
            Field field = Settings.class.getField(fieldName);
            Object rawSetting = field.get(settings);
            if (rawSetting instanceof Settings.Setting<?> setting && setting.value instanceof Boolean value && !value) {
                @SuppressWarnings("unchecked")
                Settings.Setting<Boolean> booleanSetting = (Settings.Setting<Boolean>) setting;
                booleanSetting.value = Boolean.TRUE;
                toggled.add(fieldName);
            }
        } catch (NoSuchFieldException ignored) {
            // Field not present in this Baritone version.
        } catch (IllegalAccessException ex) {
            Debug.logInternal(String.format("Failed to enable Baritone setting %s: %s", fieldName, ex.getMessage()));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (loggerContext == null || appender == null) {
            return;
        }

        Configuration configuration = loggerContext.getConfiguration();
        for (String loggerName : managedLoggerNames) {
            LoggerConfig loggerConfig = configuration.getLoggerConfig(loggerName);
            if (loggerConfig == null || !loggerName.equals(loggerConfig.getName())) {
                continue;
            }
            loggerConfig.removeAppender(APPENDER_NAME);
            if (LOGGER_NAMESPACE.equals(loggerName)) {
                if (createdLoggerConfig) {
                    configuration.removeLogger(LOGGER_NAMESPACE);
                } else if (previousLoggerLevel != null) {
                    loggerConfig.setLevel(previousLoggerLevel);
                }
            } else {
                Level prior = previousChildLoggerLevels.get(loggerName);
                if (prior != null) {
                    loggerConfig.setLevel(prior);
                }
            }
        }

        configuration.getAppenders().remove(APPENDER_NAME);
        appender.stop();
        loggerContext.updateLoggers();

        restoreLoggerConsumer();
    }

    private void restoreLoggerConsumer() {
        if (originalLoggerConsumer == null) {
            return;
        }
        Settings settings = BaritoneAPI.getSettings();
        if (settings == null) {
            return;
        }
        settings.logger.value = originalLoggerConsumer;
        originalLoggerConsumer = null;
    }
}
