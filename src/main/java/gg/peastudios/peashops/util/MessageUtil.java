package gg.peastudios.peashops.util;

import gg.peastudios.peashops.PeaShops;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.HashMap;
import java.util.Map;

// thin wrapper around messages.yml. supports {placeholder} substitution and
// ampersand color codes. nothing fancy — keep it boring so it doesn't need
// updating per-version.
public final class MessageUtil {

    private final PeaShops plugin;
    private FileConfiguration messages;
    private String prefix;

    public MessageUtil(PeaShops plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveResource("messages.yml", false);
        messages = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), "messages.yml"));
        prefix = color(messages.getString("prefix", ""));
    }

    public String get(String key) {
        return get(key, new HashMap<>());
    }

    public String get(String key, Map<String, String> placeholders) {
        String raw = messages.getString(key, "&c[missing message: " + key + "]");
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        }
        return color(raw);
    }

    public void send(CommandSender to, String key) {
        to.sendMessage(prefix + get(key));
    }

    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        to.sendMessage(prefix + get(key, placeholders));
    }

    private static String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
