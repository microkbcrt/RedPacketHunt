package com.example.redpackethunt;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class GameListener implements Listener {

    private final RedPacketHunt plugin;
    private final Random random = new Random();

    // 特殊物品的标识符
    private static final String ITEM_SWAP = "ITEM_SWAP";
    private static final String ITEM_CURSE = "ITEM_CURSE";
    private static final String ITEM_BOW = "ITEM_BOW";
    
    // 正在选人的玩家 <玩家UUID, 物品类型>
    private final Map<UUID, String> selectorMap = new HashMap<>();

    public GameListener(RedPacketHunt plugin) {
        this.plugin = plugin;
    }

    // --- 物品生成器 (供主类调用) ---

    public static ItemStack getRedPacketItem() {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "§l[红包]");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "点击即领", ChatColor.YELLOW + "活动专用"));
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack getRandomSpecialItem(boolean isAirdrop) {
        Random r = new Random();
        int roll = r.nextInt(100);

        // 药水 (30%)
        if (roll < 30) {
            return getRandomPotion();
        }
        // 冰冻雪球 (20%)
        if (roll < 50) {
            ItemStack ball = new ItemStack(Material.SNOWBALL, r.nextInt(3) + 1);
            ItemMeta meta = ball.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + "冰冻雪球");
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "击中给予缓慢与失明"));
            ball.setItemMeta(meta);
            return ball;
        }
        // 击退棒 (15%)
        if (roll < 65) {
            ItemStack stick = new ItemStack(Material.STICK);
            ItemMeta meta = stick.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "快乐击退棒");
            meta.addEnchant(Enchantment.KNOCKBACK, 2, true);
            stick.setItemMeta(meta);
            return stick;
        }
        // 击坠弓 (15%)
        if (roll < 80) { // 这里原本的概率判断保持不变
            ItemStack bow = new ItemStack(Material.BOW);
            ItemMeta meta = bow.getItemMeta();
            meta.setDisplayName(ChatColor.RED + "击坠弓");
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "射中飞行玩家使其坠落"));
            
            // --- [新增] 添加无限附魔 ---
            meta.addEnchant(Enchantment.INFINITY, 1, true);
            
            bow.setItemMeta(meta);
            return bow;
        }
        // 稀有物品: 换位钟 (10%)
        if (roll < 90) {
            ItemStack clock = new ItemStack(Material.CLOCK);
            ItemMeta meta = clock.getItemMeta();
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "换位怀表");
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "右键选择玩家交换位置"));
            // 标记NBT方便识别
            clock.setItemMeta(meta); 
            return clock;
        }
        // 稀有物品: 诅咒烈焰棒 (10%)
        ItemStack rod = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_RED + "诅咒法杖");
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "右键诅咒玩家：发光且禁飞"));
        rod.setItemMeta(meta);
        return rod;
    }

    private static ItemStack getRandomPotion() {
        Material[] pots = {Material.SPLASH_POTION, Material.LINGERING_POTION};
        ItemStack pot = new ItemStack(pots[new Random().nextInt(pots.length)]);
        PotionMeta pm = (PotionMeta) pot.getItemMeta();
        
        PotionEffectType[] types = {
            PotionEffectType.SPEED, PotionEffectType.SLOWNESS, PotionEffectType.POISON,
            PotionEffectType.JUMP_BOOST, PotionEffectType.DARKNESS, PotionEffectType.BLINDNESS,
            PotionEffectType.NAUSEA, PotionEffectType.WEAKNESS, PotionEffectType.STRENGTH,
            PotionEffectType.REGENERATION
        };
        
        PotionEffectType type = types[new Random().nextInt(types.length)];
        pm.addCustomEffect(new PotionEffect(type, 20 * 15, 1), true);
        pm.setDisplayName(ChatColor.BLUE + "随机药水");
        pot.setItemMeta(pm);
        return pot;
    }

    // --- 核心交互逻辑: 开箱读条 ---

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!plugin.isGameRunning) return;
        Player p = e.getPlayer();
        
        // 1. 处理特殊物品右键逻辑
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = e.getItem();
            if (item != null && item.hasItemMeta()) {
                String name = item.getItemMeta().getDisplayName();
                if (name.contains("换位怀表")) {
                    openPlayerSelector(p, ITEM_SWAP);
                    e.setCancelled(true);
                    return;
                }
                if (name.contains("诅咒法杖")) {
                    openPlayerSelector(p, ITEM_CURSE);
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // 2. 处理箱子开启读条
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock().getType() == Material.CHEST) {
            Block block = e.getClickedBlock();
            Location loc = block.getLocation();

            boolean isGameChest = isLocationInList(loc, plugin.activeChests);
            boolean isAirdrop = isLocationInList(loc, plugin.activeAirdrops);

            // 如果是游戏生成的箱子（非死亡掉落箱），则需要读条
            if (isGameChest || isAirdrop) {
                e.setCancelled(true); // 阻止直接打开

                if (plugin.openingTasks.containsKey(p.getUniqueId())) {
                    p.sendMessage(ChatColor.RED + "你正在开启另一个箱子！");
                    return;
                }

                int durationSeconds = isAirdrop ? 5 : 3;
                startOpeningTask(p, block, durationSeconds);
            }
        }
    }

    private boolean isLocationInList(Location target, List<Location> list) {
        for (Location loc : list) {
            if (loc.getBlockX() == target.getBlockX() && 
                loc.getBlockY() == target.getBlockY() && 
                loc.getBlockZ() == target.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    private void startOpeningTask(Player p, Block chestBlock, int seconds) {
        p.sendMessage(ChatColor.YELLOW + "正在开启... 请保持不动 " + seconds + " 秒");
        final Location startLoc = p.getLocation();
        
        BukkitTask task = new BukkitRunnable() {
            int timeLeft = seconds * 4; // Check every 5 ticks (0.25s), total checks

            @Override
            public void run() {
                // 校验取消条件
                if (!p.isOnline() || p.getLocation().distance(startLoc) > 1.0 || !plugin.isGameRunning) {
                    p.sendMessage(ChatColor.RED + "开启被打断！");
                    plugin.openingTasks.remove(p.getUniqueId());
                    this.cancel();
                    return;
                }
                
                // 播放进度特效
                p.spawnParticle(Particle.CRIT, chestBlock.getLocation().add(0.5, 1, 0.5), 5);
                p.playSound(p.getLocation(), Sound.BLOCK_WOOD_HIT, 0.5f, 1f);

                timeLeft--;
                if (timeLeft <= 0) {
                    // 完成开启
                    p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
                    p.openInventory(((Chest) chestBlock.getState()).getInventory());
                    plugin.openingTasks.remove(p.getUniqueId());
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
        
        plugin.openingTasks.put(p.getUniqueId(), task);
    }

    // --- 物品栏交互与GUI ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!plugin.isGameRunning) return;
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();

        // 1. 处理选择玩家GUI (换位/诅咒)
        if (e.getView().getTitle().equals("选择目标玩家")) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null && clicked.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) clicked.getItemMeta();
                if (meta.getOwningPlayer() != null && meta.getOwningPlayer().isOnline()) {
                    Player target = meta.getOwningPlayer().getPlayer();
                    handleSpecialItemEffect(p, target);
                }
                p.closeInventory();
            }
            return;
        }

        // 2. 处理获取红包
        ItemStack item = e.getCurrentItem();
        if (item != null && item.getType() == Material.RED_DYE && item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("红包")) {
            e.setCancelled(true);
            e.setCurrentItem(new ItemStack(Material.AIR)); // 移除红包
            
            // 计分
            plugin.scores.put(p.getUniqueId(), plugin.scores.getOrDefault(p.getUniqueId(), 0) + 1);
            p.sendMessage(ChatColor.RED + "恭喜！你抢到了一个红包！当前总数: " + plugin.scores.get(p.getUniqueId()));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

            // 清理箱子逻辑
            Inventory inv = e.getClickedInventory();
            if (inv != null && inv.getType() == InventoryType.CHEST && inv.getLocation() != null) {
                Location loc = inv.getLocation();
                // 尝试从活动列表中移除
                removeFromList(loc, plugin.activeChests);
                removeFromList(loc, plugin.activeAirdrops);
                
                // 视觉效果：如果拿完红包，箱子可以消失，或者保留让别人抢其他道具
                // 这里设定为红包拿走后，箱子作为普通容器保留，直到游戏结束清理，但从计分板移除
            }
        }
    }
    
    private void removeFromList(Location target, List<Location> list) {
        list.removeIf(loc -> loc.getBlockX() == target.getBlockX() && 
                             loc.getBlockY() == target.getBlockY() && 
                             loc.getBlockZ() == target.getBlockZ());
    }

    // --- 特殊道具技能实现 ---

    private void openPlayerSelector(Player p, String type) {
        Inventory inv = Bukkit.createInventory(null, 27, "选择目标玩家");
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(p)) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.YELLOW + target.getName());
            head.setItemMeta(meta);
            inv.addItem(head);
        }
        selectorMap.put(p.getUniqueId(), type);
        p.openInventory(inv);
    }

    private void handleSpecialItemEffect(Player user, Player target) {
        String type = selectorMap.remove(user.getUniqueId());
        if (type == null) return;

        if (type.equals(ITEM_SWAP)) {
            // 消耗道具
            consumeItemInHand(user, "换位怀表");
            
            Location locUser = user.getLocation();
            Location locTarget = target.getLocation();
            user.teleport(locTarget);
            target.teleport(locUser);
            
            user.sendMessage(ChatColor.GREEN + "换位成功！");
            target.sendMessage(ChatColor.RED + "有人强制与你交换了位置！");
            user.playSound(user.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        
        } else if (type.equals(ITEM_CURSE)) {
            consumeItemInHand(user, "诅咒法杖");
            
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30 * 20, 0));
            // 禁飞逻辑
            plugin.noFlyList.put(target.getUniqueId(), System.currentTimeMillis() + 30000);
            target.setGliding(false); // 立即打断飞行
            
            Bukkit.broadcastMessage(ChatColor.DARK_RED + target.getName() + " 被诅咒了！无法飞行且全身发光，持续30秒！快去击杀他！");
            target.sendTitle(ChatColor.DARK_RED + "你被诅咒了！", "无法使用鞘翅", 10, 40, 10);
        }
    }
    
    private void consumeItemInHand(Player p, String partialName) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.hasItemMeta() && hand.getItemMeta().getDisplayName().contains(partialName)) {
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    // --- 战斗与PVP逻辑 ---

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!plugin.isGameRunning) return;

        // 冰冻雪球
        if (e.getEntity() instanceof Snowball) {
            if (e.getHitEntity() instanceof Player) {
                Player target = (Player) e.getHitEntity();
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5 * 20, 2));
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 5 * 20, 1));
                target.sendMessage(ChatColor.AQUA + "你被冰冻雪球击中！");
            }
        }
        
        // 击坠弓
        if (e.getEntity() instanceof Arrow && e.getEntity().getShooter() instanceof Player) {
            if (e.getHitEntity() instanceof Player) {
                Player target = (Player) e.getHitEntity();
                Player shooter = (Player) e.getEntity().getShooter();
                
                // 检查射手是否持有击坠弓 (简化判断：只要名字对)
                // 注意：这里需要检查射出箭时的弓，比较难获取，这里简化为检查射手当前主手
                ItemStack hand = shooter.getInventory().getItemInMainHand();
                if (hand.getType() == Material.BOW && hand.hasItemMeta() && hand.getItemMeta().getDisplayName().contains("击坠弓")) {
                    if (target.isGliding()) {
                        plugin.noFlyList.put(target.getUniqueId(), System.currentTimeMillis() + 15000);
                        target.setGliding(false);
                        target.sendMessage(ChatColor.RED + "你被击坠了！15秒内无法飞行！");
                        shooter.sendMessage(ChatColor.GREEN + "成功击坠目标！");
                        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        if (!plugin.isGameRunning) return;
        // 检查禁飞
        if (e.isFlying() || e.getPlayer().isGliding()) { // isFlying creative check, usually gliding event is separate or EntityToggleGlideEvent
             // Spigot 1.21 specific: Glide is usually EntityToggleGlideEvent, here we check simple fly logic attempt
        }
    }
    
    // 专门处理鞘翅滑翔事件
    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (!plugin.isGameRunning) return;
        if (e.getEntity() instanceof Player && e.isGliding()) {
            Player p = (Player) e.getEntity();
            if (plugin.noFlyList.containsKey(p.getUniqueId())) {
                long expire = plugin.noFlyList.get(p.getUniqueId());
                if (System.currentTimeMillis() < expire) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "你处于禁飞状态！");
                } else {
                    plugin.noFlyList.remove(p.getUniqueId());
                }
            }
        }
    }

    // --- 死亡逻辑 ---

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!plugin.isGameRunning) return;
        
        Player victim = e.getEntity();
        e.getDrops().clear(); // 不掉落原始物品（保留在玩家身上）
        e.setKeepInventory(true); 
        
        // 创建物理尸体箱子
        Location loc = victim.getLocation();
        loc.getBlock().setType(Material.CHEST);
        if (loc.getBlock().getState() instanceof Chest) {
            Chest chest = (Chest) loc.getBlock().getState();
            Inventory inv = chest.getInventory();
            
            // 放入 "尸体红包" 
            // 随机复制死者身上的3-5样物品放进去
            Inventory victimInv = victim.getInventory();
            int itemsAdded = 0;
            for (ItemStack item : victimInv.getContents()) {
                if (item != null && item.getType() != Material.AIR && item.getType() != Material.ELYTRA) {
                    if (random.nextBoolean()) {
                        inv.addItem(item.clone()); // 复制一份放入箱子
                        itemsAdded++;
                    }
                }
                if (itemsAdded >= 5) break;
            }
            
            // 如果死者身上有红包分数，是否掉落？题目说“不掉落道具”，没说扣分。
            // 题目只说“尸体位置掉落一个物理形式的红包物品（箱子）”
            // 我们可以在箱子里放一个特殊的改名物品叫 "死者的遗物"
            ItemStack loot = new ItemStack(Material.PAPER);
            ItemMeta meta = loot.getItemMeta();
            meta.setDisplayName(ChatColor.RED + victim.getName() + " 的遗物");
            loot.setItemMeta(meta);
            inv.addItem(loot);
            
            victim.sendMessage(ChatColor.GRAY + "你死后在原地留下了一个补给箱。");
        }
    }
    
    // --- 保护机制 ---
    
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (!plugin.isGameRunning) {
            if (!e.getPlayer().isOp()) e.setCancelled(true);
            return;
        }
        // 游戏中不允许破坏生成的箱子
        Location loc = e.getBlock().getLocation();
        if (isLocationInList(loc, plugin.activeChests) || isLocationInList(loc, plugin.activeAirdrops)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "不能破坏红包箱子！");
        }
    }
    
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (plugin.isFrozen && plugin.isGameRunning) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                e.setTo(e.getFrom()); // 冻结移动
            }
        }
    }
}
