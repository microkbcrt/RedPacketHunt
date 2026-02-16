package com.example.redpackethunt;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.util.*;

public class RedPacketHunt extends JavaPlugin implements Listener, CommandExecutor {

    private Location spawnLocation;
    private boolean isGameRunning = false;
    private boolean isFrozen = false; // 是否处于冻结倒计时
    private int gameDurationMinutes = 20; // 默认20分钟
    private long gameEndTime;
    
    // 数据存储
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final List<Location> activeChests = new ArrayList<>(); // 记录未找到的箱子位置
    
    @Override
    public void onEnable() {
        getCommand("rph").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("全服找红包 (版本 1.21.11 修复版) 已加载！");
    }

    @Override
    public void onDisable() {
        stopGame(true);
    }

    // --- 指令处理 ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (args.length == 0) return false;

        if (args[0].equalsIgnoreCase("setspawn")) {
            spawnLocation = player.getLocation();
            player.sendMessage(ChatColor.GREEN + "已设置游戏中心点！");
            return true;
        }

        if (args[0].equalsIgnoreCase("setduration")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "用法: /rph setduration <分钟>");
                return true;
            }
            try {
                gameDurationMinutes = Integer.parseInt(args[1]);
                player.sendMessage(ChatColor.GREEN + "搜寻时长已设置为 " + gameDurationMinutes + " 分钟。");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "请输入有效的数字。");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("start")) {
            if (spawnLocation == null) {
                player.sendMessage(ChatColor.RED + "请先使用 /rph setspawn 设置坐标！");
                return true;
            }
            if (isGameRunning) {
                player.sendMessage(ChatColor.RED + "游戏正在进行中！");
                return true;
            }
            startGame();
            return true;
        }

        // [修复3] 新增停止指令
        if (args[0].equalsIgnoreCase("stop")) {
            if (!isGameRunning) {
                player.sendMessage(ChatColor.RED + "当前没有正在进行的游戏。");
                return true;
            }
            stopGame(true);
            Bukkit.broadcastMessage(ChatColor.RED + "管理员强制停止了游戏！");
            return true;
        }

        return false;
    }

    // --- 游戏流程逻辑 ---

    private void startGame() {
        isGameRunning = true;
        isFrozen = true;
        scores.clear();
        activeChests.clear();

        // 1. 全服玩家初始化
        for (Player p : Bukkit.getOnlinePlayers()) {
            scores.put(p.getUniqueId(), 0);
            initializePlayer(p, true); // true = 应用冻结效果
        }

        // 2. 倒计时任务
        new BukkitRunnable() {
            int count = 30;

            @Override
            public void run() {
                if (!isGameRunning) {
                    this.cancel();
                    return;
                }

                if (count > 0) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle(ChatColor.YELLOW + "游戏即将开始", ChatColor.GOLD + String.valueOf(count), 0, 25, 0);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
                    }
                    if (count == 5) generateChests();
                    count--;
                } else {
                    startMainPhase();
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    // [修复2] 初始化分离，增加是否应用Debuff的参数
    private void initializePlayer(Player p, boolean applyDebuffs) {
        p.getInventory().clear();
        p.teleport(spawnLocation);
        
        if (applyDebuffs) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 40, 1, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 40, 255, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 1000, 255, false, false)); // 保护
        } else {
            // 如果是中途加入且游戏已经开始，清除潜在的负面效果
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.setUnbreakable(true);
        elytra.setItemMeta(meta);
        p.getInventory().setChestplate(elytra);
        p.getInventory().setItemInMainHand(new ItemStack(Material.FIREWORK_ROCKET, 64));
    }

    private void generateChests() {
        World world = spawnLocation.getWorld();
        Random random = new Random();
        int attempts = 0;

        while (activeChests.size() < 10 && attempts < 100) {
            attempts++;
            int x = spawnLocation.getBlockX() + random.nextInt(1001) - 500;
            int z = spawnLocation.getBlockZ() + random.nextInt(1001) - 500;
            
            Block surface = world.getHighestBlockAt(x, z);
            Location chestLoc = surface.getLocation().add(0, 1, 0);

            if (surface.getType() == Material.WATER) {
                chestLoc = surface.getLocation().add(0, 1, 0);
            }

            chestLoc.getBlock().setType(Material.CHEST);
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                chest.getInventory().setItem(13, getRedPacketItem());
                activeChests.add(chestLoc);
            }
        }
        Bukkit.broadcastMessage(ChatColor.GREEN + "红包箱子已生成完毕！(共" + activeChests.size() + "个)");
    }

    private void startMainPhase() {
        isFrozen = false;
        gameEndTime = System.currentTimeMillis() + (gameDurationMinutes * 60 * 1000L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
            p.sendTitle(ChatColor.GREEN + "开始搜寻！", "使用鞘翅去寻找红包吧！", 10, 60, 20);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) {
                    this.cancel();
                    return;
                }

                long timeLeft = (gameEndTime - System.currentTimeMillis()) / 1000;
                
                if (timeLeft <= 0) {
                    endGame();
                    this.cancel();
                    return;
                }

                updateAllScoreboards(timeLeft);

                if (timeLeft <= 60) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (timeLeft % 10 == 0 || timeLeft <= 5) {
                            p.sendTitle(ChatColor.RED + "剩余时间", ChatColor.YELLOW + String.valueOf(timeLeft) + "秒", 0, 25, 0);
                            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 2f);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void endGame() {
        isGameRunning = false;
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
            p.teleport(spawnLocation);
            p.getInventory().clear();
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        }

        for (Location loc : activeChests) {
            loc.getBlock().setType(Material.AIR);
        }
        activeChests.clear();

        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

        Bukkit.broadcastMessage(ChatColor.GOLD + "========== 红包大赛排名 ==========");
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : sorted) {
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            Bukkit.broadcastMessage(ChatColor.AQUA + "第 " + rank + " 名: " + ChatColor.WHITE + name + 
                                    ChatColor.YELLOW + " - " + entry.getValue() + " 个红包");
            rank++;
            if (rank > 10) break;
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "================================");
    }

    private void stopGame(boolean force) {
        isGameRunning = false;
        if (force) {
            for (Location loc : activeChests) {
                if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR);
            }
            activeChests.clear();
            // 清理所有人的计分板
            for(Player p : Bukkit.getOnlinePlayers()) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
    }

    private ItemStack getRedPacketItem() {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "红包");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "活动专用，伪造无效", ChatColor.DARK_GRAY + "ID:" + UUID.randomUUID().toString().substring(0, 8)));
        item.setItemMeta(meta);
        return item;
    }

    private void updateAllScoreboards(long secondsLeft) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        String timeStr = String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = manager.getNewScoreboard();
            Objective obj = board.registerNewObjective("RPH", Criteria.DUMMY, ChatColor.RED + "§l全服找红包");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            int line = 15;
            obj.getScore(ChatColor.YELLOW + "剩余时间: " + ChatColor.WHITE + timeStr).setScore(line--);
            obj.getScore(ChatColor.GREEN + "我的红包: " + ChatColor.WHITE + scores.getOrDefault(p.getUniqueId(), 0)).setScore(line--);
            obj.getScore(ChatColor.GRAY + "----------------").setScore(line--);
            obj.getScore(ChatColor.GOLD + "剩余箱子坐标:").setScore(line--);

            for (Location loc : activeChests) {
                if (line <= 0) break;
                String coord = String.format("X:%d Y:%d Z:%d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                obj.getScore(ChatColor.AQUA + coord).setScore(line--);
            }
            p.setScoreboard(board);
        }
    }

    // --- 事件监听 ---

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!isGameRunning) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (item != null && item.getType() == Material.FIREWORK_ROCKET) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (isGameRunning) p.getInventory().setItemInMainHand(new ItemStack(Material.FIREWORK_ROCKET, 64));
            }, 1L);
        }
    }

    // [修复1] 新增：点击箱子内的红包直接计分
    @EventHandler
    public void onChestClick(InventoryClickEvent e) {
        if (!isGameRunning) return;
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();

        // 检查点击的是否是红包
        ItemStack currentItem = e.getCurrentItem();
        if (currentItem == null || currentItem.getType() != Material.RED_DYE) return;
        if (!currentItem.hasItemMeta() || !currentItem.getItemMeta().getDisplayName().contains("红包")) return;

        // 阻止拿走，直接算分
        e.setCancelled(true);
        e.setCurrentItem(new ItemStack(Material.AIR)); // 物品消失

        handleRedPacketFound(p);

        // 获取箱子位置并移除坐标
        Inventory clickedInv = e.getClickedInventory();
        if (clickedInv != null && clickedInv.getType() == InventoryType.CHEST) {
             Location chestLoc = clickedInv.getLocation();
             if (chestLoc != null) {
                 // 精准移除
                 activeChests.removeIf(loc -> loc.getBlockX() == chestLoc.getBlockX() && 
                                              loc.getBlockY() == chestLoc.getBlockY() && 
                                              loc.getBlockZ() == chestLoc.getBlockZ());
                 
                 // 可选：找到后直接破坏箱子，视觉效果更好
                 chestLoc.getBlock().setType(Material.AIR);
                 p.playSound(p.getLocation(), Sound.BLOCK_CHEST_CLOSE, 1f, 1f);
             }
        }
    }

    // 保留 Pickup 作为备用方案（防止箱子被破坏掉落）
    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!isGameRunning) return;
        if (!(e.getEntity() instanceof Player)) return;
        ItemStack item = e.getItem().getItemStack();
        if (item.getType() == Material.RED_DYE && item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("红包")) {
            Player p = (Player) e.getEntity();
            e.setCancelled(true);
            e.getItem().remove();
            
            handleRedPacketFound(p);
            // 掉落物模式下只能通过距离估算移除侧边栏坐标
            removeNearestChest(p.getLocation());
        }
    }

    private void handleRedPacketFound(Player p) {
        scores.put(p.getUniqueId(), scores.getOrDefault(p.getUniqueId(), 0) + 1);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        p.sendMessage(ChatColor.GREEN + "你找到了一个红包！当前总数: " + scores.get(p.getUniqueId()));
    }

    private void removeNearestChest(Location playerLoc) {
        Location nearest = null;
        double minDistance = Double.MAX_VALUE;
        for (Location loc : activeChests) {
            if (loc.getWorld().equals(playerLoc.getWorld())) {
                double dist = loc.distanceSquared(playerLoc);
                if (dist < minDistance) {
                    minDistance = dist;
                    nearest = loc;
                }
            }
        }
        if (nearest != null && minDistance < 25) activeChests.remove(nearest);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (isGameRunning && isFrozen) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isGameRunning && e.getItemDrop().getItemStack().getType() == Material.RED_DYE) {
            e.getItemDrop().remove();
        }
    }
    
    // [修复2] 中途加入逻辑修正
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (isGameRunning) {
             // 如果 isFrozen 为 false，说明游戏已经开始，不需要Debuff
             initializePlayer(e.getPlayer(), isFrozen);
             scores.putIfAbsent(e.getPlayer().getUniqueId(), 0);
        }
    }
}
