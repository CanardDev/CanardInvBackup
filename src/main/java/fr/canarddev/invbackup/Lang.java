package fr.canarddev.invbackup;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Messages loaded from messages_<language>.yml (en / fr), editable in the plugin folder. */
public final class Lang {

    private final YamlConfiguration msg;

    public Lang(JavaPlugin plugin, String language) {
        for (String c : new String[]{"en", "fr"}) {
            File f = new File(plugin.getDataFolder(), "messages_" + c + ".yml");
            if (!f.exists()) plugin.saveResource("messages_" + c + ".yml", false);
        }
        String code = language == null ? "en" : language.toLowerCase(Locale.ROOT);
        File f = new File(plugin.getDataFolder(), "messages_" + code + ".yml");
        if (!f.exists()) {
            plugin.getLogger().warning("messages_" + code + ".yml not found, using English.");
            code = "en";
            f = new File(plugin.getDataFolder(), "messages_en.yml");
        }
        msg = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource("messages_" + code + ".yml")) {
            if (in != null) msg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
    }

    public YamlConfiguration file() { return msg; }

    /** Raw text with placeholders replaced (%key% -> value), colors applied. */
    public String get(String key, Object... kv) {
        String s = msg.getString(key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
        return color(s);
    }

    public List<String> list(String key) { return msg.getStringList(key); }

    public void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(get("prefix") + get(key, kv));
    }

    /** &a / &l codes and &#rrggbb hex colors. */
    public static String color(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 8 <= s.length() && s.charAt(i + 1) == '#'
                    && s.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                b.append('§').append('x');
                for (char h : s.substring(i + 2, i + 8).toCharArray()) b.append('§').append(h);
                i += 7;
            } else if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                b.append('§').append(Character.toLowerCase(s.charAt(i + 1)));
                i++;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
