import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.*;
import java.util.*;

public class XMDoubleJump extends JavaPlugin implements Listener {

    private YamlConfiguration messages;
    private File messagesFile;
    private boolean enabledByDefault;
    private double jumpPower;
    private double forwardPower;
    private boolean particlesEnabled;
    private Particle particleType;
    private int particleCount;
    private double particleOffsetX, particleOffsetY, particleOffsetZ;
    private double particleSpeed;
    private boolean soundsEnabled;
    private Sound soundType;
    private float soundVolume, soundPitch;
    private int cooldownSeconds;               // جدید: time in seconds
    private Set<UUID> disabledPlayers;
    private Set<UUID> usedDoubleJump;          // برای جلوگیری از پرش نامحدود
    private Map<UUID, Long> cooldownMap;       // برای خنک‌سازی

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfiguration();
        loadMessages();
        disabledPlayers = new HashSet<>();
        usedDoubleJump = new HashSet<>();
        cooldownMap = new HashMap<>();
        getServer().getPluginManager().registerEvents(this, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            updateFlight(player);
        }
    }

    private void loadMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private void reloadConfiguration() {
        reloadConfig();
        YamlConfiguration config = (YamlConfiguration) getConfig();
        enabledByDefault = config.getBoolean("settings.enabled-by-default", true);
        jumpPower = config.getDouble("settings.jump-power", 0.6);
        forwardPower = config.getDouble("settings.forward-power", 1.0);
        cooldownSeconds = config.getInt("settings.cooldown-seconds", 0); // 0 = بدون کولدان

        particlesEnabled = config.getBoolean("particles.enabled", true);
        particleType = Particle.valueOf(config.getString("particles.type", "CLOUD").toUpperCase());
        particleCount = config.getInt("particles.count", 15);
        particleOffsetX = config.getDouble("particles.offset-x", 0.3);
        particleOffsetY = config.getDouble("particles.offset-y", 0.2);
        particleOffsetZ = config.getDouble("particles.offset-z", 0.3);
        particleSpeed = config.getDouble("particles.speed", 0.02);

        soundsEnabled = config.getBoolean("sounds.enabled", true);
        soundType = loadSound(config.getString("sounds.type", "ENTITY_BAT_TAKEOFF"));
        soundVolume = (float) config.getDouble("sounds.volume", 0.6);
        soundPitch = (float) config.getDouble("sounds.pitch", 1.5);
    }

    // متد جدید برای پشتیبانی از نام‌های custom مانند minecraft:entity.player.attack.crit
    private Sound loadSound(String soundName) {
        if (soundName == null || soundName.isEmpty()) return Sound.ENTITY_BAT_TAKEOFF;
        try {
            // اگر شامل : بود (مثل minecraft:something) قسمت بعد از : رو استخراج کن
            String cleaned = soundName.contains(":") ? soundName.split(":")[1] : soundName;
            cleaned = cleaned.toUpperCase().replace('.', '_');
            return Sound.valueOf(cleaned);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid sound type: " + soundName + ". Using default ENTITY_BAT_TAKEOFF");
            return Sound.ENTITY_BAT_TAKEOFF;
        }
    }

    @Override
    public void saveDefaultConfig() {
        saveResource("config.yml", false);
    }

    private String getMessage(String path) {
        return messages.getString(path, "");
    }

    private Component getFormattedMessage(String path, Map<String, String> placeholders) {
        String msg = getMessage(path);
        if (msg.isEmpty()) return Component.empty();
        String prefix = getMessage("prefix");
        if (!prefix.isEmpty() && !path.equals("prefix")) {
            msg = prefix + msg;
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                msg = msg.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        updateFlight(event.getPlayer());
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        updateFlight(event.getPlayer()); // بدون نیاز به Scheduler
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        // اگر بازیکن روی زمین است، اجازه پرش مجدد بده
        if (player.isOnGround() && usedDoubleJump.contains(player.getUniqueId())) {
            usedDoubleJump.remove(player.getUniqueId());
            updateFlight(player);
        }
    }

    private void updateFlight(Player player) {
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            return;
        }

        boolean isDisabled = disabledPlayers.contains(player.getUniqueId());
        boolean canDoubleJump = enabledByDefault ? !isDisabled : isDisabled;
        boolean hasPerm = player.hasPermission("xmdoublejump.use");

        // فقط اگر روی زمین است و قبلاً در هوا استفاده نکرده باشه، allowFlight بده
        if (canDoubleJump && hasPerm && player.isOnGround() && !usedDoubleJump.contains(player.getUniqueId())) {
            player.setAllowFlight(true);
        } else {
            player.setAllowFlight(false);
            if (player.isFlying()) {
                player.setFlying(false);
            }
        }
    }

    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        if (!player.hasPermission("xmdoublejump.use")) return;

        UUID uuid = player.getUniqueId();
        boolean isDisabled = disabledPlayers.contains(uuid);
        boolean canDoubleJump = enabledByDefault ? !isDisabled : isDisabled;

        if (!canDoubleJump) return;
        if (!event.isFlying()) return;

        // بررسی کولدان
        if (cooldownSeconds > 0) {
            long lastUse = cooldownMap.getOrDefault(uuid, 0L);
            long now = System.currentTimeMillis();
            if (now - lastUse < cooldownSeconds * 1000L) {
                // هنوز در کولدان است
                event.setCancelled(true);
                player.setAllowFlight(true); // اجازه نده پرنده بشه
                return;
            }
            cooldownMap.put(uuid, now);
        }

        // جلوگیری از پرش مجدد در هوا
        if (usedDoubleJump.contains(uuid)) return;

        event.setCancelled(true);
        usedDoubleJump.add(uuid);
        player.setAllowFlight(false); // مهم: جلوگیری از پرش دوباره در هوا

        // اعمال velocity
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() == 0) {
            direction = new Vector(0, 0, 0);
        }
        Vector forwardVelocity = direction.multiply(forwardPower);
        player.setVelocity(new Vector(forwardVelocity.getX(), jumpPower, forwardVelocity.getZ()));
        player.setFallDistance(0f);

        // افکت‌ها
        Location loc = player.getLocation();
        World world = player.getWorld();
        if (particlesEnabled) {
            world.spawnParticle(particleType, loc, particleCount,
                    particleOffsetX, particleOffsetY, particleOffsetZ, particleSpeed);
        }
        if (soundsEnabled && soundType != null) {
            world.playSound(loc, soundType, soundVolume, soundPitch);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("xmdoublejump")) return false;

        if (args.length == 0) {
            sender.sendMessage(getFormattedMessage("usage-toggle", null));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> {
                if (args.length == 1) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(getFormattedMessage("no-permission", null));
                        return true;
                    }
                    if (!player.hasPermission("xmdoublejump.toggle.self")) {
                        player.sendMessage(getFormattedMessage("no-permission", null));
                        return true;
                    }
                    togglePlayer(player.getUniqueId(), player, false, null);
                } else if (args.length == 2) {
                    if (!sender.hasPermission("xmdoublejump.toggle.others")) {
                        sender.sendMessage(getFormattedMessage("no-permission", null));
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(getFormattedMessage("player-not-found", null));
                        return true;
                    }
                    togglePlayer(target.getUniqueId(), target, true, sender);
                } else {
                    sender.sendMessage(getFormattedMessage("usage-toggle", null));
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("xmdoublejump.reload")) {
                    sender.sendMessage(getFormattedMessage("no-permission", null));
                    return true;
                }
                loadMessages();
                reloadConfiguration();
                sender.sendMessage(getFormattedMessage("config-reloaded", null));
            }
            default -> sender.sendMessage(getFormattedMessage("usage-toggle", null));
        }
        return true;
    }

    private void togglePlayer(UUID uuid, Player target, boolean isOther, CommandSender sender) {
        boolean currentlyDisabled = disabledPlayers.contains(uuid);
        if (currentlyDisabled) {
            disabledPlayers.remove(uuid);
            if (!isOther) {
                target.sendMessage(getFormattedMessage("double-jump-enabled-self", null));
            } else {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", target.getName());
                sender.sendMessage(getFormattedMessage("double-jump-enabled-other", placeholders));
                target.sendMessage(getFormattedMessage("double-jump-enabled-by-other",
                        Collections.singletonMap("player", sender.getName())));
            }
        } else {
            disabledPlayers.add(uuid);
            if (!isOther) {
                target.sendMessage(getFormattedMessage("double-jump-disabled-self", null));
            } else {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", target.getName());
                sender.sendMessage(getFormattedMessage("double-jump-disabled-other", placeholders));
                target.sendMessage(getFormattedMessage("double-jump-disabled-by-other",
                        Collections.singletonMap("player", sender.getName())));
            }
        }
        // بعد از تغییر وضعیت، حتماً allowFlight رو بروز کن
        usedDoubleJump.remove(uuid); // ریست وضعیت استفاده
        updateFlight(target);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            if ("toggle".startsWith(args[0].toLowerCase())) completions.add("toggle");
            if ("reload".startsWith(args[0].toLowerCase())) completions.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        }
        return completions;
    }
}
