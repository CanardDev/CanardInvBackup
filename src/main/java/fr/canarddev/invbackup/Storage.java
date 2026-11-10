package fr.canarddev.invbackup;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One YAML file per player in data/. Files are kept in a small cache and written
 * on a background thread (the text is prepared on the main thread, so it is always consistent).
 */
final class Storage {

    private final CanardInvBackup plugin;
    private final File dir;
    private final File indexFile;
    private final YamlConfiguration index;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CanardInvBackup-IO");
        t.setDaemon(true);
        return t;
    });

    /** Last 100 players used (online players are re-read from the cache, offline ones from disk). */
    private final Map<UUID, YamlConfiguration> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, YamlConfiguration> e) {
            return size() > 100;
        }
    };

    Storage(CanardInvBackup plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "data");
        if (!dir.exists() && !dir.mkdirs()) plugin.getLogger().warning("Could not create the data folder.");
        this.indexFile = new File(plugin.getDataFolder(), "names.yml");
        this.index = YamlConfiguration.loadConfiguration(indexFile);
    }

    private File file(UUID id) {
        return new File(dir, id + ".yml");
    }

    boolean exists(UUID id) {
        return cache.containsKey(id) || file(id).exists();
    }

    YamlConfiguration get(UUID id) {
        return cache.computeIfAbsent(id, k -> YamlConfiguration.loadConfiguration(file(k)));
    }

    void save(UUID id) {
        YamlConfiguration cfg = cache.get(id);
        if (cfg == null) return;
        write(file(id), cfg.saveToString());
    }

    /** Remembers name -> uuid so offline players can be found by name. */
    void remember(String name, UUID id) {
        String key = name.toLowerCase(Locale.ROOT);
        if (id.toString().equals(index.getString(key))) return;
        index.set(key, id.toString());
        write(indexFile, index.saveToString());
    }

    UUID byName(String name) {
        String s = index.getString(name.toLowerCase(Locale.ROOT));
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void write(File target, String text) {
        io.execute(() -> {
            try {
                File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
                Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save " + target.getName() + ": " + e.getMessage());
            }
        });
    }

    /** Waits for pending writes (server shutdown). */
    void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("Some backups were still being written.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
