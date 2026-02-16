package com.example.redpackethunt;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.stream.Collectors;

public class RedPacketHunt extends JavaPlugin implements Listener, CommandExecutor {

    // --- 核心变量 ---
    public Location spawnLocation;
    public boolean isGameRunning = false;
    public boolean isFrozen = false;
    private int gameDurationMinutes = 20;
    private long gameEndTime;
    
    // --- 数据存储 ---
    public final Map<UUID, Integer> scores = new HashMap<>();
    public final List<Location> activeChests = new ArrayList<>(); // 普通红包箱
    public final List<Location> activeAirdrops = new ArrayList<>(); // 空投箱
    
    // --- 特殊状态管理 ---
    public final Map<UUID, Long> noFlyList = new HashMap<>(); // 禁飞名单 <玩家, 过期时间戳>
    public final Map<UUID, BukkitTask> openingTasks = new HashMap<>(); // 正在读条的任务
    
    // --- 常量与Key ---
    public NamespacedKey keySpecialItem; // 用于标记特殊物品

    @Override
    public void onEnable() {
        keySpecialItem = new NamespacedKey(this, "rph_special");
        
        getCommand("rph").setExecutor(this);
        // 注册监听器（逻辑在第二部分实现，但在onEnable中注册）
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getLogger().info("全服找红包 (版本 1.21.11 进阶版) 已加载！");
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

    // --- 游戏主流程 ---

    private void startGame() {
        isGameRunning = true;
        isFrozen = true;
        scores.clear();
        activeChests.clear();
        activeAirdrops.clear();
        noFlyList.clear();
        openingTasks.clear();

        // 1. 初始化全服玩家
        for (Player p : Bukkit.getOnlinePlayers()) {
            scores.put(p.getUniqueId(), 0);
            initializePlayer(p, true);
        }

        // 2. 倒计时与生成
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
                    // 在倒计时还剩5秒时生成初始红包箱
                    if (count == 5) generateChests(10); 
                    count--;
                } else {
                    startMainPhase();
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    public void initializePlayer(Player p, boolean applyDebuffs) {
        p.getInventory().clear();
        p.teleport(spawnLocation);
        
        if (applyDebuffs) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 40, 1, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 40, 255, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 1000, 255, false, false)); 
        } else {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.setUnbreakable(true);
        elytra.setItemMeta(meta);
        p.getInventory().setChestplate(elytra);
        // 初始给16个火箭
        p.getInventory().setItemInMainHand(new ItemStack(Material.FIREWORK_ROCKET, 16));
        // 新增：给予一支箭，否则无限附魔的弓无法射击
        p.getInventory().setItem(9, new ItemStack(Material.ARROW, 1)); 
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

        // 启动各种定时任务
        startScoreboardTask();
        startFireworkSupplyTask();
        startAirdropTask();
        startParticleTask();
    }

    // --- 定时任务逻辑 ---

    private void startScoreboardTask() {
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

                // 最后60秒倒计时
                if (timeLeft <= 60) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (timeLeft % 10 == 0 || timeLeft <= 5) {
                            p.sendTitle(ChatColor.RED + "剩余时间", ChatColor.YELLOW + String.valueOf(timeLeft) + "秒", 0, 25, 0);
                            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 2f);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L); // 每秒刷新
    }

    private void startFireworkSupplyTask() {
        // 每3分钟 (3 * 60 * 20 ticks) 补给一次火箭
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) {
                    this.cancel();
                    return;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.getInventory().addItem(new ItemStack(Material.FIREWORK_ROCKET, 16));
                    p.sendMessage(ChatColor.AQUA + "[补给] " + ChatColor.WHITE + "已发放 16 个烟花火箭！");
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                }
            }
        }.runTaskTimer(this, 3 * 60 * 20L, 3 * 60 * 20L);
    }

    private void startAirdropTask() {
        // 每5分钟生成一个空投
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) {
                    this.cancel();
                    return;
                }
                spawnAirdrop();
            }
        }.runTaskTimer(this, 5 * 60 * 20L, 5 * 60 * 20L);
    }
    
    private void startParticleTask() {
        // 每秒渲染空投光柱
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) {
                    this.cancel();
                    return;
                }
                for (Location loc : activeAirdrops) {
                    if (loc.getWorld() == null) continue;
                    // 生成一道冲天光柱
                    for (int y = 0; y < 50; y++) {
                        loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0.5, y + 1, 0.5), 1, 0, 0, 0, 0);
                    }
                    loc.getWorld().spawnParticle(Particle.FLASH, loc.clone().add(0.5, 1.5, 0.5), 1);
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    // --- 生成逻辑 ---

    public void generateChests(int amount) {
        World world = spawnLocation.getWorld();
        Random random = new Random();
        int generated = 0;
        int attempts = 0;

        while (generated < amount && attempts < amount * 10) {
            attempts++;
            int x = spawnLocation.getBlockX() + random.nextInt(1001) - 500;
            int z = spawnLocation.getBlockZ() + random.nextInt(1001) - 500;
            
            Block surface = world.getHighestBlockAt(x, z);
            Location chestLoc = surface.getLocation().add(0, 1, 0);

            if (surface.getType() == Material.WATER || surface.getType() == Material.LAVA) {
                continue; // 尽量不生成在水里
            }

            chestLoc.getBlock().setType(Material.CHEST);
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                fillChestLoot(chest.getInventory(), false); // false = 普通红包箱
                activeChests.add(chestLoc);
                generated++;
            }
        }
        Bukkit.broadcastMessage(ChatColor.GREEN + "红包箱子已刷新！(新增 " + generated + " 个)");
    }

    private void spawnAirdrop() {
        World world = spawnLocation.getWorld();
        Random random = new Random();
        int x = spawnLocation.getBlockX() + random.nextInt(801) - 400;
        int z = spawnLocation.getBlockZ() + random.nextInt(801) - 400;
        Block surface = world.getHighestBlockAt(x, z);
        Location loc = surface.getLocation().add(0, 1, 0);

        loc.getBlock().setType(Material.CHEST); // 虽然是空投，但用箱子实体
        if (loc.getBlock().getState() instanceof Chest) {
            Chest chest = (Chest) loc.getBlock().getState();
            // 可以给空投箱子改个名（如果是容器的话，需要BlockStateMeta，这里简化处理，通过位置识别）
            fillChestLoot(chest.getInventory(), true); // true = 空投
            activeAirdrops.add(loc);
            
            String coord = String.format("X:%d Y:%d Z:%d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Bukkit.broadcastMessage(ChatColor.GOLD + "========== [空投降落] ==========");
            Bukkit.broadcastMessage(ChatColor.RED + "坐标: " + ChatColor.YELLOW + coord);
            Bukkit.broadcastMessage(ChatColor.AQUA + "空投内含稀有道具，开启需读条 5 秒！");
            Bukkit.broadcastMessage(ChatColor.GOLD + "==============================");
            
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 100f, 1f);
            }
        }
    }

    // 填充战利品逻辑 (将在Part 2中细化ItemStack的生成)
    public void fillChestLoot(Inventory inv, boolean isAirdrop) {
        inv.clear();
        Random r = new Random();
        
        // 必有红包
        int redPacketSlot = isAirdrop ? 13 : r.nextInt(27);
        inv.setItem(redPacketSlot, GameListener.getRedPacketItem());
        
        // 空投或者普通箱子都有概率刷特殊物品，空投概率极高
        int attempts = isAirdrop ? 8 : 3;
        
        for (int i = 0; i < attempts; i++) {
            if (isAirdrop || r.nextDouble() < 0.4) { // 普通箱子40%几率有额外道具
                int slot = r.nextInt(27);
                if (inv.getItem(slot) == null) {
                    inv.setItem(slot, GameListener.getRandomSpecialItem(isAirdrop));
                }
            }
        }
    }

    // --- 结束与清理 ---

    public void endGame() {
        isGameRunning = false;
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
            p.teleport(spawnLocation);
            p.getInventory().clear();
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        }

        cleanupBlocks(activeChests);
        cleanupBlocks(activeAirdrops);
        
        // 停止所有读条
        openingTasks.values().forEach(BukkitTask::cancel);
        openingTasks.clear();

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

    public void stopGame(boolean force) {
        isGameRunning = false;
        if (force) {
            cleanupBlocks(activeChests);
            cleanupBlocks(activeAirdrops);
            openingTasks.values().forEach(BukkitTask::cancel);
            openingTasks.clear();
            for(Player p : Bukkit.getOnlinePlayers()) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
    }
    
    private void cleanupBlocks(List<Location> locs) {
        for (Location loc : locs) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR);
        }
        locs.clear();
    }

    private void updateAllScoreboards(long secondsLeft) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        String timeStr = String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60);

        // 预先计算是否所有普通箱子已被收集
        boolean allChestsFound = activeChests.isEmpty();

        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = manager.getNewScoreboard();
            Objective obj = board.registerNewObjective("RPH", Criteria.DUMMY, ChatColor.RED + "§l全服找红包");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            List<String> lines = new ArrayList<>();
            lines.add(ChatColor.YELLOW + "倒计时: " + ChatColor.WHITE + timeStr);
            lines.add(ChatColor.GREEN + "我的红包: " + ChatColor.WHITE + scores.getOrDefault(p.getUniqueId(), 0));
            
            // --- [新增] 显示当前玩家坐标 ---
            Location pl = p.getLocation();
            lines.add(ChatColor.WHITE + String.format("当前: %d, %d, %d", pl.getBlockX(), pl.getBlockY(), pl.getBlockZ()));
            
            lines.add(ChatColor.GRAY + "----------------");

            // --- [修改] 核心逻辑：箱子找完后的处理 ---
            if (allChestsFound) {
                // 1. 给予发光效果 (持续3秒，循环刷新，保持常亮)
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false));
                
                // 2. 侧边栏显示最近的玩家
                lines.add(ChatColor.RED + "§l[决战时刻]");
                lines.add(ChatColor.GOLD + "所有箱子已找完!");
                lines.add(ChatColor.GOLD + "全员发光中...");
                
                Player nearest = null;
                double minDistance = Double.MAX_VALUE;
                
                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (target.equals(p) || target.getWorld() != p.getWorld()) continue;
                    double dist = target.getLocation().distance(p.getLocation());
                    if (dist < minDistance) {
                        minDistance = dist;
                        nearest = target;
                    }
                }
                
                if (nearest != null) {
                    lines.add(ChatColor.AQUA + "最近敌人: " + ChatColor.WHITE + nearest.getName());
                    lines.add(ChatColor.AQUA + "距离: " + ChatColor.WHITE + String.format("%.1fm", minDistance));
                } else {
                    lines.add(ChatColor.GRAY + "附近暂无敌人");
                }

            } else {
                // --- 原有逻辑：显示剩余箱子 ---
                
                // 空投显示 (保持不变)
                if (!activeAirdrops.isEmpty()) {
                    lines.add(ChatColor.GOLD + ">> 空投坐标 <<");
                    for (Location loc : activeAirdrops) {
                        lines.add(String.format("X:%d Y:%d Z:%d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                    }
                    lines.add(ChatColor.GRAY + " ");
                }

                // 最近箱子显示 (保持不变)
                lines.add(ChatColor.GOLD + "最近箱子:");
                List<Location> nearestChests = getNearestChests(p.getLocation(), 5);
                if (nearestChests.isEmpty()) {
                    lines.add(ChatColor.GRAY + "附近没有箱子...");
                } else {
                    for (Location loc : nearestChests) {
                        lines.add(ChatColor.AQUA + String.format("%d, %d, %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                    }
                }
            }

            // 渲染到计分板
            int scoreIdx = 15;
            for (String line : lines) {
                if (scoreIdx <= 0) break;
                if (obj.getScore(line).isScoreSet()) {
                    line = line + ChatColor.RESET;
                }
                obj.getScore(line).setScore(scoreIdx--);
            }
            p.setScoreboard(board);
        }
    }

    private List<Location> getNearestChests(Location playerLoc, int limit) {
        return activeChests.stream()
            .filter(loc -> loc.getWorld().equals(playerLoc.getWorld()))
            .sorted(Comparator.comparingDouble(loc -> loc.distanceSquared(playerLoc)))
            .limit(limit)
            .collect(Collectors.toList());
    }
}
