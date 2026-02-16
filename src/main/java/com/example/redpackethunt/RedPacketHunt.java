package com.example.redpackethunt;

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
import org.bukkit.event.block.Action; // [修复] 添加了缺少的 Action 导入
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.util.*;

public class RedPacketHunt extends JavaPlugin implements Listener, CommandExecutor {

    // --- 核心变量 ---
    private Location spawnLocation;
    private boolean isGameRunning = false;
    private int gameDurationMinutes = 20;
    private long gameEndTime;
    
    // 禁用飞行/鞘翅的玩家 UUID -> 过期时间戳
    private final Map<UUID, Long> groundedPlayers = new HashMap<>();
    // 正在开箱子的玩家 UUID -> 任务ID
    private final Map<UUID, Integer> openingTasks = new HashMap<>();
    // 记录空投位置以便播放特效
    private final List<Location> activeAirdrops = new ArrayList<>();
    // 记录所有游戏生成的箱子位置（用于清理和计分板显示）
    private final List<Location> allGameChests = new ArrayList<>();

    // NamespacedKeys 用于识别特殊物品和箱子类型
    private final NamespacedKey KEY_CHEST_TYPE = new NamespacedKey(this, "chest_type"); // 0=普通, 1=红包箱, 2=空投, 3=死亡箱
    private final NamespacedKey KEY_SPECIAL_ITEM = new NamespacedKey(this, "special_item");

    @Override
    public void onEnable() {
        getCommand("rph").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RedPacketHunt 2.0 (1.21 修复版) 已加载！");
        
        // 全局循环：处理空投特效和飞行封禁过期
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) return;

                // 空投粒子光束
                for (Location loc : activeAirdrops) {
                    if (loc.getBlock().getType() != Material.CHEST) continue;
                    loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 1, 0.5), 1, 0, 0.5, 0, 0.05);
                    for (int i = 1; i < 50; i += 2) {
                        loc.getWorld().spawnParticle(Particle.WAX_ON, loc.clone().add(0.5, i, 0.5), 1);
                    }
                }

                // 检查飞行封禁过期
                long now = System.currentTimeMillis();
                groundedPlayers.entrySet().removeIf(entry -> {
                   if (now > entry.getValue()) {
                       Player p = Bukkit.getPlayer(entry.getKey());
                       if (p != null) p.sendMessage(ChatColor.GREEN + "你的飞行诅咒已解除！");
                       return true;
                   }
                   return false;
                });
            }
        }.runTaskTimer(this, 20L, 20L);
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
            player.sendMessage(ChatColor.GREEN + "游戏中心点已设置！");
            return true;
        }
        if (args[0].equalsIgnoreCase("setduration")) {
            if (args.length < 2) return false;
            gameDurationMinutes = Integer.parseInt(args[1]);
            player.sendMessage(ChatColor.GREEN + "时长已设为 " + gameDurationMinutes + " 分钟");
            return true;
        }
        if (args[0].equalsIgnoreCase("start")) {
            if (spawnLocation == null) {
                player.sendMessage(ChatColor.RED + "请先设置坐标！");
                return true;
            }
            if (isGameRunning) {
                player.sendMessage(ChatColor.RED + "游戏已在进行中！");
                return true;
            }
            startGame();
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) {
            stopGame(true);
            Bukkit.broadcastMessage(ChatColor.RED + "管理员强制停止了游戏！");
            return true;
        }
        return true;
    }

    // --- 游戏主逻辑 ---

    private void startGame() {
        isGameRunning = true;
        gameEndTime = System.currentTimeMillis() + (gameDurationMinutes * 60 * 1000L);
        activeAirdrops.clear();
        allGameChests.clear();
        groundedPlayers.clear();

        // 初始化玩家
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getInventory().clear();
            p.teleport(spawnLocation);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            
            // 初始装备
            giveStarterKit(p);
            p.sendTitle(ChatColor.GOLD + "游戏开始！", ChatColor.YELLOW + "搜寻箱子，击败对手！", 10, 60, 20);
        }

        // 1. 定时任务：每3分钟发烟花
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) { cancel(); return; }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.getInventory().addItem(new ItemStack(Material.FIREWORK_ROCKET, 16));
                    p.sendMessage(ChatColor.AQUA + "[补给] 已发放 16 个烟花火箭！");
                }
            }
        }.runTaskTimer(this, 3 * 60 * 20L, 3 * 60 * 20L);

        // 2. 定时任务：每5分钟生成空投
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) { cancel(); return; }
                spawnAirdrop();
            }
        }.runTaskTimer(this, 5 * 60 * 20L, 5 * 60 * 20L);

        // 3. 初始生成一些普通红包箱子 (例如 20 个)
        for(int i=0; i<20; i++) spawnRandomChest(false);

        // 4. 游戏结束倒计时
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isGameRunning) { cancel(); return; }
                long timeLeft = (gameEndTime - System.currentTimeMillis()) / 1000;
                updateScoreboard(timeLeft);
                if (timeLeft <= 0) {
                    stopGame(false);
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void stopGame(boolean force) {
        isGameRunning = false;
        // 清理箱子
        for (Location loc : allGameChests) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR);
        }
        allGameChests.clear();
        activeAirdrops.clear();
        openingTasks.clear();
        groundedPlayers.clear();

        if (!force) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "游戏结束！");
        }
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.teleport(spawnLocation);
            p.getInventory().clear();
        }
    }

    private void giveStarterKit(Player p) {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.setUnbreakable(true);
        elytra.setItemMeta(meta);
        
        p.getInventory().setChestplate(elytra);
        p.getInventory().setItemInMainHand(new ItemStack(Material.FIREWORK_ROCKET, 16));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
    }

    // --- 生成逻辑 ---

    // type: 1=Normal(RedPacket), 2=Airdrop, 3=Death
    private void createGameChest(Location loc, int type) {
        loc.getBlock().setType(Material.CHEST);
        if (!(loc.getBlock().getState() instanceof Chest)) return;
        
        Chest chest = (Chest) loc.getBlock().getState();
        chest.getPersistentDataContainer().set(KEY_CHEST_TYPE, PersistentDataType.INTEGER, type);
        
        // 如果是死亡箱子，外面处理内容；如果是普通/空投，这里填充随机战利品
        if (type != 3) {
            fillChestWithLoot(chest.getInventory(), type == 2);
        }
        
        chest.update();
        allGameChests.add(loc);
        if (type == 2) activeAirdrops.add(loc);
    }

    private void spawnRandomChest(boolean isAirdrop) {
        World world = spawnLocation.getWorld();
        Random r = new Random();
        int range = 400; // 范围
        int x = spawnLocation.getBlockX() + r.nextInt(range * 2) - range;
        int z = spawnLocation.getBlockZ() + r.nextInt(range * 2) - range;
        Block block = world.getHighestBlockAt(x, z).getRelative(0, 1, 0);
        
        createGameChest(block.getLocation(), 1); // 普通箱子
    }

    private void spawnAirdrop() {
        World world = spawnLocation.getWorld();
        Random r = new Random();
        int range = 300;
        int x = spawnLocation.getBlockX() + r.nextInt(range * 2) - range;
        int z = spawnLocation.getBlockZ() + r.nextInt(range * 2) - range;
        Block block = world.getHighestBlockAt(x, z).getRelative(0, 1, 0);

        createGameChest(block.getLocation(), 2); // 空投
        
        Bukkit.broadcastMessage(ChatColor.RED + "========== 空投降临 ==========");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "坐标: X:" + x + " Y:" + block.getY() + " Z:" + z);
        Bukkit.broadcastMessage(ChatColor.GOLD + "里面包含丰厚物资！");
        
        world.spawnParticle(Particle.EXPLOSION, block.getLocation(), 10);
        world.playSound(block.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1f);
    }

    // --- 战利品系统 ---
    
    private void fillChestWithLoot(Inventory inv, boolean isAirdrop) {
        inv.clear();
        Random r = new Random();
        int itemsCount = isAirdrop ? r.nextInt(5) + 5 : r.nextInt(3) + 2;

        List<ItemStack> pool = new ArrayList<>();
        
        // 基础物资
        pool.add(new ItemStack(Material.GOLDEN_APPLE, r.nextInt(2)+1));
        pool.add(new ItemStack(Material.ENDER_PEARL, r.nextInt(2)+1));
        pool.add(new ItemStack(Material.ARROW, 16));
        
        // 药水
        pool.add(createPotion(PotionType.STRONG_HEALING, false));
        pool.add(createPotion(PotionType.STRONG_STRENGTH, false));
        pool.add(createPotion(PotionType.LONG_SWIFTNESS, false));
        pool.add(createPotion(PotionType.STRONG_LEAPING, false));
        pool.add(createPotion(PotionType.LONG_WEAKNESS, true)); // 投掷
        pool.add(createPotion(PotionType.STRONG_HARMING, true));
        pool.add(createPotion(PotionType.STRONG_POISON, true));
        pool.add(createPotion(PotionType.STRONG_SLOWNESS, true));

        // 特殊道具 (权重较低，空投里权重高)
        if (r.nextInt(100) < (isAirdrop ? 80 : 20)) pool.add(getSpecialItem("ice_snowball"));
        if (r.nextInt(100) < (isAirdrop ? 50 : 10)) pool.add(getSpecialItem("swap_pearl")); // 钟
        if (r.nextInt(100) < (isAirdrop ? 50 : 10)) pool.add(getSpecialItem("downfall_bow"));
        if (r.nextInt(100) < (isAirdrop ? 40 : 5)) pool.add(getSpecialItem("cursed_rod"));
        if (r.nextInt(100) < (isAirdrop ? 60 : 15)) pool.add(getSpecialItem("knockback_stick"));

        for (int i = 0; i < itemsCount; i++) {
            if (pool.isEmpty()) break;
            ItemStack item = pool.get(r.nextInt(pool.size()));
            int slot = r.nextInt(inv.getSize());
            while(inv.getItem(slot) != null) slot = r.nextInt(inv.getSize());
            inv.setItem(slot, item);
        }
    }

    // --- 特殊物品工厂 ---

    private ItemStack getSpecialItem(String type) {
        ItemStack item;
        ItemMeta meta;
        
        switch (type) {
            case "ice_snowball":
                item = new ItemStack(Material.SNOWBALL, 1);
                meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.AQUA + "冰冻雪球");
                meta.setLore(Arrays.asList(ChatColor.GRAY + "击中给予5秒缓慢+失明"));
                break;
            case "swap_pearl":
                item = new ItemStack(Material.CLOCK);
                meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.LIGHT_PURPLE + "换位怀表");
                meta.setLore(Arrays.asList(ChatColor.GRAY + "右键选择玩家交换位置", ChatColor.RED + "一次性用品"));
                break;
            case "downfall_bow":
                item = new ItemStack(Material.BOW);
                meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.GOLD + "击坠弓");
                meta.setLore(Arrays.asList(ChatColor.GRAY + "击中飞行玩家使其坠落", ChatColor.GRAY + "并禁止飞行15秒"));
                break;
            case "knockback_stick":
                item = new ItemStack(Material.STICK);
                meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.YELLOW + "快乐击退棒");
                meta.addEnchant(Enchantment.KNOCKBACK, 2, true);
                break;
            case "cursed_rod":
                item = new ItemStack(Material.BLAZE_ROD);
                meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.RED + "诅咒烈焰棒");
                meta.setLore(Arrays.asList(ChatColor.GRAY + "右键选择玩家进行诅咒", ChatColor.GRAY + "发光+禁飞30秒", ChatColor.RED + "一次性用品"));
                break;
            default:
                return new ItemStack(Material.AIR);
        }
        
        meta.getPersistentDataContainer().set(KEY_SPECIAL_ITEM, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPotion(PotionType type, boolean splash) {
        ItemStack item = new ItemStack(splash ? Material.SPLASH_POTION : Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    // --- 事件监听 ---

    // 1. 开箱读条机制
    @EventHandler
    public void onChestOpen(PlayerInteractEvent e) {
        if (!isGameRunning) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.CHEST) return;

        Block b = e.getClickedBlock();
        if (!(b.getState() instanceof Chest)) return;
        Chest chest = (Chest) b.getState();
        
        PersistentDataContainer data = chest.getPersistentDataContainer();
        if (!data.has(KEY_CHEST_TYPE, PersistentDataType.INTEGER)) return; // 不是游戏箱子

        int type = data.get(KEY_CHEST_TYPE, PersistentDataType.INTEGER);
        
        // 死亡箱子(type 3)无读条
        if (type == 3) return;

        e.setCancelled(true); // 阻止立即打开
        
        Player p = e.getPlayer();
        if (openingTasks.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "你正在开启另一个箱子！");
            return;
        }

        int durationSeconds = (type == 2) ? 5 : 3; // 空投5秒，普通3秒
        startOpeningProcess(p, chest, durationSeconds);
    }

    private void startOpeningProcess(Player p, Chest chest, int seconds) {
        p.sendMessage(ChatColor.YELLOW + "正在开启箱子... 请保持不动 " + seconds + " 秒");
        Location startLoc = p.getLocation();
        
        int taskId = new BukkitRunnable() {
            int timeLeft = seconds * 20; // ticks
            
            @Override
            public void run() {
                // 校验取消条件：移动、死亡、箱子消失
                if (!p.isOnline() || p.isDead() || chest.getBlock().getType() != Material.CHEST || 
                    startLoc.distance(p.getLocation()) > 0.5) {
                    p.sendTitle("", ChatColor.RED + "开启打断！", 0, 20, 10);
                    openingTasks.remove(p.getUniqueId());
                    this.cancel();
                    return;
                }

                // 进度提示
                if (timeLeft % 20 == 0) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                    p.sendTitle(ChatColor.GOLD + "开启中...", ChatColor.YELLOW + String.valueOf(timeLeft/20), 0, 10, 0);
                }

                if (timeLeft <= 0) {
                    // 完成
                    p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
                    p.openInventory(chest.getInventory());
                    
                    // 如果是空投，移除特效列表
                    activeAirdrops.remove(chest.getLocation());
                    
                    openingTasks.remove(p.getUniqueId());
                    this.cancel();
                }
                timeLeft--;
            }
        }.runTaskTimer(this, 0L, 1L).getTaskId();
        
        openingTasks.put(p.getUniqueId(), taskId);
    }

    // 防止破坏游戏箱子
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!isGameRunning) return;
        if (e.getBlock().getType() == Material.CHEST) {
             Chest c = (Chest) e.getBlock().getState();
             if (c.getPersistentDataContainer().has(KEY_CHEST_TYPE, PersistentDataType.INTEGER)) {
                 if (!e.getPlayer().isOp()) {
                     e.setCancelled(true);
                     e.getPlayer().sendMessage(ChatColor.RED + "不能破坏补给箱！");
                 }
             }
        }
    }

    // 2. 死亡处理
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!isGameRunning) return;
        Player p = e.getEntity();
        
        // 保留物品，清除掉落
        e.setKeepInventory(true);
        e.getDrops().clear();
        
        Location deathLoc = p.getLocation();
        
        // 生成死亡红包箱
        deathLoc.getBlock().setType(Material.CHEST);
        if (deathLoc.getBlock().getState() instanceof Chest) {
            Chest chest = (Chest) deathLoc.getBlock().getState();
            chest.getPersistentDataContainer().set(KEY_CHEST_TYPE, PersistentDataType.INTEGER, 3); // 3=死亡箱
            
            Inventory inv = chest.getInventory();
            // 复制死者物品
            for (ItemStack item : p.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    // 随机位置放入
                    int slot = getEmptySlot(inv);
                    if (slot != -1) inv.setItem(slot, item.clone());
                }
            }
            // 额外随机奖励
            inv.addItem(getSpecialItem("ice_snowball")); // 稍微给点安慰奖给捡尸者
            
            chest.update();
            allGameChests.add(deathLoc);
        }
        
        p.sendMessage(ChatColor.RED + "你死了！你的物资被复制并留在了原地。");
    }

    private int getEmptySlot(Inventory inv) {
        for (int i=0; i<inv.getSize(); i++) {
            if (inv.getItem(i) == null) return i;
        }
        return -1;
    }

    // 3. 特殊物品互动
    @EventHandler
    public void onInteractItem(PlayerInteractEvent e) {
        if (!isGameRunning) return;
        if (e.getItem() == null) return;
        if (!e.getItem().hasItemMeta()) return;
        
        PersistentDataContainer data = e.getItem().getItemMeta().getPersistentDataContainer();
        if (!data.has(KEY_SPECIAL_ITEM, PersistentDataType.STRING)) return;
        
        String type = data.get(KEY_SPECIAL_ITEM, PersistentDataType.STRING);
        Player p = e.getPlayer();

        if (e.getAction().toString().contains("RIGHT_CLICK")) {
            if (type.equals("swap_pearl") || type.equals("cursed_rod")) {
                e.setCancelled(true);
                openPlayerSelector(p, type);
            }
        }
    }

    // 3.1 GUI 选择器
    private void openPlayerSelector(Player p, String itemType) {
        List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
        targets.remove(p); // 排除自己
        
        if (targets.isEmpty()) {
            p.sendMessage(ChatColor.RED + "没有其他存活玩家！");
            return;
        }

        int size = (targets.size() / 9 + 1) * 9;
        Inventory gui = Bukkit.createInventory(null, size, 
            itemType.equals("swap_pearl") ? "选择交换目标" : "选择诅咒目标");
        
        for (Player target : targets) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.YELLOW + target.getName());
            head.setItemMeta(meta);
            gui.addItem(head);
        }
        p.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!isGameRunning) return;
        String title = e.getView().getTitle();
        if (!title.equals("选择交换目标") && !title.equals("选择诅咒目标")) return;
        
        e.setCancelled(true);
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() != Material.PLAYER_HEAD) return;
        
        Player p = (Player) e.getWhoClicked();
        String targetName = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());
        Player target = Bukkit.getPlayer(targetName);
        
        if (target == null || !target.isOnline()) {
            p.sendMessage(ChatColor.RED + "玩家已离线！");
            p.closeInventory();
            return;
        }

        // 扣除物品
        ItemStack hand = p.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);

        if (title.equals("选择交换目标")) {
            Location loc1 = p.getLocation();
            Location loc2 = target.getLocation();
            p.teleport(loc2);
            target.teleport(loc1);
            p.sendMessage(ChatColor.GREEN + "位置交换成功！");
            target.sendMessage(ChatColor.RED + "你被 " + p.getName() + " 交换了位置！");
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        } else {
            // 诅咒：发光 + 禁飞
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30*20, 0));
            groundPlayer(target, 30);
            p.sendMessage(ChatColor.GREEN + "诅咒成功！");
            target.sendMessage(ChatColor.RED + "你被诅咒了！无法飞行且全身发光！");
            target.playSound(target.getLocation(), Sound.ENTITY_GHAST_SCREAM, 1f, 1f);
        }
        p.closeInventory();
    }

    // 4. 战斗道具逻辑
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!isGameRunning) return;
        if (!(e.getHitEntity() instanceof Player)) return;
        
        Player target = (Player) e.getHitEntity();
        Projectile proj = e.getEntity();
        
        // 击坠弓
        if (proj instanceof Arrow && proj.getShooter() instanceof Player) {
            Player shooter = (Player) proj.getShooter();
            ItemStack bow = shooter.getInventory().getItemInMainHand();
            if (isSpecialItem(bow, "downfall_bow")) {
                if (target.isGliding()) {
                    target.setGliding(false);
                    groundPlayer(target, 15);
                    target.sendMessage(ChatColor.RED + "你被击坠了！15秒内无法飞行！");
                    shooter.sendMessage(ChatColor.GREEN + "成功击坠目标！");
                    target.playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
                }
            }
        }
        
        // 冰冻雪球
        if (proj instanceof Snowball && proj.getShooter() instanceof Player) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5*20, 2));
            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 5*20, 1));
            target.sendMessage(ChatColor.AQUA + "你被冰冻了！");
        }
    }

    // 禁飞逻辑
    private void groundPlayer(Player p, int seconds) {
        groundedPlayers.put(p.getUniqueId(), System.currentTimeMillis() + (seconds * 1000L));
        if (p.isGliding()) p.setGliding(false);
    }
    
    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent e) {
        if (!isGameRunning) return;
        if (e.getEntity() instanceof Player && e.isGliding()) {
            Player p = (Player) e.getEntity();
            if (groundedPlayers.containsKey(p.getUniqueId())) {
                long expire = groundedPlayers.get(p.getUniqueId());
                if (System.currentTimeMillis() < expire) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "你处于禁飞状态！");
                } else {
                    groundedPlayers.remove(p.getUniqueId());
                }
            }
        }
    }

    // 辅助：检查物品是否为特殊物品
    private boolean isSpecialItem(ItemStack item, String id) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return data.has(KEY_SPECIAL_ITEM, PersistentDataType.STRING) && 
               data.get(KEY_SPECIAL_ITEM, PersistentDataType.STRING).equals(id);
    }

    // --- 计分板更新 (显示最近的5个箱子) ---
    private void updateScoreboard(long secondsLeft) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        String timeStr = String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = manager.getNewScoreboard();
            Objective obj = board.registerNewObjective("RPH", Criteria.DUMMY, ChatColor.RED + "§l全服红包大乱斗");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            int line = 15; // 从第15行开始倒序排列

            // --- 1. 基础信息 ---
            obj.getScore(ChatColor.YELLOW + "倒计时: " + ChatColor.WHITE + timeStr).setScore(line--);
            obj.getScore(ChatColor.GRAY + "----------------").setScore(line--);

            // --- 2. 状态显示 (禁飞倒计时) ---
            if (groundedPlayers.containsKey(p.getUniqueId())) {
                long left = (groundedPlayers.get(p.getUniqueId()) - System.currentTimeMillis()) / 1000;
                if (left > 0) {
                    obj.getScore(ChatColor.RED + "⚠ 禁飞剩余: " + ChatColor.BOLD + left + "s").setScore(line--);
                }
            }

            // --- 3. 最近的5个箱子逻辑 ---
            // 过滤出有效的箱子列表（必须在同一个世界且方块仍然是箱子）
            List<Location> validChests = new ArrayList<>();
            for (Location loc : allGameChests) {
                if (loc.getWorld().equals(p.getWorld()) && loc.getBlock().getType() == Material.CHEST) {
                    validChests.add(loc);
                }
            }

            // 根据距离玩家的远近进行排序 (近 -> 远)
            validChests.sort((c1, c2) -> Double.compare(c1.distanceSquared(p.getLocation()), c2.distanceSquared(p.getLocation())));

            if (!validChests.isEmpty()) {
                obj.getScore(ChatColor.GOLD + ">> 最近物资坐标 <<").setScore(line--);
                
                // 取前5个 (或者少于5个时的全部)
                int count = 0;
                for (Location loc : validChests) {
                    if (count >= 5 || line <= 0) break;

                    int dist = (int) loc.distance(p.getLocation());
                    
                    // 格式：x100 y64 z100 (50m)
                    String coordStr = String.format("x%d y%d z%d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                    
                    // 默认显示颜色
                    String entry = ChatColor.AQUA + coordStr + ChatColor.YELLOW + " (" + dist + "m)";
                    
                    // 检查是否是空投 (如果是空投，用紫色显示)
                    if (activeAirdrops.contains(loc)) {
                        entry = ChatColor.LIGHT_PURPLE + coordStr + ChatColor.GOLD + " (空投)";
                    }

                    obj.getScore(entry).setScore(line--);
                    count++;
                }
            } else {
                obj.getScore(ChatColor.GRAY + "暂无探测到物资...").setScore(line--);
            }

            // --- 4. 底部信息 ---
            if (line > 0) obj.getScore(" ").setScore(line--); // 空行
            if (line > 0) obj.getScore(ChatColor.GREEN + "存活玩家: " + Bukkit.getOnlinePlayers().size()).setScore(line--);

            p.setScoreboard(board);
        }
    }
}
