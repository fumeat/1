package dev.colorsync;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ColorSync extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String TEAM_PREFIX = "cs_";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // UUID -> color. Concurrent, because the chat event runs on another thread.
    private final Map<UUID, TextColor> colors = new ConcurrentHashMap<>();
    private final Map<String, String> presets = new HashMap<>();
    private File colorsFile;

    // settings
    private TextColor defaultColor = NamedTextColor.WHITE;
    private boolean tabEnabled, nametagEnabled, waypointEnabled, chatEnabled;
    private double localRadius;
    private String globalPrefix;
    private Component localTag, globalTag, separator, nobodyHeard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        colorsFile = new File(getDataFolder(), "colors.yml");
        loadSettings();
        loadColors();
        cleanupTeams();

        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("color");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // in case of /reload: re-apply to everyone who is online
        for (Player p : Bukkit.getOnlinePlayers()) {
            apply(p, false);
        }
    }

    @Override
    public void onDisable() {
        saveColors();
        cleanupTeams();
    }

    // ------------------------------------------------------------------ settings

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();

        TextColor def = TextColor.fromHexString(c.getString("default-color", "#FFFFFF"));
        defaultColor = def != null ? def : NamedTextColor.WHITE;

        tabEnabled = c.getBoolean("tab.enabled", true);
        nametagEnabled = c.getBoolean("nametag.enabled", true);
        waypointEnabled = c.getBoolean("waypoint.enabled", true);

        chatEnabled = c.getBoolean("chat.enabled", true);
        localRadius = c.getDouble("chat.local-radius", 100);
        globalPrefix = c.getString("chat.global-prefix", "!");
        localTag = MM.deserialize(c.getString("chat.local-tag", "[L] "));
        globalTag = MM.deserialize(c.getString("chat.global-tag", "[G] "));
        separator = MM.deserialize(c.getString("chat.separator", " > "));
        nobodyHeard = MM.deserialize(c.getString("chat.nobody-heard", "Nobody heard you."));

        presets.clear();
        ConfigurationSection sec = c.getConfigurationSection("presets");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String value = sec.getString(key);
                if (value != null) {
                    presets.put(key.toLowerCase(Locale.ROOT), value);
                }
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private void loadColors() {
        colors.clear();
        if (!colorsFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(colorsFile);
        for (String key : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                TextColor col = TextColor.fromHexString(y.getString(key, ""));
                if (col != null) {
                    colors.put(id, col);
                }
            } catch (IllegalArgumentException ignored) {
                // bad key, skip
            }
        }
    }

    private void saveColors() {
        YamlConfiguration y = new YamlConfiguration();
        colors.forEach((id, col) -> y.set(id.toString(), col.asHexString()));
        try {
            getDataFolder().mkdirs();
            y.save(colorsFile);
        } catch (IOException e) {
            getLogger().warning("Could not save colors.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ applying a color

    private void setColor(Player p, TextColor c) {
        colors.put(p.getUniqueId(), c);
        saveColors();
        apply(p, false);
    }

    private void resetColor(Player p) {
        colors.remove(p.getUniqueId());
        saveColors();
        apply(p, true);
    }

    /** Pushes the player's color to tab, nametag and locator bar. Main thread only. */
    private void apply(Player p, boolean explicitReset) {
        TextColor c = colors.get(p.getUniqueId());

        if (tabEnabled) {
            p.playerListName(c == null ? null : Component.text(p.getName(), c));
        }
        if (nametagEnabled) {
            applyNametag(p, c);
        }
        if (waypointEnabled) {
            if (c != null) {
                String hex = String.format("%06X", c.value());
                runConsole("waypoint modify " + p.getName() + " color hex " + hex);
            } else if (explicitReset) {
                runConsole("waypoint modify " + p.getName() + " color reset");
            }
        }
    }

    private void runConsole(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private String teamName(Player p) {
        return TEAM_PREFIX + p.getUniqueId().toString().replace("-", "").substring(0, 13);
    }

    private void applyNametag(Player p, TextColor c) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = teamName(p);
        Team team = sb.getTeam(name);
        if (c == null) {
            if (team != null) {
                team.unregister();
            }
            return;
        }
        if (team == null) {
            team = sb.registerNewTeam(name);
        }
        // the name above the head only supports the 16 legacy colors
        team.color(NamedTextColor.nearestTo(c));
        if (!team.hasEntry(p.getName())) {
            team.addEntry(p.getName());
        }
    }

    private void cleanupTeams() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (t.getName().startsWith(TEAM_PREFIX)) {
                t.unregister();
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // small delay so the client is fully loaded before the waypoint command runs
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                apply(p, false);
            }
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (nametagEnabled) {
            Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(e.getPlayer()));
            if (t != null) {
                t.unregister();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!chatEnabled) {
            return;
        }
        Player sender = e.getPlayer();
        String plain = PlainTextComponentSerializer.plainText().serialize(e.message());
        boolean global = !globalPrefix.isEmpty() && plain.startsWith(globalPrefix);

        if (global) {
            plain = plain.substring(globalPrefix.length()).stripLeading();
            if (plain.isEmpty()) {
                e.setCancelled(true);
                return;
            }
            e.message(Component.text(plain));
        } else {
            Location from = sender.getLocation();
            double r2 = localRadius * localRadius;
            boolean heard = false;

            Iterator<Audience> it = e.viewers().iterator();
            while (it.hasNext()) {
                Audience a = it.next();
                if (a instanceof Player viewer && !viewer.equals(sender)) {
                    if (viewer.hasPermission("colorsync.spy")) {
                        continue; // admins see all local chat
                    }
                    Location l = viewer.getLocation();
                    if (l.getWorld() != from.getWorld() || l.distanceSquared(from) > r2) {
                        it.remove();
                    } else {
                        heard = true;
                    }
                }
            }
            if (!heard) {
                sender.sendMessage(nobodyHeard);
            }
        }

        final boolean isGlobal = global;
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> {
            TextColor c = colors.getOrDefault(source.getUniqueId(), defaultColor);
            return Component.empty()
                    .append(isGlobal ? globalTag : localTag)
                    .append(Component.text(source.getName(), c))
                    .append(separator)
                    .append(message.colorIfAbsent(NamedTextColor.WHITE));
        }));
    }

    // ------------------------------------------------------------------ commands

    private TextColor parseColor(String input) {
        String s = input;
        String preset = presets.get(s.toLowerCase(Locale.ROOT));
        if (preset != null) {
            s = preset;
        }
        if (!s.startsWith("#")) {
            s = "#" + s;
        }
        return TextColor.fromHexString(s); // null if invalid
    }

    private void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "Использование: /" + label + " <#hex | название | reset> [игрок]", NamedTextColor.GRAY);
            if (!presets.isEmpty()) {
                msg(sender, "Названия: " + String.join(", ", presets.keySet()), NamedTextColor.GRAY);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            if (!sender.hasPermission("colorsync.reload")) {
                msg(sender, "Нет прав.", NamedTextColor.RED);
                return true;
            }
            loadSettings();
            loadColors();
            for (Player p : Bukkit.getOnlinePlayers()) {
                apply(p, false);
            }
            msg(sender, "ColorSync перезагружен.", NamedTextColor.GREEN);
            return true;
        }

        if (!sender.hasPermission("colorsync.use")) {
            msg(sender, "Нет прав.", NamedTextColor.RED);
            return true;
        }

        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("colorsync.others")) {
                msg(sender, "Нет прав менять цвет другим игрокам.", NamedTextColor.RED);
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg(sender, "Игрок " + args[1] + " не в сети.", NamedTextColor.RED);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg(sender, "Из консоли укажи игрока: /" + label + " <цвет> <игрок>", NamedTextColor.RED);
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            resetColor(target);
            msg(sender, "Цвет сброшен: " + target.getName(), NamedTextColor.GREEN);
            return true;
        }

        TextColor c = parseColor(args[0]);
        if (c == null) {
            msg(sender, "Не понял цвет. Пример: /" + label + " #7EC8F2", NamedTextColor.RED);
            return true;
        }

        setColor(target, c);
        sender.sendMessage(Component.text("Цвет установлен для " + target.getName() + ": ", NamedTextColor.GREEN)
                .append(Component.text(c.asHexString(), c)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(presets.keySet());
            options.add("reset");
            if (sender.hasPermission("colorsync.reload")) {
                options.add("reload");
            }
            for (String o : options) {
                if (o.startsWith(start)) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && sender.hasPermission("colorsync.others")) {
            String start = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
