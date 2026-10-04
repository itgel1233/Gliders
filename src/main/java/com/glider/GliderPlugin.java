package com.glider;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class GliderPlugin extends JavaPlugin implements Listener {

    // ---------------- Settings (all speeds are blocks per tick; 20 ticks = 1 s) ----------------
    private static final double BASE_SINK = 0.10;        // normal sinking speed while gliding (~2 blocks/s)
    private static final double MIN_SINK = 0.07;         // sinking speed when looking up (flaring)
    private static final double MAX_DIVE_SINK = 0.50;    // sinking speed when looking steeply down
    private static final double BASE_SPEED = 0.45;       // forward speed (~9 blocks/s)
    private static final double MAX_DIVE_SPEED = 0.75;   // forward speed when diving (~15 blocks/s)
    private static final double MIN_FLARE_SPEED = 0.25;  // forward speed when looking up
    private static final double START_FALL_DISTANCE = 2.0; // blocks you must fall before the glider opens
    private static final double SMOOTHING = 0.30;        // how quickly speed changes (0..1)
    private static final int DAMAGE_INTERVAL_TICKS = 20; // lose durability every this many ticks of gliding
    private static final int DAMAGE_AMOUNT = 1;          // durability lost each interval (crossbow has 465)
    // -----------------------------------------------------------------------------------------

    private static final class State {
        Location last;
        boolean active;
        int airTicks;
        Vector vel = new Vector();
        long lastActiveTick = -1000;
        int damageTimer = 0;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private NamespacedKey gliderKey;
    private NamespacedKey recipeKey;
    private long tickCount = 0;

    // ============================ Lifecycle ============================

    @Override
    public void onEnable() {
        gliderKey = new NamespacedKey(this, "glider");
        recipeKey = new NamespacedKey(this, "glider_recipe");

        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createGlider());
        recipe.shape("FFF", "STS", " S ");
        recipe.setIngredient('F', Material.FEATHER);
        recipe.setIngredient('S', Material.STICK);
        recipe.setIngredient('T', Material.STRING);
        recipe.setCategory(CraftingBookCategory.EQUIPMENT);
        Bukkit.addRecipe(recipe);

        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(recipeKey);

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
        states.clear();
    }

    // ============================ Item ============================

    private ItemStack createGlider() {
        ItemStack s = new ItemStack(Material.CROSSBOW);
        CrossbowMeta m = (CrossbowMeta) s.getItemMeta();
        m.displayName(Component.text("Glider", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Hold it while falling to glide.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Look down to dive, up to slow down,", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("sneak to fold it away.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        // A charged crossbow makes the player hold their arms up; the model is swapped to a wing.
        m.setChargedProjectiles(List.of(new ItemStack(Material.ARROW)));
        m.setItemModel(NamespacedKey.minecraft("elytra"));
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        m.getPersistentDataContainer().set(gliderKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    private boolean isGlider(ItemStack it) {
        return it != null
                && it.getType() == Material.CROSSBOW
                && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(gliderKey, PersistentDataType.BYTE);
    }

    private boolean holdsGlider(Player p) {
        return isGlider(p.getInventory().getItemInMainHand()) || isGlider(p.getInventory().getItemInOffHand());
    }

    /** Makes sure a held glider always stays "charged" so the raised-arm pose never disappears. */
    private void ensureCharged(Player p) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack it = p.getInventory().getItem(slot);
            if (!isGlider(it)) continue;
            CrossbowMeta cm = (CrossbowMeta) it.getItemMeta();
            if (!cm.hasChargedProjectiles()) {
                cm.setChargedProjectiles(List.of(new ItemStack(Material.ARROW)));
                it.setItemMeta(cm);
                p.getInventory().setItem(slot, it);
            }
        }
    }

    // ============================ Gliding ============================

    private boolean blocked(Player p) {
        return p.isDead()
                || p.getGameMode() == GameMode.SPECTATOR
                || p.isFlying()
                || p.isGliding()
                || p.isInsideVehicle()
                || p.isSneaking()
                || p.isSwimming()
                || p.isInWater()
                || p.isInLava()
                || p.isClimbing();
    }

    private void tick() {
        tickCount++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());

            if (!holdsGlider(p)) {
                if (s != null && !s.active) states.remove(p.getUniqueId());
                else if (s != null) s.active = false;
                continue;
            }
            if (s == null) {
                s = new State();
                states.put(p.getUniqueId(), s);
            }
            if (tickCount % 10 == 0) ensureCharged(p);

            Location now = p.getLocation();
            double dx = 0, dy = 0, dz = 0;
            if (s.last != null && s.last.getWorld() == now.getWorld()) {
                dx = now.getX() - s.last.getX();
                dy = now.getY() - s.last.getY();
                dz = now.getZ() - s.last.getZ();
            }
            s.last = now;

            if (blocked(p) || p.isOnGround()) {
                s.active = false;
                s.airTicks = 0;
                continue;
            }
            s.airTicks++;

            if (!s.active) {
                if (s.airTicks >= 5 && dy < -0.05 && p.getFallDistance() >= START_FALL_DISTANCE) {
                    s.active = true;
                    s.damageTimer = 0;
                    s.vel = new Vector(dx, dy, dz);
                    p.getWorld().playSound(now, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.8f, 1.0f);
                } else {
                    continue;
                }
            }

            // --- active gliding ---
            double pitch = now.getPitch();
            double sink;
            double speed;
            if (pitch >= 0) {
                double d = Math.min(pitch, 60.0) / 60.0;
                sink = BASE_SINK + (MAX_DIVE_SINK - BASE_SINK) * d;
                speed = BASE_SPEED + (MAX_DIVE_SPEED - BASE_SPEED) * d;
            } else {
                double u = Math.min(-pitch, 45.0) / 45.0;
                sink = BASE_SINK - (BASE_SINK - MIN_SINK) * u;
                speed = BASE_SPEED - (BASE_SPEED - MIN_FLARE_SPEED) * u;
            }

            double yawRad = Math.toRadians(now.getYaw());
            double tx = -Math.sin(yawRad) * speed;
            double tz = Math.cos(yawRad) * speed;
            double ty = -sink;

            s.vel.setX(s.vel.getX() + (tx - s.vel.getX()) * SMOOTHING);
            s.vel.setY(s.vel.getY() + (ty - s.vel.getY()) * SMOOTHING);
            s.vel.setZ(s.vel.getZ() + (tz - s.vel.getZ()) * SMOOTHING);

            p.setVelocity(s.vel.clone());
            p.setFallDistance(0f);
            s.lastActiveTick = tickCount;

            s.damageTimer++;
            if (s.damageTimer >= DAMAGE_INTERVAL_TICKS) {
                s.damageTimer = 0;
                if (damageGlider(p)) {
                    s.active = false;
                    continue;
                }
            }

            if (tickCount % 3 == 0) {
                p.getWorld().spawnParticle(Particle.CLOUD, now.clone().add(0, 0.2, 0), 1, 0.2, 0.1, 0.2, 0.0);
            }
            if (tickCount % 15 == 0) {
                p.getWorld().playSound(now, Sound.ITEM_ELYTRA_FLYING, 0.15f, 1.0f);
            }
        }
    }

    /** Wears the held glider down. Returns true if it broke. */
    private boolean damageGlider(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE) return false;
        EquipmentSlot slot = isGlider(p.getInventory().getItemInMainHand())
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        ItemStack it = p.getInventory().getItem(slot);
        if (!isGlider(it)) return false;

        CrossbowMeta cm = (CrossbowMeta) it.getItemMeta();
        if (cm.isUnbreakable()) return false;
        int unbreaking = cm.getEnchantLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return false;

        Damageable dm = (Damageable) cm;
        int newDamage = dm.getDamage() + DAMAGE_AMOUNT;
        if (newDamage >= Material.CROSSBOW.getMaxDurability()) {
            p.getInventory().setItem(slot, null);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.05);
            p.sendActionBar(Component.text("Your glider broke!", NamedTextColor.RED));
            return true;
        }
        dm.setDamage(newDamage);
        it.setItemMeta(cm);
        p.getInventory().setItem(slot, it);
        return false;
    }

    // ============================ Events ============================

    /** No fall damage while gliding or in the moment right after landing. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        State s = states.get(p.getUniqueId());
        if (s != null && (s.active || tickCount - s.lastActiveTick <= 10)) e.setCancelled(true);
    }

    /** The glider is a crossbow underneath, so make sure it can never shoot. */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isGlider(e.getItem())) return;
        e.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (isGlider(e.getBow())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(recipeKey);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    // ============================ Command ============================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player pl) {
            target = pl;
        } else {
            sender.sendMessage(Component.text("Usage: /glider <player>", NamedTextColor.RED));
            return true;
        }
        Map<Integer, ItemStack> left = target.getInventory().addItem(createGlider());
        for (ItemStack rest : left.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        sender.sendMessage(Component.text("Gave a glider to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }
}
