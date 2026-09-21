package com.priolab.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads and stores the {@link Config} in {@code ~/.priolab/config.json}.
 */
public class ConfigManager {

    public static final Path CONFIG_DIR =
            Paths.get(System.getProperty("user.home"), ".priolab");
    public static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private Config config;

    /** @return true if a config file already exists on disk. */
    public boolean configExists() {
        return Files.isRegularFile(CONFIG_FILE);
    }

    /** The currently loaded config (may be {@code null} before {@link #load()}). */
    public Config get() {
        return config;
    }

    public Path configFile() {
        return CONFIG_FILE;
    }

    /** Read the config from disk into memory. */
    public Config load() {
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            config = mapper.readValue(reader, Config.class);
            if (config == null) {
                config = new Config();
            }
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config: " + CONFIG_FILE, e);
        }
    }

    /** Persist the given config, creating {@code ~/.priolab} if needed. */
    public void save(Config config) {
        this.config = config;
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                mapper.writeValue(writer, config);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config: " + CONFIG_FILE, e);
        }
    }
}
