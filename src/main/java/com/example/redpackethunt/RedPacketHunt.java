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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
        getLogger().info("全服找红包 (版本 1.21.11 群骑纷争适配版) 已加载！");
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
            initializePlayer(p);
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
                    // Title & Sound
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle(ChatColor.YELLOW + "游戏即将开始", ChatColor.GOLD + String.valueOf(count), 0, 25, 0);
                        // 1.21 音符盒声音
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f); // 标准音 La
                    }
                    
                    // 倒数第5秒生成箱子
                    if (count == 5) {
                        generateChests();
                    }
                    count--;
                } else {
                    startMainPhase();
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void initializePlayer(Player p) {
        p.getInventory().clear();
        p.teleport(spawnLocation);
        
        // 效果：失明 + 缓慢 + 抗性提升(防止互殴)
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 40, 1, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 40, 255, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 1000, 255, false, false));
        
        // 装备
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.setUnbreakable(true); // 鞘翅无限耐久
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
            
            // 获取最高方块
            Block surface = world.getHighestBlockAt(x, z);
            Location chestLoc = surface.getLocation().add(0, 1, 0);

            // 水面处理：如果最高方块是水，则箱子生成在水面上方
            if (surface.getType() == Material.WATER) {
                chestLoc = surface.getLocation().add(0, 1, 0);
            }

            // 放置箱子
            chestLoc.getBlock().setType(Material.CHEST);
            if (chestLoc.getBlock().getState() instanceof Chest) {
                Chest chest = (Chest) chestLoc.getBlock().getState();
                chest.getInventory().setItem(13, getRedPacketItem()); // 放中间
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

        // 游戏主循环 (每秒更新侧边栏和时间)
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
        }.runTaskTimer(this, 0L, 20L);
    }

    private void endGame() {
        isGameRunning = false;
        
        // 播放龙啸并传送
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
            p.teleport(spawnLocation);
            p.getInventory().clear();
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); // 清除计分板
            // 移除所有药水效果
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        }

        // 清理剩余箱子
        for (Location loc : activeChests) {
            loc.getBlock().setType(Material.AIR);
        }
        activeChests.clear();

        // 排名结算
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
        }
    }

    // --- 辅助功能 ---

    private ItemStack getRedPacketItem() {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "红包");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "活动专用，伪造无效", ChatColor.DARK_GRAY + "ID:" + UUID.randomUUID().toString().substring(0, 8)));
        item.setItemMeta(meta);
        return item;
    }

    // --- 计分板系统 ---
    private void updateAllScoreboards(long secondsLeft) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        
        // 格式化时间
        String timeStr = String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = manager.getNewScoreboard();
            Objective obj = board.registerNewObjective("RPH", Criteria.DUMMY, ChatColor.RED + "§l全服找红包");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            // 倒序添加分数以控制显示行数
            int line = 15;
            
            Score sTime = obj.getScore(ChatColor.YELLOW + "剩余时间: " + ChatColor.WHITE + timeStr);
            sTime.setScore(line--);
            
            Score sCount = obj.getScore(ChatColor.GREEN + "我的红包: " + ChatColor.WHITE + scores.getOrDefault(p.getUniqueId(), 0));
            sCount.setScore(line--);
            
            Score sSpacer = obj.getScore(ChatColor.GRAY + "----------------");
            sSpacer.setScore(line--);
            
            Score sInfo = obj.getScore(ChatColor.GOLD + "剩余箱子坐标:");
            sInfo.setScore(line--);

            // 显示剩余箱子坐标
            for (Location loc : activeChests) {
                if (line <= 0) break;
                String coord = String.format("X:%d Y:%d Z:%d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                Score sLoc = obj.getScore(ChatColor.AQUA + coord);
                sLoc.setScore(line--);
            }
            
            p.setScoreboard(board);
        }
    }

    // --- 事件监听 ---

    // 1. 无限烟花逻辑
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!isGameRunning) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        
        // 如果玩家使用了烟花火箭
        if (item != null && item.getType() == Material.FIREWORK_ROCKET) {
            // 简单的补充逻辑：让玩家手里的烟花永远保持64个
            // 这样玩家使用一个，下一tick我们补满，实际上就是无限
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (isGameRunning) { // 双重检查游戏状态
                    p.getInventory().setItemInMainHand(new ItemStack(Material.FIREWORK_ROCKET, 64));
                }
            }, 1L);
        }
    }

    // 2. 拾取红包逻辑
    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!isGameRunning) return;
        if (!(e.getEntity() instanceof Player)) return;
        
        ItemStack item = e.getItem().getItemStack();
        // 检查是否是红包
        if (item.getType() == Material.RED_DYE && item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("红包")) {
            Player p = (Player) e.getEntity();
            
            e.setCancelled(true); // 取消捡起进入背包
            e.getItem().remove(); // 销毁掉落物
            
            // 加分
            scores.put(p.getUniqueId(), scores.getOrDefault(p.getUniqueId(), 0) + 1);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            p.sendMessage(ChatColor.GREEN + "你找到了一个红包！当前总数: " + scores.get(p.getUniqueId()));

            // 关键：移除最近的箱子坐标 (使侧边栏坐标消失)
            removeNearestChest(p.getLocation());
        }
    }

    private void removeNearestChest(Location playerLoc) {
        Location nearest = null;
        double minDistance = Double.MAX_VALUE;

        // 寻找离玩家最近的一个活跃箱子（通常就是玩家刚打开的那个）
        for (Location loc : activeChests) {
            // 确保在同一个世界
            if (loc.getWorld().equals(playerLoc.getWorld())) {
                double dist = loc.distanceSquared(playerLoc);
                if (dist < minDistance) {
                    minDistance = dist;
                    nearest = loc;
                }
            }
        }

        // 如果距离足够近（例如5格以内），判定为找到该箱子
        if (nearest != null && minDistance < 25) {
            activeChests.remove(nearest);
            // 这里也可以把箱子方块顺便敲掉，防止误导别人
            nearest.getBlock().setType(Material.AIR);
        }
    }

    // 3. 冻结期间逻辑
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (isGameRunning && isFrozen) e.setCancelled(true);
    }

    // 4. 防止丢弃红包（虽然捡不起，但防止通过容器取出）
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isGameRunning && e.getItemDrop().getItemStack().getType() == Material.RED_DYE) {
            e.getItemDrop().remove();
        }
    }
    
    // 5. 中途加入的玩家自动强制参加
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (isGameRunning) {
             initializePlayer(e.getPlayer());
             scores.putIfAbsent(e.getPlayer().getUniqueId(), 0);
        }
    }
}
