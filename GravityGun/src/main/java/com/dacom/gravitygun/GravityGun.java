package com.dacom.gravitygun;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 단일 파일 중력건 플러그인
 * - 아이템: BLAZE_ROD, 표시이름 "&bGravity Gun"
 * - 우클릭: 집기/놓기, 좌클릭: 던지기, 쉬프트: 강제 놓기
 * - /gg give <player> : 중력건 지급
 * - /gg reload       : (있다면) config.yml 리로드. 없으면 코드 기본값 사용
 */
public class GravityGun extends JavaPlugin implements Listener, CommandExecutor {

    // ===== 기본 메시지/메커닉 값(설정 없을 때 사용) =====
    private String PREFIX = color("&b[GG]&r ");
    private String MSG_GOT_GUN = color("&a중력건을 지급했습니다.");
    private String MSG_RELOAD = color("&a설정을 리로드했습니다.");
    private String MSG_NO_PERM = color("&c권한이 없습니다.");
    private String MSG_ONLY_PLAYER = color("&c플레이어만 가능합니다.");
    private String MSG_NO_TARGET = color("&e대상 없음.");
    private String MSG_CANNOT = color("&c이 엔티티는 집을 수 없습니다.");
    private String MSG_GRABBED = color("&a대상을 집었습니다.");
    private String MSG_RELEASED = color("&e대상을 놓았습니다.");
    private String MSG_LAUNCHED = color("&b발사!");

    private Material GUN_MATERIAL = Material.BLAZE_ROD;
    private String GUN_NAME = color("&bGravity Gun");
    private int GUN_CMD = 0; // 커스텀 모델 데이터(리소스팩 쓰면 수치 기입)

    private double RANGE = 16.0;
    private double HOLD_DIST = 3.0;
    private double LAUNCH_POWER = 2.6;
    private boolean SMOOTH = true;
    private boolean ALLOW_GRAB_PLAYERS = true;
    private final Set<EntityType> BLACKLIST = EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN
    );
    private long GRAB_COOLDOWN_MS = 300;

    // ===== 런타임 상태 =====
    private NamespacedKey gunKey;
    private final Map<UUID, LivingEntity> held = new HashMap<>();
    private final Map<UUID, Boolean> originalGravity = new HashMap<>();
    private final Map<UUID, Boolean> originalCollidable = new HashMap<>();
    private final Map<UUID, Long> lastGrab = new HashMap<>();
    private BukkitTask tickTask;

    // ===== 플러그인 생명주기 =====
    @Override
    public void onEnable() {
        saveDefaultConfig();     // 있으면 사용, 없으면 무시
        gunKey = new NamespacedKey(this, "gravity_gun");

        // 설정 반영(있을 경우)
        loadConfigIfPresent();

        // 리스너 & 커맨드
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("gg")).setExecutor(this);

        // 틱 태스크 시작
        startTickTask();

        getLogger().info("GravityGun enabled (single-file)");
    }

    @Override
    public void onDisable() {
        releaseAll(true);
        if (tickTask != null) tickTask.cancel();
        getLogger().info("GravityGun disabled");
    }

    // ===== 설정 로드(선택적) =====
    private void loadConfigIfPresent() {
        FileConfiguration c = getConfig();
        PREFIX = color(c.getString("messages.prefix", PREFIX));
        MSG_GOT_GUN   = color(c.getString("messages.got_gun", MSG_GOT_GUN));
        MSG_RELOAD    = color(c.getString("messages.reload", MSG_RELOAD));
        MSG_NO_PERM   = color(c.getString("messages.no_perm", MSG_NO_PERM));
        MSG_ONLY_PLAYER = color(c.getString("messages.only_player", MSG_ONLY_PLAYER));
        MSG_NO_TARGET = color(c.getString("messages.no_target", MSG_NO_TARGET));
        MSG_CANNOT    = color(c.getString("messages.cannot_grab", MSG_CANNOT));
        MSG_GRABBED   = color(c.getString("messages.grabbed", MSG_GRABBED));
        MSG_RELEASED  = color(c.getString("messages.released", MSG_RELEASED));
        MSG_LAUNCHED  = color(c.getString("messages.launched", MSG_LAUNCHED));

        GUN_MATERIAL = Material.matchMaterial(c.getString("item.material", GUN_MATERIAL.name()));
        GUN_NAME     = color(c.getString("item.name", GUN_NAME));
        GUN_CMD      = c.getInt("item.custom_model_data", GUN_CMD);

        RANGE        = c.getDouble("mechanics.range", RANGE);
        HOLD_DIST    = c.getDouble("mechanics.hold_distance", HOLD_DIST);
        LAUNCH_POWER = c.getDouble("mechanics.launch_power", LAUNCH_POWER);
        SMOOTH       = c.getBoolean("mechanics.tick_smooth", SMOOTH);

        ALLOW_GRAB_PLAYERS = c.getBoolean("limits.allow_grab_players", ALLOW_GRAB_PLAYERS);
        BLACKLIST.clear();
        for (String s : c.getStringList("limits.blacklist")) {
            try { BLACKLIST.add(EntityType.valueOf(s)); } catch (Exception ignored) {}
        }
        if (BLACKLIST.isEmpty()) {
            BLACKLIST.addAll(Arrays.asList(EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN));
        }
        GRAB_COOLDOWN_MS = c.getLong("cooldown.grab_ms", GRAB_COOLDOWN_MS);
    }

    // ===== 커맨드 (/gg) =====
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && (args.length == 0 || !args[0].equalsIgnoreCase("reload"))) {
            sender.sendMessage(PREFIX + MSG_ONLY_PLAYER);
        }

        if (args.length == 0) {
            sender.sendMessage("§7/gg give <player> §f- 중력건 지급");
            sender.sendMessage("§7/gg reload §f- 설정 리로드");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("gravitygun.admin")) {
                sender.sendMessage(PREFIX + MSG_NO_PERM); return true;
            }
            reloadConfig();
            loadConfigIfPresent();
            sender.sendMessage(PREFIX + MSG_RELOAD);
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("gravitygun.admin")) {
                sender.sendMessage(PREFIX + MSG_NO_PERM); return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§c사용법: /gg give <player>");
                return true;
            }
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t == null) {
                sender.sendMessage("§c플레이어를 찾을 수 없습니다.");
                return true;
            }
            t.getInventory().addItem(makeGun());
            t.sendMessage(PREFIX + MSG_GOT_GUN);
            return true;
        }

        return false;
    }

    // ===== 아이템 판정/생성 =====
    private boolean isGun(ItemStack item) {
        if (item == null || item.getType() != (GUN_MATERIAL == null ? Material.BLAZE_ROD : GUN_MATERIAL)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        if (!GUN_NAME.equals(meta.getDisplayName())) return false;
        return meta.getPersistentDataContainer().has(gunKey, PersistentDataType.BYTE);
    }

    private ItemStack makeGun() {
        ItemStack it = new ItemStack(GUN_MATERIAL == null ? Material.BLAZE_ROD : GUN_MATERIAL);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(GUN_NAME);
        if (GUN_CMD > 0) meta.setCustomModelData(GUN_CMD);
        meta.getPersistentDataContainer().set(gunKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    // ===== 집기/놓기/던지기 로직 =====
    private boolean canGrab(LivingEntity le) {
        if (BLACKLIST.contains(le.getType())) return false;
        if (le instanceof Player p) {
            if (!ALLOW_GRAB_PLAYERS) return false;
            if (p.getGameMode() == GameMode.SPECTATOR) return false;
        }
        return true;
    }

    private LivingEntity getHeld(Player p) { return held.get(p.getUniqueId()); }

    private boolean tryGrab(Player p, LivingEntity t) {
        long now = System.currentTimeMillis();
        long last = lastGrab.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < GRAB_COOLDOWN_MS) return false;
        lastGrab.put(p.getUniqueId(), now);

        if (!canGrab(t)) return false;
        if (held.containsValue(t)) return false;

        if (getHeld(p) != null) release(p, false);

        held.put(p.getUniqueId(), t);
        originalGravity.put(t.getUniqueId(), t.hasGravity());
        originalCollidable.put(t.getUniqueId(), t.isCollidable());
        t.setGravity(false);
        t.setCollidable(false);
        t.setVelocity(new Vector(0, 0, 0));
        t.setFallDistance(0);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f);
        return true;
    }

    private void release(Player p, boolean silent) {
        LivingEntity t = held.remove(p.getUniqueId());
        if (t != null && t.isValid()) {
            Boolean g = originalGravity.remove(t.getUniqueId());
            Boolean c = originalCollidable.remove(t.getUniqueId());
            if (g != null) t.setGravity(g);
            if (c != null) t.setCollidable(c);
            t.setFallDistance(0f);
            if (!silent) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 0.9f);
        }
    }

    private void releaseAll(boolean silent) {
        for (UUID uid : new ArrayList<>(held.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) release(p, silent);
        }
    }

    private void launch(Player p) {
        LivingEntity t = getHeld(p);
        if (t == null) return;
        Vector dir = p.getEyeLocation().getDirection().normalize();
        release(p, true);
        t.setVelocity(dir.multiply(LAUNCH_POWER));
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.2f);
    }

    // ===== 시야 대상 선택 =====
    private LivingEntity rayPick(Player p) {
        var r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), RANGE,
                e -> e instanceof LivingEntity && e.getUniqueId() != p.getUniqueId());
        if (r != null && r.getHitEntity() instanceof LivingEntity le) return le;

        LivingEntity closest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : p.getNearbyEntities(RANGE, RANGE, RANGE)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.getUniqueId().equals(p.getUniqueId())) continue;
            double d = le.getLocation().distanceSquared(p.getLocation());
            if (d < best) { best = d; closest = le; }
        }
        return closest;
    }

    // ===== 틱 태스크 =====
    private void startTickTask() {
        if (tickTask != null) tickTask.cancel();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID puid : List.copyOf(held.keySet())) {
                Player p = Bukkit.getPlayer(puid);
                LivingEntity t = held.get(puid);
                if (p == null || !p.isOnline() || t == null || !t.isValid()) {
                    if (p != null) release(p, true);
                    continue;
                }

                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().normalize();
                Location targetLoc = eye.add(dir.multiply(HOLD_DIST));

                double yOffset = Math.min(1.5, Math.max(0.4, t.getHeight() * 0.5));
                targetLoc.add(0, -yOffset, 0);

                if (SMOOTH) {
                    Vector to = targetLoc.toVector().subtract(t.getLocation().toVector());
                    if (to.lengthSquared() > 9) {
                        t.teleport(targetLoc);
                        t.setVelocity(new Vector(0, 0, 0));
                    } else {
                        Vector vel = to.multiply(0.45);
                        if (vel.length() > 1.2) vel.normalize().multiply(1.2);
                        t.setVelocity(vel);
                    }
                } else {
                    t.teleport(targetLoc);
                    t.setVelocity(new Vector(0, 0, 0));
                }

                t.setFallDistance(0f);
                if (t.getLocation().getBlock().isSolid()) t.teleport(t.getLocation().add(0, 0.25, 0));
                if (t.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null && t.getHealth() < 0.1) t.setHealth(0.1);
            }
        }, 1L, 1L);
    }

    // ===== 이벤트 (한 파일에 모두 구현) =====
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPermission("gravitygun.use")) return;
        ItemStack hand = e.getItem();
        if (!isGun(hand)) return;

        Action a = e.getAction();
        e.setCancelled(true);

        if (p.isSneaking()) {
            if (getHeld(p) != null) {
                release(p, false);
                p.sendMessage(PREFIX + MSG_RELEASED);
            } else {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
            }
            return;
        }

        switch (a) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> {
                if (getHeld(p) != null) {
                    launch(p);
                    p.sendMessage(PREFIX + MSG_LAUNCHED);
                } else {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                }
            }
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                if (getHeld(p) != null) {
                    release(p, false);
                    p.sendMessage(PREFIX + MSG_RELEASED);
                } else {
                    LivingEntity t = rayPick(p);
                    if (t == null) { p.sendMessage(PREFIX + MSG_NO_TARGET); return; }
                    if (!canGrab(t)) { p.sendMessage(PREFIX + MSG_CANNOT); return; }
                    if (tryGrab(p, t)) {
                        p.sendMessage(PREFIX + MSG_GRABBED);
                    } else {
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                    }
                }
            }
            default -> {}
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e)  { release(e.getPlayer(), true); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { release(e.getEntity(), true); }
    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        // 특별한 GUI는 없지만, 혹여 손에 든 상태에서 이동/버리기 등으로
        // 이상 동작이 생기지 않도록 추가 제약을 두고 싶다면 여기서 처리 가능
    }

    // ===== 유틸 =====
    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
