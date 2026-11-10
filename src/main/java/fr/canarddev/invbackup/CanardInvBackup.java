package fr.canarddev.invbackup;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CanardInvBackup - saves players' inventory + ender chest (on quit, death, shutdown,
 * at a chosen time or by command), lets staff look at them and give them back.
 */
public final class CanardInvBackup extends JavaPlugin implements Listener, TabExecutor {

    static final int INV_SLOTS = 41;   // 0-35 inventory, 36-39 armor, 40 off-hand
    static final int ENDER_SLOTS = 27;

    private Lang lang;
    private Storage storage;

    private int maxBackups;
    private boolean onQuit, onDeath, onShutdown, scheduledOn;
    private String scheduledTime;
    private ZoneId zone;
    private DateTimeFormatter dateFormat;
    private String lastScheduledDay = "";

    /** Read-only menus. */
    static final class View implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        storage = new Storage(this);
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("invbackup");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // scheduled backup, checked every 20 seconds
        Bukkit.getScheduler().runTaskTimer(this, this::scheduledCheck, 20L * 20, 20L * 20);
    }

    @Override
    public void onDisable() {
        if (storage == null) return;
        if (onShutdown) for (Player p : Bukkit.getOnlinePlayers()) backup(p, "shutdown");
        storage.close();
    }

    private void load() {
        reloadConfig();
        FileConfiguration c = getConfig();
        lang = new Lang(this, c.getString("language", "en"));
        maxBackups = Math.max(1, c.getInt("max-backups", 5));
        onQuit = c.getBoolean("save-on.quit", true);
        onDeath = c.getBoolean("save-on.death", true);
        onShutdown = c.getBoolean("save-on.shutdown", true);
        scheduledOn = c.getBoolean("scheduled.enabled", false);
        scheduledTime = c.getString("scheduled.time", "03:55");
        try {
            zone = ZoneId.of(c.getString("timezone", "Europe/Paris"));
        } catch (Exception e) {
            getLogger().warning("Unknown timezone in config.yml, using the server's.");
            zone = ZoneId.systemDefault();
        }
        try {
            dateFormat = DateTimeFormatter.ofPattern(c.getString("date-format", "dd/MM/yyyy HH:mm:ss"));
        } catch (Exception e) {
            dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        }
    }

    // ================================================================== backups

    /** Saves inventory + ender chest of an online player. */
    public void backup(Player p, String reason) {
        UUID id = p.getUniqueId();
        YamlConfiguration cfg = storage.get(id);
        int n = cfg.getInt("next", 0) + 1;
        cfg.set("next", n);
        cfg.set("name", p.getName());
        String base = "backups." + n;
        cfg.set(base + ".time", System.currentTimeMillis());
        cfg.set(base + ".reason", reason);
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < Math.min(inv.length, INV_SLOTS); i++) {
            if (inv[i] != null && inv[i].getType() != Material.AIR) cfg.set(base + ".inv." + i, inv[i]);
        }
        ItemStack[] ender = p.getEnderChest().getContents();
        for (int i = 0; i < Math.min(ender.length, ENDER_SLOTS); i++) {
            if (ender[i] != null && ender[i].getType() != Material.AIR) cfg.set(base + ".ender." + i, ender[i]);
        }
        // keep only the newest ones
        List<Integer> ids = ids(cfg);
        while (ids.size() > maxBackups) cfg.set("backups." + ids.remove(0), null);
        storage.remember(p.getName(), id);
        storage.save(id);
    }

    /** Backup ids, oldest first. */
    private static List<Integer> ids(YamlConfiguration cfg) {
        List<Integer> out = new ArrayList<>();
        ConfigurationSection s = cfg.getConfigurationSection("backups");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            try {
                out.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
        }
        out.sort(Integer::compare);
        return out;
    }

    /** Number shown to staff (1 = newest) -> section, or null. */
    private ConfigurationSection pick(YamlConfiguration cfg, int shown) {
        List<Integer> ids = ids(cfg);
        if (shown < 1 || shown > ids.size()) return null;
        return cfg.getConfigurationSection("backups." + ids.get(ids.size() - shown));
    }

    private String date(long millis) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(dateFormat);
    }

    private String reason(String key) {
        return lang.get("reasons." + key);
    }

    private static Map<Integer, ItemStack> items(ConfigurationSection s, String part) {
        Map<Integer, ItemStack> out = new HashMap<>();
        ConfigurationSection p = s.getConfigurationSection(part);
        if (p == null) return out;
        for (String k : p.getKeys(false)) {
            ItemStack it = p.getItemStack(k);
            try {
                if (it != null) out.put(Integer.parseInt(k), it.clone());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ================================================================== automatic

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (onQuit) backup(e.getPlayer(), "quit");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (onDeath) backup(e.getEntity(), "death");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        storage.remember(e.getPlayer().getName(), e.getPlayer().getUniqueId());
    }

    private void scheduledCheck() {
        if (!scheduledOn) return;
        ZonedDateTime now = ZonedDateTime.now(zone);
        String hhmm = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        String day = now.toLocalDate().toString();
        if (!hhmm.equals(scheduledTime) || day.equals(lastScheduledDay)) return;
        lastScheduledDay = day;
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            backup(p, "scheduled");
            n++;
        }
        getLogger().info(Lang.color(lang.get("scheduled-console", "count", n)).replaceAll("§.", ""));
    }

    // ================================================================== menus

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof View) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof View) e.setCancelled(true);
    }

    private void openInventory(Player viewer, String name, int shown, ConfigurationSection b) {
        View holder = new View();
        Inventory gui = Bukkit.createInventory(holder, 45, lang.get("gui-inv-title", "player", name, "n", shown));
        holder.inv = gui;
        Map<Integer, ItemStack> it = items(b, "inv");
        // rows 1-3: main inventory, row 4: hotbar
        for (int s = 0; s < 36; s++) {
            ItemStack i = it.get(s);
            if (i != null) gui.setItem(s < 9 ? 27 + s : s - 9, i);
        }
        // row 5: helmet, chestplate, leggings, boots, off-hand
        for (int k = 0; k < 4; k++) {
            ItemStack i = it.get(39 - k);
            if (i != null) gui.setItem(36 + k, i);
        }
        if (it.get(40) != null) gui.setItem(40, it.get(40));
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int s = 41; s <= 43; s++) gui.setItem(s, pane);
        List<String> lore = new ArrayList<>();
        for (String l : lang.list("gui-info-lore"))
            lore.add(Lang.color(l.replace("%date%", date(b.getLong("time")))
                    .replace("%reason%", reason(b.getString("reason", "manual")))
                    .replace("%player%", name).replace("%n%", String.valueOf(shown))));
        gui.setItem(44, named(Material.PAPER, lang.get("gui-info-name", "n", shown), lore));
        viewer.openInventory(gui);
    }

    private void openEnder(Player viewer, String name, int shown, ConfigurationSection b) {
        View holder = new View();
        Inventory gui = Bukkit.createInventory(holder, 27, lang.get("gui-ender-title", "player", name, "n", shown,
                "date", date(b.getLong("time"))));
        holder.inv = gui;
        for (Map.Entry<Integer, ItemStack> en : items(b, "ender").entrySet())
            if (en.getKey() < 27) gui.setItem(en.getKey(), en.getValue());
        viewer.openInventory(gui);
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            i.setItemMeta(meta);
        }
        return i;
    }

    // ================================================================== commands

    /** Online player, then our own name list, then players who already joined. */
    private UUID resolve(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        UUID id = storage.byName(name);
        if (id != null) return id;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op != null ? op.getUniqueId() : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("invbackup.use")) {
            lang.send(sender, "no-permission");
            return true;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> {
                if (a.length < 2) { lang.send(sender, "usage-list", "label", label); return true; }
                list(sender, label, a[1]);
            }
            case "view", "ender" -> {
                if (!(sender instanceof Player viewer)) { lang.send(sender, "players-only"); return true; }
                if (a.length < 2) { lang.send(sender, "usage-" + sub, "label", label); return true; }
                UUID id = resolve(a[1]);
                if (id == null || !storage.exists(id)) { lang.send(sender, "no-backups", "player", a[1]); return true; }
                int n = a.length > 2 ? parse(a[2]) : 1;
                ConfigurationSection b = pick(storage.get(id), n);
                if (b == null) { lang.send(sender, "backup-not-found", "label", label, "player", a[1]); return true; }
                String name = storage.get(id).getString("name", a[1]);
                if (sub.equals("view")) openInventory(viewer, name, n, b);
                else openEnder(viewer, name, n, b);
            }
            case "restore" -> restore(sender, label, a);
            case "save" -> {
                if (a.length > 1 && !a[1].equalsIgnoreCase("all")) {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { lang.send(sender, "must-be-online", "player", a[1]); return true; }
                    backup(t, "manual");
                    lang.send(sender, "saved-player", "player", t.getName());
                } else {
                    int n = 0;
                    for (Player p : Bukkit.getOnlinePlayers()) { backup(p, "manual"); n++; }
                    lang.send(sender, "saved-all", "count", n);
                }
            }
            case "reload" -> {
                load();
                lang.send(sender, "reloaded");
            }
            default -> {
                for (String l : lang.list("help")) sender.sendMessage(Lang.color(l.replace("%label%", label)));
            }
        }
        return true;
    }

    private void list(CommandSender sender, String label, String name) {
        UUID id = resolve(name);
        if (id == null || !storage.exists(id)) { lang.send(sender, "no-backups", "player", name); return; }
        YamlConfiguration cfg = storage.get(id);
        List<Integer> ids = ids(cfg);
        if (ids.isEmpty()) { lang.send(sender, "no-backups", "player", name); return; }
        String real = cfg.getString("name", name);
        sender.sendMessage(lang.get("list-header", "player", real, "count", ids.size()));
        for (int shown = 1; shown <= ids.size(); shown++) {
            ConfigurationSection b = cfg.getConfigurationSection("backups." + ids.get(ids.size() - shown));
            if (b == null) continue;
            sender.sendMessage(lang.get("list-line", "n", shown, "date", date(b.getLong("time")),
                    "reason", reason(b.getString("reason", "manual"))));
        }
        sender.sendMessage(lang.get("list-footer", "label", label, "player", real));
    }

    private void restore(CommandSender sender, String label, String[] a) {
        if (a.length < 3) { lang.send(sender, "usage-restore", "label", label); return; }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { lang.send(sender, "must-be-online", "player", a[1]); return; }
        String what = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "all";
        if (!what.equals("inv") && !what.equals("ender") && !what.equals("all")) {
            lang.send(sender, "usage-restore", "label", label);
            return;
        }
        int n = parse(a[2]);
        ConfigurationSection b = pick(storage.get(target.getUniqueId()), n);
        if (b == null) { lang.send(sender, "backup-not-found", "label", label, "player", a[1]); return; }
        // copy BEFORE making the safety backup (it could push this one out)
        Map<Integer, ItemStack> inv = items(b, "inv");
        Map<Integer, ItemStack> ender = items(b, "ender");
        String date = date(b.getLong("time"));
        backup(target, "before-restore");
        if (!what.equals("ender")) {
            target.getInventory().clear();
            for (Map.Entry<Integer, ItemStack> en : inv.entrySet())
                if (en.getKey() < INV_SLOTS) target.getInventory().setItem(en.getKey(), en.getValue());
        }
        if (!what.equals("inv")) {
            target.getEnderChest().clear();
            for (Map.Entry<Integer, ItemStack> en : ender.entrySet())
                if (en.getKey() < ENDER_SLOTS) target.getEnderChest().setItem(en.getKey(), en.getValue());
        }
        String part = lang.get("parts." + what);
        lang.send(sender, "restored", "part", part, "player", target.getName(), "date", date);
        lang.send(sender, "restored-note");
        if (sender != target) lang.send(target, "restored-player", "part", part);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replace("#", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("invbackup.use")) return out;
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            for (String s : List.of("list", "view", "ender", "restore", "save", "reload")) if (s.startsWith(last)) out.add(s);
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("reload")) {
            if (a[0].equalsIgnoreCase("save") && "all".startsWith(last)) out.add("all");
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
        } else if (a.length == 3 && List.of("view", "ender", "restore").contains(a[0].toLowerCase(Locale.ROOT))) {
            UUID id = resolve(a[1]);
            if (id != null && storage.exists(id)) {
                int size = ids(storage.get(id)).size();
                for (int i = 1; i <= size; i++) out.add(String.valueOf(i));
            }
        } else if (a.length == 4 && a[0].equalsIgnoreCase("restore")) {
            for (String s : List.of("all", "inv", "ender")) if (s.startsWith(last)) out.add(s);
        }
        return out;
    }
}
