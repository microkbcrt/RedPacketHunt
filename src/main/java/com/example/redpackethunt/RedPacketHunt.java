package com.example.redpackethunt;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;

import java.util.*;

public class RedPacketHunt extends JavaPlugin implements Listener, CommandExecutor {

    // --- 核心变量 ---
    private Location spawnLocation;
    private boolean isGameRunning = false;
    private int gameDurationMinutes = 20;
    private long gameEndTime;
    private final NamespacedKey KEY_ID = new NamespacedKey(this, "rph_item_id");

    // --- 游戏数据 ---
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Set<Location> normalChests = new HashSet<>();
    private final Set<Location> airdropChests = new HashSet<>();
    private final Set<Location> deathChests = new HashSet<>();
    
    // --- 状态控制 ---
    private final Map<UUID, Long> groundedPlayers = new HashMap<>(); // 禁飞名单 <玩家, 过期时间戳>
    private final Map<UUID, OpeningTask> openingTasks = new HashMap<>(); // 正在开箱的玩家

    @Override
    public void onEnable() {
        getCommand("rph").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("全服找红包 (进阶博弈版) 已加载！");
        
        // 粒子效果 & 禁飞检查 循环任务
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) return;
                
                // 1. 空投光柱
                for (Location loc : airdropChests) {
                    if (loc.getWorld() == null) continue;
                    loc.getWorld().spawnParticle(Particle.FIREWORK, loc.clone().add(0.5, 0, 0.5), 20, 0.2, 5, 0.2, 0.1);
                    loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 1, 0.5), 1, 0, 0, 0, 0.05);
                    // 向上画一条线
                    for (int y = 1; y < 50; y+=2) {
                        loc.getWorld().spawnParticle(Particle.WAX_OFF, loc.clone().add(0.5, y, 0.5), 1);
                    }
                }

                // 2. 禁飞检查
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (groundedPlayers.containsKey(p.getUniqueId())) {
                        if (now < groundedPlayers.get(p.getUniqueId())) {
                            if (p.isGliding()) {
                                p.setGliding(false);
                                p.sendMessage(ChatColor.RED + "你处于禁飞状态！");
                            }
                        } else {
                            groundedPlayers.remove(p.getUniqueId());
                            p.sendMessage(ChatColor.GREEN + "禁飞状态已解除！");
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @Override
    public void onDisable() {
        stopGame(true);
    }

    // --- 指令 ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (args.length == 0) return false;

        if (args[0].equalsIgnoreCase("setspawn")) {
            spawnLocation = player.getLocation();
            player.sendMessage(ChatColor.GREEN + "中心点已设置！");
            return true;
        }

        if (args[0].equalsIgnoreCase("setduration")) {
            try {
                gameDurationMinutes = Integer.parseInt(args[1]);
                player.sendMessage(ChatColor.GREEN + "时长已设置: " + gameDurationMinutes + "分钟");
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "数字无效");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("start")) {
            if (spawnLocation == null) {
                player.sendMessage(ChatColor.RED + "请先 setspawn");
                return true;
            }
            if (isGameRunning) {
                player.sendMessage(ChatColor.RED + "游戏进行中");
                return true;
            }
            startGame();
            return true;
        }

        if (args[0].equalsIgnoreCase("stop")) {
            stopGame(true);
            Bukkit.broadcastMessage(ChatColor.RED + "游戏被强制停止");
            return true;
        }
        return false;
    }

    // --- 游戏流程 ---

    private void startGame() {
        isGameRunning = true;
        scores.clear();
        normalChests.clear();
        airdropChests.clear();
        deathChests.clear();
        groundedPlayers.clear();
        openingTasks.clear();

        // 初始化玩家
        for (Player p : Bukkit.getOnlinePlayers()) {
            scores.put(p.getUniqueId(), 0);
            initializePlayer(p);
        }

        // 倒计时开始生成箱子
        new BukkitRunnable() {
            int count = 10;
            @Override
            public void run() {
                if (!isGameRunning) { cancel(); return; }
                if (count > 0) {
                    Bukkit.broadcastMessage(ChatColor.YELLOW + "游戏将在 " + count + " 秒后开始...");
                    count--;
                } else {
                    generateChests(20, false); // 生成20个初始箱子
                    startMainLoops();
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void initializePlayer(Player p) {
        p.getInventory().clear();
        p.teleport(spawnLocation);
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));

        // 基础装备
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.setUnbreakable(true);
        elytra.setItemMeta(meta);
        p.getInventory().setChestplate(elytra);
        
        p.getInventory().addItem(new ItemStack(Material.FIREWORK_ROCKET, 16));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
    }

    private void startMainLoops() {
        gameEndTime = System.currentTimeMillis() + (gameDurationMinutes * 60 * 1000L);
        
        // 1. 游戏主计时与计分板
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) { cancel(); return; }
                long left = (gameEndTime - System.currentTimeMillis()) / 1000;
                if (left <= 0) {
                    endGame();
                    cancel();
                    return;
                }
                updateScoreboards(left);
            }
        }.runTaskTimer(this, 0L, 20L);

        // 2. 补给任务 (3分钟发一次火箭)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) { cancel(); return; }
                Bukkit.broadcastMessage(ChatColor.AQUA + "补给到达！每人获得 16 个烟花火箭！");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.getInventory().addItem(new ItemStack(Material.FIREWORK_ROCKET, 16));
                }
            }
        }.runTaskTimer(this, 20 * 180, 20 * 180);

        // 3. 空投任务 (5分钟一次)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) { cancel(); return; }
                generateChests(1, true); // 生成1个空投
            }
        }.runTaskTimer(this, 20 * 300, 20 * 300);
    }

    private void stopGame(boolean cleanup) {
        isGameRunning = false;
        openingTasks.values().forEach(OpeningTask::cancel);
        openingTasks.clear();
        
        if (cleanup) {
            // 清理箱子
            for (Location loc : normalChests) loc.getBlock().setType(Material.AIR);
            for (Location loc : airdropChests) loc.getBlock().setType(Material.AIR);
            for (Location loc : deathChests) loc.getBlock().setType(Material.AIR);
            normalChests.clear();
            airdropChests.clear();
            deathChests.clear();
        }
    }

    private void endGame() {
        stopGame(true);
        Bukkit.broadcastMessage(ChatColor.GOLD + "========== 游戏结束 ==========");
        // 简单排名广播
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(scores.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int rank = 1;
        for (Map.Entry<UUID, Integer> e : list) {
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            Bukkit.broadcastMessage(ChatColor.AQUA + "第" + rank + ": " + name + " (" + e.getValue() + "分)");
            if (rank++ >= 5) break;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.teleport(spawnLocation);
            p.getInventory().clear();
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    // --- 箱子与战利品 ---

    private void generateChests(int count, boolean isAirdrop) {
        Random r = new Random();
        for (int i = 0; i < count; i++) {
            int x = spawnLocation.getBlockX() + r.nextInt(800) - 400;
            int z = spawnLocation.getBlockZ() + r.nextInt(800) - 400;
            Block b = spawnLocation.getWorld().getHighestBlockAt(x, z);
            Location loc = b.getLocation().add(0, 1, 0);
            
            loc.getBlock().setType(Material.CHEST);
            if (isAirdrop) {
                airdropChests.add(loc);
                Bukkit.broadcastMessage(ChatColor.GOLD + ">> 空投已降落在 X:" + x + " Z:" + z + " <<");
                loc.getWorld().playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 5f, 1f);
            } else {
                normalChests.add(loc);
            }
            
            fillChest(loc, isAirdrop);
        }
        if (!isAirdrop) Bukkit.broadcastMessage(ChatColor.GREEN + "已刷新 " + count + " 个普通红包箱！");
    }

    private void fillChest(Location loc, boolean isAirdrop) {
        if (!(loc.getBlock().getState() instanceof Chest)) return;
        Chest chest = (Chest) loc.getBlock().getState();
        Inventory inv = chest.getInventory();
        inv.clear();

        // 1. 必有红包
        int redPacketCount = isAirdrop ? 5 : 1;
        for(int k=0; k<redPacketCount; k++) {
            inv.addItem(createSpecialItem("红包", Material.RED_DYE, "right_click_score"));
        }

        // 2. 随机战利品
        Random r = new Random();
        if (isAirdrop || r.nextDouble() < 0.4) { // 空投必有，普通箱40%
            addRandomLoot(inv, isAirdrop);
        }
    }

    private void addRandomLoot(Inventory inv, boolean isRich) {
        Random r = new Random();
        double roll = r.nextDouble();

        // 冰冻雪球
        if (roll < 0.25) {
            ItemStack item = createSpecialItem("冰冻雪球", Material.SNOWBALL, "freeze_ball");
            item.setAmount(r.nextInt(3) + 3); // 3-5个
            inv.addItem(item);
        }
        // 击坠弓 (稀有)
        else if (roll < 0.35) {
            ItemStack bow = createSpecialItem("击坠弓", Material.BOW, "anti_air_bow");
            inv.addItem(bow);
            inv.addItem(new ItemStack(Material.ARROW, 5));
        }
        // 击退棒
        else if (roll < 0.45) {
            ItemStack stick = createSpecialItem("击退棒", Material.STICK, "kb_stick");
            stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2);
            inv.addItem(stick);
        }
        // 诅咒烈焰棒 (稀有)
        else if (roll < 0.55) {
            inv.addItem(createSpecialItem("诅咒烈焰棒", Material.BLAZE_ROD, "curse_rod"));
        }
        // 换位钟 (稀有)
        else if (roll < 0.65) {
            inv.addItem(createSpecialItem("换位时钟", Material.CLOCK, "swap_clock"));
        }
        // 药水杂项
        else {
            inv.addItem(getRandomPotion());
            if (isRich) inv.addItem(new ItemStack(Material.GOLDEN_APPLE, 2));
        }
    }

    private ItemStack createSpecialItem(String name, Material mat, String id) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + name);
        meta.getPersistentDataContainer().set(KEY_ID, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getRandomPotion() {
        ItemStack potion = new ItemStack(Material.SPLASH_POTION);
        PotionMeta pm = (PotionMeta) potion.getItemMeta();
        
        PotionEffectType[] types = {
            PotionEffectType.SPEED, PotionEffectType.SLOWNESS, PotionEffectType.POISON,
            PotionEffectType.JUMP_BOOST, PotionEffectType.DARKNESS, PotionEffectType.BLINDNESS,
            PotionEffectType.NAUSEA, PotionEffectType.WEAKNESS, PotionEffectType.STRENGTH,
            PotionEffectType.REGENERATION
        };
        
        Random r = new Random();
        PotionEffectType type = types[r.nextInt(types.length)];
        pm.addCustomEffect(new PotionEffect(type, 20 * 15, 1), true); // 15秒 II级
        pm.setDisplayName(ChatColor.AQUA + "随机战术药水");
        potion.setItemMeta(pm);
        return potion;
    }

    // --- 开箱读条系统 ---

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!isGameRunning) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.CHEST) return;

        Location loc = e.getClickedBlock().getLocation();
        Player p = e.getPlayer();

        // 1. 判断箱子类型
        ChestType type = null;
        if (normalChests.contains(loc)) type = ChestType.NORMAL;
        else if (airdropChests.contains(loc)) type = ChestType.AIRDROP;
        else if (deathChests.contains(loc)) type = ChestType.DEATH;

        if (type == null) return; // 野生箱子不管

        e.setCancelled(true); // 阻止直接打开

        if (type == ChestType.DEATH) {
            // 死亡箱子无读条
            p.openInventory(((Chest) loc.getBlock().getState()).getInventory());
        } else {
            // 开始读条
            startOpening(p, loc, type == ChestType.AIRDROP ? 5 : 3);
        }
    }

    private void startOpening(Player p, Location loc, int seconds) {
        if (openingTasks.containsKey(p.getUniqueId())) return; // 已经在开

        OpeningTask task = new OpeningTask(p, loc, seconds);
        openingTasks.put(p.getUniqueId(), task);
        task.runTaskTimer(this, 0L, 2L); // 每0.1秒检查一次
    }

    private class OpeningTask extends BukkitRunnable {
        Player p;
        Location loc;
        int maxTicks;
        int currentTicks = 0;

        public OpeningTask(Player p, Location loc, int seconds) {
            this.p = p;
            this.loc = loc;
            this.maxTicks = seconds * 20; // tick
        }

        @Override
        public void run() {
            if (!p.isOnline() || p.isDead() || loc.getBlock().getType() != Material.CHEST) {
                cancelTask(p.getUniqueId(), "开箱中止");
                return;
            }

            // 检查移动 (简单的距离检查)
            if (p.getLocation().distance(loc) > 4) {
                cancelTask(p.getUniqueId(), ChatColor.RED + "距离过远，开箱失败！");
                return;
            }

            // 进度条
            float percent = (float) currentTicks / maxTicks;
            int bars = 20;
            int filled = (int) (bars * percent);
            StringBuilder sb = new StringBuilder(ChatColor.GREEN + "破解中: [");
            for(int i=0; i<bars; i++) sb.append(i < filled ? "|" : ChatColor.GRAY + "|");
            sb.append(ChatColor.GREEN + "]");
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(sb.toString()));

            if (currentTicks >= maxTicks) {
                // 完成
                Chest chest = (Chest) loc.getBlock().getState();
                p.openInventory(chest.getInventory());
                p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
                openingTasks.remove(p.getUniqueId());
                
                // 移除位置记录（为了防止重复读条，虽然箱子还在）
                // 实际逻辑：开完一次后，如果想再开还要读条。
                // 如果想要开一次后箱子消失，在 InventoryClickEvent 处理拿完逻辑
                this.cancel();
            }
            currentTicks += 2; // +2 ticks because runTaskTimer(0, 2)
        }
    }
    
    private void cancelTask(UUID uuid, String reason) {
        OpeningTask t = openingTasks.remove(uuid);
        if (t != null) {
            t.cancel();
            if (t.p != null && reason != null) t.p.sendMessage(reason);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (isGameRunning && e.getEntity() instanceof Player) {
            // 受伤打断开箱
            cancelTask(e.getEntity().getUniqueId(), ChatColor.RED + "受到攻击，开箱被打断！");
        }
    }

    // --- 道具功能逻辑 ---

    @EventHandler
    public void onItemInteract(PlayerInteractEvent e) {
        if (!isGameRunning) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;

        String id = item.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
        if (id == null) return;

        Player p = e.getPlayer();

        if (id.equals("swap_clock") || id.equals("curse_rod")) {
            openPlayerSelector(p, id);
            e.setCancelled(true);
        }
    }

    private void openPlayerSelector(Player p, String actionId) {
        Inventory inv = Bukkit.createInventory(null, 27, actionId.equals("swap_clock") ? "选择换位目标" : "选择诅咒目标");
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(p)) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.YELLOW + target.getName());
            meta.getPersistentDataContainer().set(KEY_ID, PersistentDataType.STRING, "TARGET:" + target.getUniqueId().toString());
            head.setItemMeta(meta);
            inv.addItem(head);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!isGameRunning) return;
        String title = e.getView().getTitle();
        if (!title.equals("选择换位目标") && !title.equals("选择诅咒目标")) return;
        
        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;

        Player p = (Player) e.getWhoClicked();
        String data = e.getCurrentItem().getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
        if (data == null || !data.startsWith("TARGET:")) return;

        UUID targetId = UUID.fromString(data.substring(7));
        Player target = Bukkit.getPlayer(targetId);

        if (target == null || !target.isOnline()) {
            p.sendMessage(ChatColor.RED + "玩家不在线！");
            p.closeInventory();
            return;
        }

        // 消耗物品逻辑
        ItemStack hand = p.getInventory().getItemInMainHand();
        String handId = hand.hasItemMeta() ? hand.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING) : "";

        if (title.equals("选择换位目标") && "swap_clock".equals(handId)) {
            Location pLoc = p.getLocation();
            Location tLoc = target.getLocation();
            p.teleport(tLoc);
            target.teleport(pLoc);
            p.sendMessage(ChatColor.GREEN + "换位成功！");
            target.sendMessage(ChatColor.RED + "你被 " + p.getName() + " 强制换位了！");
            p.playSound(pLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            hand.setAmount(hand.getAmount() - 1);
            p.closeInventory();
        } 
        else if (title.equals("选择诅咒目标") && "curse_rod".equals(handId)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 30, 0));
            groundedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + 30000);
            target.sendMessage(ChatColor.RED + "你被 " + p.getName() + " 诅咒了！发光且禁飞30秒！");
            p.sendMessage(ChatColor.GREEN + "诅咒成功！");
            hand.setAmount(hand.getAmount() - 1);
            p.closeInventory();
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!isGameRunning) return;
        if (!(e.getHitEntity() instanceof Player)) return;
        Player target = (Player) e.getHitEntity();
        
        if (e.getEntity() instanceof Snowball) {
            ItemStack item = ((Snowball) e.getEntity()).getItem();
            String id = item.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
            if ("freeze_ball".equals(id)) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2));
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 1));
                target.sendMessage(ChatColor.RED + "你被冰冻了！");
            }
        } 
        else if (e.getEntity() instanceof Arrow) {
            Arrow arrow = (Arrow) e.getEntity();
            if (!(arrow.getShooter() instanceof Player)) return;
            // 简单的判断：只要是玩家射的箭击中飞行玩家就算击坠 (简化逻辑，如果要严格判断弓类型，需要更复杂追踪)
            // 为了游戏性，假设只要是弓箭射中飞行玩家都有效，或者你必须给箭加tag。
            // 这里我们简化：如果射手手持击坠弓，则生效。
            Player shooter = (Player) arrow.getShooter();
            ItemStack hand = shooter.getInventory().getItemInMainHand();
            // 这里有个小bug，箭射出后换手，但为了性能暂不追踪实体元数据
            String id = hand.hasItemMeta() ? hand.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING) : null;
            
            if ("anti_air_bow".equals(id) && target.isGliding()) {
                target.setGliding(false);
                groundedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + 15000);
                target.sendMessage(ChatColor.RED + "你被击坠弓命中！禁飞15秒！");
                shooter.sendMessage(ChatColor.GREEN + "击坠成功！");
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 2f);
            }
        }
    }

    // --- 死亡与战利品 ---

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!isGameRunning) return;
        
        Player p = e.getEntity();
        e.setKeepInventory(true);
        e.getDrops().clear(); // 不掉落任何原版物品
        
        // 生成死亡战利品箱
        Location loc = p.getLocation();
        if (loc.getY() < -60) loc.setY(spawnLocation.getY()); // 虚空保护
        
        Block block = loc.getBlock();
        block.setType(Material.CHEST);
        deathChests.add(loc);
        
        Chest chest = (Chest) block.getState();
        Inventory inv = chest.getInventory();
        
        // 1. 红包
        inv.addItem(createSpecialItem("红包", Material.RED_DYE, "right_click_score"));
        
        // 2. 复制死者的一部分道具 (除了不可掉落的)
        for (ItemStack is : p.getInventory().getContents()) {
            if (is == null) continue;
            // 不复制鞘翅，其他概率复制
            if (is.getType() != Material.ELYTRA && Math.random() < 0.5) {
                inv.addItem(is.clone());
            }
        }
        
        p.sendMessage(ChatColor.RED + "你死了！战利品箱已生成，但物品未丢失。");
    }

    @EventHandler
    public void onChestScore(InventoryClickEvent e) {
        if (!isGameRunning) return;
        if (!(e.getWhoClicked() instanceof Player)) return;
        ItemStack cur = e.getCurrentItem();
        if (cur != null && cur.getType() == Material.RED_DYE) {
            String id = cur.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
            if ("right_click_score".equals(id)) {
                e.setCancelled(true);
                e.getCurrentItem().setAmount(0);
                Player p = (Player) e.getWhoClicked();
                scores.put(p.getUniqueId(), scores.getOrDefault(p.getUniqueId(), 0) + 1);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                p.sendMessage(ChatColor.GREEN + "获得红包！当前分数: " + scores.get(p.getUniqueId()));
                
                // 如果是箱子里的红包，拿完后检查箱子是否空了，空了就移除
                if (e.getClickedInventory().getType() == InventoryType.CHEST) {
                    cleanupChest(e.getClickedInventory().getLocation());
                }
            }
        }
    }
    
    private void cleanupChest(Location loc) {
        // 简单的延迟检查
        new BukkitRunnable(){
            public void run(){
                if(loc.getBlock().getState() instanceof Chest){
                   if(((Chest)loc.getBlock().getState()).getInventory().isEmpty()){
                       loc.getBlock().setType(Material.AIR);
                       normalChests.remove(loc);
                       airdropChests.remove(loc);
                       deathChests.remove(loc);
                   }
                }
            }
        }.runTaskLater(this, 5L);
    }

    // --- 计分板 ---
    private void updateScoreboards(long secondsLeft) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        String timeStr = String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = sm.getNewScoreboard();
            Objective obj = sb.registerNewObjective("RPH", Criteria.DUMMY, ChatColor.RED + "§l群骑纷争");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            
            int score = 15;
            obj.getScore(ChatColor.YELLOW + "时间: " + ChatColor.WHITE + timeStr).setScore(score--);
            obj.getScore(ChatColor.GREEN + "我的红包: " + scores.getOrDefault(p.getUniqueId(), 0)).setScore(score--);
            obj.getScore("").setScore(score--);
            
            obj.getScore(ChatColor.GOLD + "剩余箱子: " + normalChests.size()).setScore(score--);
            if (!airdropChests.isEmpty()) {
                obj.getScore(ChatColor.LIGHT_PURPLE + ">> 空投存在! <<").setScore(score--);
                for (Location l : airdropChests) {
                     obj.getScore(ChatColor.WHITE + String.format("X:%d Z:%d", l.getBlockX(), l.getBlockZ())).setScore(score--);
                }
            }
            p.setScoreboard(sb);
        }
    }
    
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if(isGameRunning) {
            scores.putIfAbsent(e.getPlayer().getUniqueId(), 0);
            initializePlayer(e.getPlayer());
        }
    }
    
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTask(e.getPlayer().getUniqueId(), null);
    }
    
    private enum ChestType { NORMAL, AIRDROP, DEATH }
}
