package com.magicsmp.billford;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BillfordPlugin extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final List<Trade> trades = new ArrayList<>();
    private final ConcurrentHashMap<UUID, Inventory> openMenus = new ConcurrentHashMap<>();
    private int currentTrade;
    private long intervalMillis;
    private long nextResetAt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        loadEverything(false);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new BillfordPlaceholders(this).register();
            getLogger().info("PlaceholderAPI support enabled: %billford_time%");
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    private void loadEverything(boolean announceReload) {
        reloadConfig();
        loadTrades();
        intervalMillis = parseDuration(getConfig().getString("preset-change-interval", "20m"));
        if (intervalMillis < 1000L) intervalMillis = 20L * 60L * 1000L;

        File dataFile = new File(getDataFolder(), "data.yml");
        var data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        currentTrade = trades.isEmpty() ? 0 : Math.floorMod(data.getInt("current-trade", 0), trades.size());
        nextResetAt = data.getLong("next-reset-at", 0L);
        if (nextResetAt <= System.currentTimeMillis()) nextResetAt = System.currentTimeMillis() + intervalMillis;
        saveState();
        refreshAllMenus();
        if (announceReload) Bukkit.broadcast(component(message("reload")));
    }

    private void loadTrades() {
        trades.clear();
        ConfigurationSection root = getConfig().getConfigurationSection("presets");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            Material result = Material.matchMaterial(section.getString("trade-item", "STONE"));
            if (result == null) continue;
            int resultAmount = Math.max(1, section.getInt("amount", 1));
            List<Cost> costs = new ArrayList<>();
            for (String line : section.getStringList("required-items")) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) continue;
                try {
                    Material material = Material.matchMaterial(parts[1]);
                    if (material != null) costs.add(new Cost(material, Math.max(1, Integer.parseInt(parts[0]))));
                } catch (NumberFormatException ignored) { }
            }
            if (!costs.isEmpty()) trades.add(new Trade(result, resultAmount, costs));
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!trades.isEmpty() && now >= nextResetAt) {
            currentTrade = (currentTrade + 1) % trades.size();
            nextResetAt = now + intervalMillis;
            saveState();
            Trade trade = trades.get(currentTrade);
            Bukkit.broadcast(component(message("reset")
                    .replace("%amount%", String.valueOf(trade.resultAmount()))
                    .replace("%item%", pretty(trade.result()))));
        }
        refreshAllMenus();
    }

    private void saveState() {
        File file = new File(getDataFolder(), "data.yml");
        var data = new org.bukkit.configuration.file.YamlConfiguration();
        data.set("current-trade", currentTrade);
        data.set("next-reset-at", nextResetAt);
        try { data.save(file); } catch (IOException e) { getLogger().warning("Could not save data.yml: " + e.getMessage()); }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0) {
            if (!sender.hasPermission("astralbillford.admin")) {
                sender.sendMessage(component("&cYou don't have permission."));
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                loadEverything(true);
                nextResetAt = System.currentTimeMillis() + intervalMillis;
                saveState();
                refreshAllMenus();
                return true;
            }
            if (args[0].equalsIgnoreCase("settime")) return setTime(sender, args);
            if (args[0].equalsIgnoreCase("trade")) return editTrades(sender, args);
            sendAdminHelp(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        openMenu(player);
        return true;
    }

    private boolean setTime(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(component("&cUsage: /billford settime <20m|30m|1h>"));
            return true;
        }
        long parsed = parseDuration(args[1]);
        if (parsed < 10_000L) {
            sender.sendMessage(component("&cThe reset time must be at least 10 seconds."));
            return true;
        }
        getConfig().set("preset-change-interval", args[1].toLowerCase(Locale.ROOT));
        saveConfig();
        intervalMillis = parsed;
        nextResetAt = System.currentTimeMillis() + intervalMillis;
        saveState();
        refreshAllMenus();
        sender.sendMessage(component(message("time-changed").replace("%time%", args[1])));
        return true;
    }

    private boolean editTrades(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendAdminHelp(sender);
            return true;
        }
        if (args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(component("&#ff9900&lBillford trades:"));
            for (int i = 0; i < trades.size(); i++) {
                Trade trade = trades.get(i);
                sender.sendMessage(component("&7#" + (i + 1) + " &f" + formatCosts(trade)
                        + " &7→ &#7afc00" + trade.resultAmount() + "x " + pretty(trade.result())));
            }
            return true;
        }
        if (args[1].equalsIgnoreCase("remove")) {
            if (args.length != 3) {
                sender.sendMessage(component("&cUsage: /billford trade remove <number>"));
                return true;
            }
            int index = parseIndex(args[2]);
            if (index < 0 || index >= trades.size()) {
                sender.sendMessage(component("&cThat trade number does not exist."));
                return true;
            }
            if (trades.size() == 1) {
                sender.sendMessage(component("&cYou must keep at least one trade."));
                return true;
            }
            trades.remove(index);
            currentTrade = Math.floorMod(currentTrade, trades.size());
            saveTradesToConfig();
            saveState();
            refreshAllMenus();
            sender.sendMessage(component(message("trade-removed").replace("%number%", String.valueOf(index + 1))));
            return true;
        }
        boolean setting = args[1].equalsIgnoreCase("set");
        boolean adding = args[1].equalsIgnoreCase("add");
        int offset = setting ? 3 : 2;
        if ((!setting && !adding) || args.length != offset + 4) {
            sender.sendMessage(component("&cUsage: /billford trade add <give-item> <give-amount> <cost-item> <cost-amount>"));
            sender.sendMessage(component("&cOr: /billford trade set <number> <give-item> <give-amount> <cost-item> <cost-amount>"));
            return true;
        }
        int index = setting ? parseIndex(args[2]) : trades.size();
        if (setting && (index < 0 || index >= trades.size())) {
            sender.sendMessage(component("&cThat trade number does not exist."));
            return true;
        }
        Material give = Material.matchMaterial(args[offset]);
        Material cost = Material.matchMaterial(args[offset + 2]);
        int giveAmount;
        int costAmount;
        try {
            giveAmount = Integer.parseInt(args[offset + 1]);
            costAmount = Integer.parseInt(args[offset + 3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(component("&cAmounts must be whole numbers."));
            return true;
        }
        if (give == null || cost == null) {
            sender.sendMessage(component("&cUnknown item. Example: GOLDEN_APPLE or NETHERITE_INGOT."));
            return true;
        }
        if (giveAmount < 1 || giveAmount > give.getMaxStackSize() || costAmount < 1) {
            sender.sendMessage(component("&cGive amount must fit one stack, and cost must be at least 1."));
            return true;
        }
        Trade trade = new Trade(give, giveAmount, List.of(new Cost(cost, costAmount)));
        if (setting) trades.set(index, trade); else trades.add(trade);
        saveTradesToConfig();
        refreshAllMenus();
        String key = setting ? "trade-updated" : "trade-added";
        sender.sendMessage(component(message(key).replace("%number%", String.valueOf(index + 1))));
        return true;
    }

    private void saveTradesToConfig() {
        getConfig().set("presets", null);
        for (int i = 0; i < trades.size(); i++) {
            Trade trade = trades.get(i);
            String path = "presets." + (i + 1);
            getConfig().set(path + ".trade-item", trade.result().name());
            getConfig().set(path + ".amount", trade.resultAmount());
            getConfig().set(path + ".required-items", trade.costs().stream()
                    .map(cost -> cost.amount() + " " + cost.material().name()).toList());
        }
        saveConfig();
    }

    private int parseIndex(String input) {
        try { return Integer.parseInt(input) - 1; }
        catch (NumberFormatException ignored) { return -1; }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(component("&#ff9900/billford settime <20m|30m|1h>"));
        sender.sendMessage(component("&#ff9900/billford trade list"));
        sender.sendMessage(component("&#ff9900/billford trade add <give-item> <give-amount> <cost-item> <cost-amount>"));
        sender.sendMessage(component("&#ff9900/billford trade set <number> <give-item> <give-amount> <cost-item> <cost-amount>"));
        sender.sendMessage(component("&#ff9900/billford trade remove <number>"));
        sender.sendMessage(component("&#ff9900/billford reload"));
    }

    private void openMenu(Player player) {
        if (trades.isEmpty()) {
            player.sendMessage(component("&cNo trades are configured."));
            return;
        }
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, component("&8ʙɪʟʟꜰᴏʀᴅ"));
        holder.inventory = inventory;
        fillMenu(inventory);
        openMenus.put(player.getUniqueId(), inventory);
        player.openInventory(inventory);
    }

    private void fillMenu(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&7 ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        Trade trade = trades.get(currentTrade);
        Cost first = trade.costs().getFirst();
        inventory.setItem(10, item(first.material(), "&f" + pretty(first.material()),
                List.of("&7Required: &f" + first.amount())));
        inventory.setItem(23, item(Material.HOPPER, "&#7afc00&lTRADE",
                List.of("&fClick to confirm the trade", "", "&7You need the items in your inventory")));
        inventory.setItem(25, item(trade.result(), "&#7afc00" + pretty(trade.result()),
                List.of("&7You receive: &f" + trade.resultAmount())));
        inventory.setItem(49, item(Material.BOOK, "&#7afc00Billford Trade",
                List.of("&f" + formatCosts(trade), "&ffor", "&#7afc00" + trade.resultAmount() + "x " + pretty(trade.result()))));
        updateTimer(inventory);
    }

    private void updateTimer(Inventory inventory) {
        inventory.setItem(48, item(Material.CLOCK, "&#ff9900&lTIME UNTIL RESET",
                List.of("&f" + formatRemaining(), "", "&7Reset interval: &f" + formatDuration(intervalMillis))));
    }

    private void refreshAllMenus() {
        for (var entry : openMenus.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !(player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)) {
                openMenus.remove(entry.getKey());
                continue;
            }
            fillMenu(entry.getValue());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        event.setCancelled(true);
        if (event.getRawSlot() != 23 || !(event.getWhoClicked() instanceof Player player)) return;
        performTrade(player);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder)
            openMenus.remove(event.getPlayer().getUniqueId());
    }

    private void performTrade(Player player) {
        Trade trade = trades.get(currentTrade);
        for (Cost cost : trade.costs()) {
            if (!player.getInventory().containsAtLeast(new ItemStack(cost.material()), cost.amount())) {
                player.sendMessage(component(message("no-required-items")));
                play(player, "error");
                return;
            }
        }
        ItemStack reward = new ItemStack(trade.result(), trade.resultAmount());
        if (player.getInventory().firstEmpty() == -1 && !player.getInventory().contains(reward.getType())) {
            player.sendMessage(component(message("inventory-full")));
            play(player, "error");
            return;
        }
        for (Cost cost : trade.costs()) remove(player, cost.material(), cost.amount());
        var leftovers = player.getInventory().addItem(reward);
        leftovers.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        player.sendMessage(component(message("trade-success")
                .replace("%amount%", String.valueOf(trade.resultAmount()))
                .replace("%item%", pretty(trade.result()))));
        play(player, "trade-success");
    }

    private void remove(Player player, Material material, int amount) {
        int left = amount;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            left -= take;
            if (left == 0) return;
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(component(name));
        meta.lore(lore.stream().map(this::component).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private Component component(String input) { return LEGACY.deserialize(color(input)); }

    private String color(String input) {
        Matcher matcher = HEX.matcher(input == null ? "" : input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append('§').append(c);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(output);
        return ChatColor.translateAlternateColorCodes('&', output.toString());
    }

    private String message(String key) { return getConfig().getString("messages." + key, ""); }

    private void play(Player player, String key) {
        try { player.playSound(player.getLocation(), Sound.valueOf(getConfig().getString("sounds." + key, "UI_BUTTON_CLICK")), 1f, 1f); }
        catch (Exception ignored) { }
    }

    private String formatCosts(Trade trade) {
        return trade.costs().stream().map(c -> c.amount() + "x " + pretty(c.material())).reduce((a, b) -> a + ", " + b).orElse("");
    }

    public String formatRemaining() {
        long seconds = Math.max(0L, (nextResetAt - System.currentTimeMillis() + 999L) / 1000L);
        return String.format(Locale.ROOT, "%02dm %02ds", seconds / 60L, seconds % 60L);
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(1L, millis / 1000L);
        if (seconds % 3600L == 0L) return (seconds / 3600L) + "h";
        if (seconds % 60L == 0L) return (seconds / 60L) + "m";
        return seconds + "s";
    }

    public String configuredInterval() {
        return formatDuration(intervalMillis);
    }

    public String currentTradeDescription() {
        if (trades.isEmpty()) return "None";
        Trade trade = trades.get(currentTrade);
        return trade.resultAmount() + "x " + pretty(trade.result());
    }

    private String pretty(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        return out.toString().trim();
    }

    private long parseDuration(String value) {
        if (value == null || value.isBlank()) return 20L * 60L * 1000L;
        String text = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("ms")) return Long.parseLong(text.substring(0, text.length() - 2));
            if (text.endsWith("s")) return Long.parseLong(text.substring(0, text.length() - 1)) * 1000L;
            if (text.endsWith("m")) return Long.parseLong(text.substring(0, text.length() - 1)) * 60_000L;
            if (text.endsWith("h")) return Long.parseLong(text.substring(0, text.length() - 1)) * 3_600_000L;
            return Long.parseLong(text) * 1000L;
        } catch (NumberFormatException ignored) { return 20L * 60L * 1000L; }
    }

    private record Cost(Material material, int amount) { }
    private record Trade(Material result, int resultAmount, List<Cost> costs) { }
    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public @NotNull Inventory getInventory() { return Objects.requireNonNull(inventory); }
    }
}
