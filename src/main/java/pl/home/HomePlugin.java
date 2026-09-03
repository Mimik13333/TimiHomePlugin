package pl.home;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class HomePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final String MAIN_TITLE = "§8§lHOME";
    private static final String SETTINGS_TITLE = "§8§lUSTAWIENIA HOME";
    private static final String ICONS_TITLE = "§8§lWYBIERZ IKONĘ";
    private final Map<UUID, Map<String, HomeData>> homes = new HashMap<>();
    private final Map<UUID, TeleportData> teleports = new HashMap<>();
    private final Set<UUID> editing = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadHomes();
        getCommand("home").setExecutor(this);
        getCommand("home").setTabCompleter(this);
        getCommand("sethome").setExecutor(this);
        getCommand("sethome").setTabCompleter(this);
        getCommand("delhome").setExecutor(this);
        getCommand("delhome").setTabCompleter(this);
        getCommand("homeadmin").setExecutor(this);
        getCommand("homeadmin").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Home enabled.");
    }

    @Override
    public void onDisable() {
        saveHomes();
        teleports.values().forEach(t -> t.cancel());
        teleports.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cTa komenda jest tylko dla graczy.");
            return true;
        }

        if (cmd.equals("home")) {
            if (!player.hasPermission("home.use")) {
                player.sendMessage("§cNie masz uprawnień.");
                return true;
            }
            if (args.length == 0) {
                openMain(player);
                return true;
            }
            HomeData home = getHome(player, args[0]);
            if (home == null) {
                player.sendMessage("§cNie masz home o nazwie §f" + args[0] + "§c.");
                return true;
            }
            startTeleport(player, args[0], home);
            return true;
        }

        if (cmd.equals("sethome")) {
            if (!player.hasPermission("home.set")) {
                player.sendMessage("§cNie masz uprawnień.");
                return true;
            }
            String name = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : nextHomeName(player);
            if (!isValidHomeName(name)) {
                player.sendMessage("§cNazwa home może mieć 1-16 znaków: litery, cyfry, _ i -.");
                return true;
            }
            int max = maxHomes(player);
            Map<String, HomeData> map = homes.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
            if (!map.containsKey(name) && map.size() >= max && !player.hasPermission("home.bypass.limit")) {
                player.sendMessage("§cOsiągnąłeś limit §f" + max + " §chome'ów.");
                return true;
            }
            map.put(name, HomeData.fromLocation(player.getLocation(), Material.RED_BED, true));
            saveHomes();
            player.sendMessage("§aHome §f" + name + " §azostał ustawiony.");
            return true;
        }

        if (cmd.equals("delhome")) {
            if (!player.hasPermission("home.set")) {
                player.sendMessage("§cNie masz uprawnień.");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage("§cUżycie: /delhome <nazwa>");
                return true;
            }
            Map<String, HomeData> map = homes.get(player.getUniqueId());
            if (map == null || map.remove(args[0].toLowerCase(Locale.ROOT)) == null) {
                player.sendMessage("§cNie znaleziono takiego home.");
                return true;
            }
            saveHomes();
            player.sendMessage("§aHome został usunięty.");
            return true;
        }

        if (cmd.equals("homeadmin")) {
            if (!player.hasPermission("home.admin")) {
                player.sendMessage("§cNie masz uprawnień.");
                return true;
            }
            return handleAdmin(player, args);
        }
        return true;
    }

    private boolean handleAdmin(Player admin, String[] args) {
        if (args.length == 0) {
            admin.sendMessage("§e/homeadmin list <gracz>");
            admin.sendMessage("§e/homeadmin teleport <gracz> <home>");
            admin.sendMessage("§e/homeadmin usun <gracz> <home>");
            admin.sendMessage("§e/homeadmin wyczysc <gracz>");
            admin.sendMessage("§e/homeadmin ustaw <gracz> <home>");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list") && args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { admin.sendMessage("§cGracz nie jest online."); return true; }
            Map<String, HomeData> map = homes.getOrDefault(target.getUniqueId(), Collections.emptyMap());
            admin.sendMessage("§6Home'y gracza §f" + target.getName() + "§6: §e" + String.join(", ", map.keySet()));
            return true;
        }
        if (sub.equals("teleport") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { admin.sendMessage("§cGracz nie jest online."); return true; }
            HomeData home = getHome(target, args[2]);
            if (home == null) { admin.sendMessage("§cNie znaleziono home."); return true; }
            target.teleport(home.toLocation());
            admin.sendMessage("§aTeleportowano gracza.");
            return true;
        }
        if (sub.equals("usun") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { admin.sendMessage("§cGracz nie jest online."); return true; }
            Map<String, HomeData> map = homes.get(target.getUniqueId());
            if (map == null || map.remove(args[2].toLowerCase(Locale.ROOT)) == null) { admin.sendMessage("§cNie znaleziono home."); return true; }
            saveHomes();
            admin.sendMessage("§aUsunięto home gracza.");
            return true;
        }
        if (sub.equals("wyczysc") && args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { admin.sendMessage("§cGracz nie jest online."); return true; }
            homes.remove(target.getUniqueId());
            saveHomes();
            admin.sendMessage("§aUsunięto wszystkie home'y gracza.");
            return true;
        }
        if (sub.equals("ustaw") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { admin.sendMessage("§cGracz nie jest online."); return true; }
            Map<String, HomeData> map = homes.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>());
            map.put(args[2].toLowerCase(Locale.ROOT), HomeData.fromLocation(admin.getLocation(), Material.RED_BED, true));
            saveHomes();
            admin.sendMessage("§aUstawiono home gracza w Twojej lokalizacji.");
            return true;
        }
        admin.sendMessage("§cNieprawidłowa składnia.");
        return true;
    }

    private void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, MAIN_TITLE);
        Map<String, HomeData> map = homes.getOrDefault(player.getUniqueId(), Collections.emptyMap());
        int slot = 10;
        for (Map.Entry<String, HomeData> entry : map.entrySet()) {
            if (slot == 16) slot = 19;
            if (slot == 25) slot = 28;
            if (slot == 34) slot = 37;
            if (slot >= 45) break;
            HomeData data = entry.getValue();
            ItemStack item = new ItemStack(data.icon);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§6§l" + entry.getKey());
            List<String> lore = new ArrayList<>();
            lore.add("§7PPM §8» §fTeleportuj");
            lore.add("§7LPM §8» §fUstawienia");
            if (data.showCoordinates) {
                Location loc = data.toLocation();
                lore.add("");
                lore.add("§7Świat: §f" + data.world);
                lore.add("§7X: §f" + format(loc.getX()));
                lore.add("§7Y: §f" + format(loc.getY()));
                lore.add("§7Z: §f" + format(loc.getZ()));
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
            slot++;
        }
        for (int i = 0; i < 5; i++) {
            int s = 45 + i;
            if (i == 0) inv.setItem(s, named(Material.RED_BED, "§a§l+ DODAJ HOME", "§7PPM §8» §futwórz home", "§7Miejsce: §fTwoja pozycja"));
            else inv.setItem(s, named(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        inv.setItem(49, named(Material.BARRIER, "§c§lZAMKNIJ"));
        player.openInventory(inv);
    }

    private void openSettings(Player player, String name) {
        HomeData data = getHome(player, name);
        if (data == null) return;
        Inventory inv = Bukkit.createInventory(null, 27, SETTINGS_TITLE);
        inv.setItem(10, named(Material.ENDER_PEARL, "§b§lTELEPORTUJ", "§7Kliknij, aby rozpocząć teleportację"));
        inv.setItem(12, named(data.showCoordinates ? Material.LIME_DYE : Material.GRAY_DYE,
                "§e§lKOORDYNATY", "§7Status: " + (data.showCoordinates ? "§aWŁĄCZONE" : "§cWYŁĄCZONE"), "§8Kliknij, aby zmienić"));
        inv.setItem(14, named(data.icon, "§d§lZMIEN IKONĘ", "§7Kliknij, aby wybrać blok"));
        inv.setItem(16, named(Material.NAME_TAG, "§6§lNAZWA", "§7Aktualnie: §f" + name, "§7Komendą: §f/sethome <nazwa>"));
        inv.setItem(22, named(Material.RED_DYE, "§c§lUSUŃ HOME", "§7Kliknij, aby usunąć ten home"));
        inv.setItem(26, named(Material.BARRIER, "§c§lWRÓĆ"));
        player.openInventory(inv);
        player.setMetadata("home.settings", new org.bukkit.metadata.FixedMetadataValue(this, name));
    }

    private void openIcons(Player player, String homeName) {
        Inventory inv = Bukkit.createInventory(null, 54, ICONS_TITLE);
        List<Material> materials = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isItem()) continue;
            if (m.isAir()) continue;
            if (m.name().contains("SPAWN_EGG")) continue;
            if (m.name().contains("POTION")) continue;
            if (m.name().contains("BUCKET")) continue;
            if (m.name().contains("MUSIC_DISC")) continue;
            if (m.name().contains("COMMAND_BLOCK")) continue;
            materials.add(m);
            if (materials.size() >= 45) break;
        }
        for (int i = 0; i < materials.size() && i < 45; i++) {
            ItemStack item = new ItemStack(materials.get(i));
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§f" + formatMaterial(materials.get(i)));
            meta.setLore(Collections.singletonList("§8Kliknij, aby ustawić ikonę"));
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        inv.setItem(49, named(Material.BARRIER, "§c§lWRÓĆ"));
        player.openInventory(inv);
        player.setMetadata("home.icon", new org.bukkit.metadata.FixedMetadataValue(this, homeName));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.equals(MAIN_TITLE) && !title.equals(SETTINGS_TITLE) && !title.equals(ICONS_TITLE)) return;
        event.setCancelled(true);
        if (title.equals(MAIN_TITLE)) handleMainClick(player, event); else if (title.equals(SETTINGS_TITLE)) handleSettingsClick(player, event); else handleIconsClick(player, event);
    }

    private void handleMainClick(Player player, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        if (slot == 45) {
            if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.LEFT) {
                createHome(player);
            }
            return;
        }
        if (slot == 49) { player.closeInventory(); return; }
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String name = ChatColor.stripColor(meta.getDisplayName());
        if (!hasHome(player, name)) return;
        if (event.getClick() == ClickType.RIGHT) {
            HomeData data = getHome(player, name);
            if (data != null) startTeleport(player, name, data);
        } else if (event.getClick() == ClickType.LEFT) {
            openSettings(player, name);
        }
    }

    private void handleSettingsClick(Player player, InventoryClickEvent event) {
        String name = getMetadata(player, "home.settings");
        if (name == null) return;
        HomeData data = getHome(player, name);
        if (data == null) { openMain(player); return; }
        int slot = event.getRawSlot();
        if (slot == 10) {
            player.closeInventory();
            startTeleport(player, name, data);
        } else if (slot == 12) {
            data.showCoordinates = !data.showCoordinates;
            saveHomes();
            openSettings(player, name);
        } else if (slot == 14) {
            openIcons(player, name);
        } else if (slot == 22) {
            Map<String, HomeData> map = homes.get(player.getUniqueId());
            if (map != null) map.remove(name);
            saveHomes();
            player.sendMessage("§aHome §f" + name + " §azostał usunięty.");
            player.closeInventory();
        } else if (slot == 26) {
            openMain(player);
        }
    }

    private void handleIconsClick(Player player, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == 49) { openMain(player); return; }
        if (slot < 0 || slot >= 45) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) return;
        String homeName = getMetadata(player, "home.icon");
        if (homeName == null) return;
        HomeData data = getHome(player, homeName);
        if (data == null) return;
        data.icon = item.getType();
        saveHomes();
        player.sendMessage("§aIkona home §f" + homeName + " §azostała zmieniona.");
        openSettings(player, homeName);
    }

    private void createHome(Player player) {
        Map<String, HomeData> map = homes.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
        int max = maxHomes(player);
        if (map.size() >= max && !player.hasPermission("home.bypass.limit")) {
            player.sendMessage("§cOsiągnąłeś limit §f" + max + " §chome'ów.");
            return;
        }
        String name = nextHomeName(player);
        map.put(name, HomeData.fromLocation(player.getLocation(), Material.RED_BED, true));
        saveHomes();
        player.sendMessage("§aUtworzono home §f" + name + "§a.");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.2f);
        openMain(player);
    }

    private void startTeleport(Player player, String name, HomeData data) {
        cancelTeleport(player);
        int delay = getConfig().getInt("teleport-delay", 5);
        Location start = player.getLocation().clone();
        TeleportData tp = new TeleportData(player, data.toLocation(), start, name);
        teleports.put(player.getUniqueId(), tp);
        player.closeInventory();
        player.sendMessage("§eTeleportacja do home §f" + name + " §eza §c" + delay + " §esekund. Nie ruszaj się.");
        tp.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) { tp.cancel(); return; }
            if (moved(start, player.getLocation())) {
                player.sendMessage("§cTeleportacja anulowana, ponieważ się ruszyłeś.");
                tp.cancel();
                return;
            }
            tp.seconds--;
            if (tp.seconds <= 0) {
                player.teleport(tp.target);
                player.sendMessage("§aTeleportowano do home §f" + name + "§a.");
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
                tp.cancel();
            } else {
                player.sendActionBar("§eTeleportacja za §c" + tp.seconds + "§e...");
            }
        }, 20L, 20L);
    }

    private boolean moved(Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) return true;
        return a.distanceSquared(b) > 0.04;
    }

    private void cancelTeleport(Player player) {
        TeleportData tp = teleports.remove(player.getUniqueId());
        if (tp != null) tp.cancel();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!teleports.containsKey(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            cancelTeleport(event.getPlayer());
            event.getPlayer().sendMessage("§cTeleportacja anulowana, ponieważ się ruszyłeś.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelTeleport(event.getPlayer());
        editing.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getView().getTitle().equals(MAIN_TITLE) || event.getView().getTitle().equals(SETTINGS_TITLE) || event.getView().getTitle().equals(ICONS_TITLE)) {
            player.removeMetadata("home.settings", this);
            player.removeMetadata("home.icon", this);
        }
    }

    private int maxHomes(Player player) {
        if (player.hasPermission("home.limit.20")) return 20;
        if (player.hasPermission("home.limit.10")) return 10;
        if (player.hasPermission("home.limit.5")) return 5;
        if (player.hasPermission("home.limit.3")) return 3;
        return getConfig().getInt("default-limit", 3);
    }

    private HomeData getHome(Player player, String name) {
        Map<String, HomeData> map = homes.get(player.getUniqueId());
        return map == null ? null : map.get(name.toLowerCase(Locale.ROOT));
    }

    private boolean hasHome(Player player, String name) { return getHome(player, name) != null; }

    private String nextHomeName(Player player) {
        Map<String, HomeData> map = homes.getOrDefault(player.getUniqueId(), Collections.emptyMap());
        int i = 1;
        while (map.containsKey("home" + i)) i++;
        return "home" + i;
    }

    private boolean isValidHomeName(String s) { return s.matches("[a-zA-Z0-9_-]{1,16}"); }

    private ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private String format(double d) { return String.format(Locale.US, "%.1f", d); }

    private String formatMaterial(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String part : s.split(" ")) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        return out.toString().trim();
    }

    private String getMetadata(Player player, String key) {
        if (!player.hasMetadata(key)) return null;
        return player.getMetadata(key).stream().filter(v -> v.getOwningPlugin() == this).findFirst().map(v -> v.asString()).orElse(null);
    }

    private void loadHomes() {
        homes.clear();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "homes.yml"));
        for (String uuidString : cfg.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidString); } catch (IllegalArgumentException ex) { continue; }
            Map<String, HomeData> map = new LinkedHashMap<>();
            for (String name : cfg.getConfigurationSection(uuidString).getKeys(false)) {
                String p = uuidString + "." + name;
                String world = cfg.getString(p + ".world");
                World w = world == null ? null : Bukkit.getWorld(world);
                if (w == null) continue;
                HomeData d = new HomeData();
                d.world = world;
                d.x = cfg.getDouble(p + ".x");
                d.y = cfg.getDouble(p + ".y");
                d.z = cfg.getDouble(p + ".z");
                d.yaw = (float) cfg.getDouble(p + ".yaw");
                d.pitch = (float) cfg.getDouble(p + ".pitch");
                d.showCoordinates = cfg.getBoolean(p + ".show-coordinates", true);
                try { d.icon = Material.valueOf(cfg.getString(p + ".icon", "RED_BED")); } catch (Exception e) { d.icon = Material.RED_BED; }
                map.put(name, d);
            }
            homes.put(uuid, map);
        }
    }

    private void saveHomes() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
        for (Map.Entry<UUID, Map<String, HomeData>> entry : homes.entrySet()) {
            for (Map.Entry<String, HomeData> home : entry.getValue().entrySet()) {
                String p = entry.getKey() + "." + home.getKey();
                HomeData d = home.getValue();
                cfg.set(p + ".world", d.world);
                cfg.set(p + ".x", d.x);
                cfg.set(p + ".y", d.y);
                cfg.set(p + ".z", d.z);
                cfg.set(p + ".yaw", d.yaw);
                cfg.set(p + ".pitch", d.pitch);
                cfg.set(p + ".show-coordinates", d.showCoordinates);
                cfg.set(p + ".icon", d.icon.name());
            }
        }
        try { cfg.save(new java.io.File(getDataFolder(), "homes.yml")); } catch (Exception e) { getLogger().severe("Nie udalo sie zapisac homes.yml: " + e.getMessage()); }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("home") || cmd.equals("delhome")) {
            if (!(sender instanceof Player p)) return Collections.emptyList();
            if (args.length == 1) return homes.getOrDefault(p.getUniqueId(), Collections.emptyMap()).keySet().stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (cmd.equals("homeadmin")) {
            if (args.length == 1) return List.of("list", "teleport", "usun", "wyczysc", "ustaw");
            if (args.length == 2 && (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("teleport") || args[0].equalsIgnoreCase("usun") || args[0].equalsIgnoreCase("wyczysc") || args[0].equalsIgnoreCase("ustaw"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            if (args.length == 3 && (args[0].equalsIgnoreCase("teleport") || args[0].equalsIgnoreCase("usun"))) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target != null) return homes.getOrDefault(target.getUniqueId(), Collections.emptyMap()).keySet().stream().toList();
            }
        }
        return Collections.emptyList();
    }

    private static class HomeData {
        String world;
        double x, y, z;
        float yaw, pitch;
        Material icon = Material.RED_BED;
        boolean showCoordinates = true;

        static HomeData fromLocation(Location loc, Material icon, boolean showCoordinates) {
            HomeData d = new HomeData();
            d.world = Objects.requireNonNull(loc.getWorld()).getName();
            d.x = loc.getX(); d.y = loc.getY(); d.z = loc.getZ();
            d.yaw = loc.getYaw(); d.pitch = loc.getPitch();
            d.icon = icon; d.showCoordinates = showCoordinates;
            return d;
        }

        Location toLocation() {
            World w = Bukkit.getWorld(world);
            return new Location(w, x, y, z, yaw, pitch);
        }
    }

    private static class TeleportData {
        final Player player;
        final Location target;
        final Location start;
        final String name;
        int seconds;
        BukkitTask task;

        TeleportData(Player player, Location target, Location start, String name) {
            this.player = player; this.target = target; this.start = start; this.name = name; this.seconds = 5;
        }

        void cancel() { if (task != null) task.cancel(); }
    }
}
