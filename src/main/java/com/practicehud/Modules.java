package com.practicehud;

import com.practicehud.Hud.Row;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registry of every module plus the code that implements them. */
public final class Modules {
    public static final String CAT_CRYSTAL = "Crystal & Respawn";
    public static final String CAT_COMBAT = "Combat";
    public static final String CAT_MACE = "Mace & Wind";
    public static final String CAT_CART = "Minecart & Redstone";
    public static final String CAT_UTIL = "Spear & Utility";
    public static final String CAT_UHC = "UHC / Nethpot";
    public static final String CAT_VISUAL = "Visuals";
    public static final String CAT_AMBIENCE = "Ambience";
    public static final String CAT_DONUT = "DonutSMP";

    public static final List<String> CATEGORIES = List.of(CAT_CRYSTAL, CAT_COMBAT, CAT_MACE, CAT_CART, CAT_UTIL, CAT_UHC, CAT_VISUAL, CAT_DONUT, CAT_AMBIENCE);
    public static final List<Module> ALL = new ArrayList<>();

    // Smoothed minecart velocities (blocks per tick), measured from client positions.
    private static final Map<Integer, Vec3d> LAST_CART_POS = new HashMap<>();
    private static final Map<Integer, Vec3d> CART_VEL = new HashMap<>();
    private static final java.util.Random AMBIENCE_RANDOM = new java.util.Random();
    private static final int[] FPS_HISTORY = new int[36];
    private static int fpsHistoryIndex;
    private static int lastFpsSampleTick;

    private Modules() {
    }

    private static void add(String id, String name, String cat, String desc, Module.Renderer r) {
        ALL.add(new Module(id, name, cat, desc, r));
    }

    /** Registers a module that Competitive Safe Mode holds off. */
    private static void addRestricted(String id, String name, String cat, String desc, Module.Renderer r) {
        ALL.add(new Module(id, name, cat, desc, r, true));
    }

    public static Module byId(String id) {
        for (Module m : ALL) if (m.id.equals(id)) return m;
        return null;
    }

    public static boolean enabled(String id) {
        Module m = byId(id);
        return m != null && m.isEnabled();
    }

    static {
        // ---- Crystal ----
        add("crystal_assist", "Crystal Assist", CAT_CRYSTAL,
                "Shows crystal supplies and the block you are targeting.", Modules::crystalAssist);
        add("crystal_combo", "Crystal Combo", CAT_CRYSTAL,
                "Displays crystal and obsidian counts for setup awareness.", Modules::crystalCombo);
        add("totem_manager", "Totem Manager", CAT_CRYSTAL,
                "Tracks totem count and offhand status; switching remains manual.", Modules::totemStatus);
        add("anchor_assist", "Anchor Assist", CAT_CRYSTAL,
                "Displays respawn-anchor charge information when targeted.", Modules::anchorMeter);
        add("anchor_safety", "Anchor Safety", CAT_CRYSTAL,
                "Shows a caution status when viewing a charged anchor.", Modules::anchorSafety);
        add("elytra_assist", "Elytra Assist", CAT_CRYSTAL,
                "Displays elytra equipped state, durability and glide speed.", Modules::elytraHud);
        add("obsidian_assist", "Obsidian Assist", CAT_CRYSTAL,
                "Tracks obsidian and end-crystal supplies.", Modules::crystalCombo);
        add("pearl_assist", "Pearl Assist", CAT_CRYSTAL,
                "Shows ender-pearl count and predicted landing point.", Modules::pearlAssist);

        add("blast_calc", "Blast Damage Calculator", CAT_CRYSTAL,
                "Live damage you would take from nearby end crystals and charged respawn anchors (armour and difficulty included, enchantments not). Marks each one on screen: red = lethal, yellow = heavy, green = safe.",
                Combat::renderBlast);

        // ---- Combat ----
        add("totem_pops", "Totem Pop Tracker", CAT_COMBAT,
                "Counts totems popped by nearby players, with a chat alert for each pop.", Combat::renderPops);
        add("combat_timer", "Combat Tag Timer", CAT_COMBAT,
                "15-second countdown after you hit or are hit by a player.", Combat::renderTag);
        add("reticle", "Custom Crosshair", CAT_VISUAL,
                "Replaces the crosshair (shape/size/colour in settings). Changes colour over a target in reach.", null);
        add("target_ring", "Target Tracking Ring", CAT_COMBAT,
                "A static ring at screen centre for aim practice.", Modules::targetRing);
        add("combat_assist", "Combat Assist", CAT_COMBAT,
                "Displays information about the entity under your crosshair.", Modules::entityInfo);
        add("sprint_assist", "Sprint Assist", CAT_COMBAT,
                "Displays sprint state and movement speed; does not automate inputs.", Modules::sprintIndicator);
        add("shield_assist", "Shield Assist", CAT_COMBAT,
                "Displays shield durability, blocking state and cooldown.", Modules::shieldStatus);
        add("distance", "Combat Distance Counter", CAT_COMBAT,
                "Distance in blocks to the mob or player under your crosshair (up to 64).", Modules::distanceCounter);
        add("sprint", "Sprint Indicator", CAT_COMBAT,
                "Shows sprint / sneak state and horizontal speed.", Modules::sprintIndicator);
        add("guard_timing", "Guard Timing Indicator", CAT_COMBAT,
                "Weapon attack cooldown and shield-disabled state.", Modules::guardTiming);
        add("shield_status", "Shield Status", CAT_COMBAT,
                "Shield durability, blocking state and cooldown.", Modules::shieldStatus);
        add("ability_cooldown", "Ability Cooldown Bar", CAT_COMBAT,
                "Cooldown progress of the item in your hand (pearls, wind charges, shields...).", Modules::abilityCooldown);
        add("netherite_hud", "Netherite Combat HUD", CAT_COMBAT,
                "Health, potion effects and armour durability in one panel.", Modules::combatHud);
        add("entity_info", "Entity Information", CAT_COMBAT,
                "Name, health bar and effects of the living entity under your crosshair.", Modules::entityInfo);

        // ---- Mace & Wind ----
        add("crit_bar", "Critical Hit Timing Bar", CAT_MACE,
                "Shows whether a hit would crit right now, plus fall distance and attack charge.", Modules::critBar);
        add("mace_height", "Mace Height Calculator", CAT_MACE,
                "Fall distance, the 1.5-block smash minimum and estimated bonus damage.", Modules::maceHeight);
        add("wind_trainer", "Wind Burst Combo Trainer", CAT_MACE,
                "Step prompts for wind charge -> mace combos.", Modules::windTrainer);
        add("jump_gauge", "Jump Velocity Gauge", CAT_MACE,
                "Vertical speed in blocks/second.", Modules::jumpGauge);
        add("elytra", "Elytra HUD", CAT_MACE,
                "Pitch, speed and elytra durability while gliding.", Modules::elytraHud);
        add("mace_assist", "Mace Assist", CAT_MACE,
                "Shows mace readiness and fall-distance information.", Modules::maceHeight);
        add("mace_guide", "Mace Guide", CAT_MACE,
                "Displays fall distance and estimated mace bonus damage.", Modules::maceHeight);
        add("wind_assist", "Wind Assist", CAT_MACE,
                "Shows step-by-step wind-charge and mace practice prompts.", Modules::windTrainer);
        add("pearl_boost_info", "Pearl Boost Guide", CAT_MACE,
                "Displays pearl and wind-charge inventory counts.", Modules::pearlAssist);
        add("wind_boost_info", "Wind Boost Guide", CAT_MACE,
                "Displays wind-charge count and vertical movement speed.", Modules::windGuide);

        // ---- Minecart & Redstone ----
        add("cart_speed", "Minecart Speedometer", CAT_CART,
                "Speed and heading of the three nearest minecarts.", Modules::cartSpeedometer);
        add("rail_counter", "Inventory Rail Counter", CAT_CART,
                "Rails, minecarts and redstone in your inventory.", Modules::railCounter);
        add("cart_assist", "Cart Assist", CAT_CART,
                "Shows nearby minecart distance and speed.", Modules::cartSpeedometer);
        add("cart_safety", "Cart Safety", CAT_CART,
                "Displays TNT minecart and protective-block supplies.", Modules::cartSafety);
        add("cart_manager", "Cart Manager", CAT_CART,
                "Tracks minecart, TNT and rail supplies.", Modules::railCounter);

        // ---- Utility ----
        add("throw_calc", "Throw Landing Calculator", CAT_UTIL,
                "Predicts where an ender pearl, snowball, egg or splash potion will land from your aim.", Modules::throwCalc);
        add("reach", "Interaction Distance", CAT_UTIL,
                "Distance to the block or entity you are targeting.", Modules::reachReadout);
        add("spear_assist", "Spear Assist", CAT_UTIL,
                "Displays held spear name and durability.", Modules::heldItemHud);
        add("trajectory", "Projectile & Pearl Predictor", CAT_UTIL,
                "Draws the landing path of your pearls, potions and arrows, and marks enemy pearls in flight with distance and time.",
                Combat::renderTrajectory);
        addRestricted("middle_click", "Middle-Click Utilities", CAT_UTIL,
                "Middle-click throws a pearl, sneak + middle-click uses an XP bottle, middle-click while gliding uses a firework. Held off by Safe Mode.", null);
        addRestricted("fast_xp", "Fast XP", CAT_UTIL,
                "Throws XP bottles at full speed: holding right-click no longer waits between throws. Held off by Safe Mode.", null);
        add("regear_helper", "Hotbar Re-gear Helper", CAT_UTIL,
                "Outlines hotbar slots that no longer match your saved layout (save it in Settings).", Combat::renderRegear);

        // ---- Visuals ----
        add("low_fire", "Low Fire", CAT_VISUAL,
                "Lowers the first-person fire overlay, and hides it while you have Fire Resistance.", null);
        add("toggle_sprint", "Toggle Sprint", CAT_VISUAL,
                "Sprint stays on until you stop moving, using the game's own toggle option.", Combat::renderToggleStatus);
        add("toggle_sneak", "Toggle Sneak", CAT_VISUAL,
                "Sneak stays on until you press it again, using the game's own toggle option.", Combat::renderToggleStatus);
        addRestricted("hitbox_lasers", "Hitboxes & Eye Lasers", CAT_VISUAL,
                "Thin hitbox outlines on players (green inside 3 blocks) and a red line along where each opponent is looking. Held off by Safe Mode.",
                Combat::renderHitboxes);
        add("no_fire_overlay", "No Fire Overlay", CAT_VISUAL,
                "Hides the first-person fire overlay for a clearer, less obstructed view.", null);
        add("no_nausea_overlay", "No Nausea Overlay", CAT_VISUAL,
                "Hides the nausea screen distortion overlay.", null);
        add("no_portal_overlay", "No Portal Overlay", CAT_VISUAL,
                "Hides the purple portal screen overlay.", null);
        add("no_vignette", "No Vignette", CAT_VISUAL,
                "Hides the dark edge vignette around the screen.", null);
        add("swing_speed", "Swing Speed", CAT_VISUAL,
                "Speeds up your local first-person arm swing animation. Does not change attack speed or damage.", null);
        add("fps_counter", "FPS Counter", CAT_VISUAL, "Displays current client FPS.", Modules::fpsCounter);
        add("coordinates", "Coordinates HUD", CAT_VISUAL, "Displays current X, Y and Z coordinates.", Modules::coordinatesHud);
        add("armor_status", "Armor Status", CAT_VISUAL, "Displays equipped armour durability.", Modules::armorStatusHud);
        add("potion_hud", "Potion HUD", CAT_VISUAL, "Displays active potion effects and duration.", Modules::potionStatusHud);
        add("keystrokes", "Keystrokes", CAT_VISUAL, "Shows WASD, jump, sneak and sprint key states.", Modules::keystrokesHud);
        add("held_item", "Held Item HUD", CAT_VISUAL, "Shows the held item name, count and durability.", Modules::heldItemHud);
        add("world_time", "World Time", CAT_VISUAL, "Displays the current local world time.", Modules::worldTimeHud);
        add("fps_graph", "FPS Graph", CAT_VISUAL, "Shows a rolling graph of recent client frame rates.", Modules::fpsGraph);
        add("session_stats", "Session Stats", CAT_VISUAL, "Shows session duration, movement distance and current FPS.", Modules::sessionStats);
        add("target_hud", "Target HUD", CAT_VISUAL, "Shows the name and health of the entity under your crosshair.", Modules::entityInfo);
        add("entity_display", "Entity Display", CAT_VISUAL, "Shows name, health and effects for your current target.", Modules::entityInfo);
        add("storage_display", "Storage Display", CAT_VISUAL, "Identifies the storage block you are looking at.", Modules::storageDisplay);
        add("projectile_guide", "Projectile Guide", CAT_VISUAL, "Shows the predicted path and landing point of held projectiles.", Modules::throwCalc);
        add("target_highlight", "Target Highlight", CAT_VISUAL, "Adds a target ring and target information.", Modules::targetRing);
        add("animation_settings", "Animation Settings", CAT_VISUAL, "Uses the XMCore swing-speed setting for local hand animation.", null);
        add("crosshair_settings", "Crosshair Settings", CAT_VISUAL, "Uses the XMCore crosshair customisation settings.", null);
        add("name_display", "Name Display", CAT_VISUAL, "Displays the name and health of the entity under your crosshair.", Modules::entityInfo);

        // ---- UHC / Nethpot ----
        add("potion_assist", "Potion Assist", CAT_UHC,
                "Displays active potion effects and durations.", Modules::potionStatusHud);
        add("potion_manager", "Potion Manager", CAT_UHC,
                "Counts useful potions in your inventory.", Modules::potionInventory);
        add("sprint_uhc", "Sprint Assist", CAT_UHC,
                "Displays sprint state and movement speed.", Modules::sprintIndicator);
        add("nethpot_settings", "Nethpot Settings", CAT_UHC,
                "Shows health, armour and active effects for Nethpot awareness.", Modules::combatHud);

        // ---- DonutSMP: each tool has its own toggle ----
        add("donut_balance", "Balance HUD", CAT_DONUT,
                "Shows your tracked DonutSMP virtual balance and starting balance.", DonutSmp::renderBalance);
        add("donut_earnings", "Live Tracker", CAT_DONUT,
                "Automatically tracks incoming DonutSMP payments with a customizable HUD.", DonutSmp::renderEarnings);
        add("donut_top_earners", "Top Earners", CAT_DONUT,
                "Shows the top five players by tracked virtual payments.", DonutSmp::renderTopEarners);
        add("donut_goal_alert", "Earnings Goal Alert", CAT_DONUT,
                "Notifies you when your tracked virtual earnings reach your goal.", null);
        add("donut_auction", "Auction Tracker", CAT_DONUT,
                "Pay-to-bid auction: set a minimum, and every payment sent to you shows up live with the payer's name and amount.", DonutSmp::renderAuction);
        add("donut_stream_info", "Stream Info HUD", CAT_DONUT,
                "Shows your player name and DonutSMP stream status.", DonutSmp::renderStreamInfo);

        // ---- Ambience ----
        add("ambient_particles", "Ambient Particles", CAT_AMBIENCE,
                "Adds subtle floating light particles around you. Client-side visual effect.", null);
        add("ambient_audio", "Ambient Sounds", CAT_AMBIENCE,
                "Occasionally plays a quiet vanilla ambient cave sound locally.", null);
    }

    // =====================================================================
    // Frame / tick entry points
    // =====================================================================

    public static void renderAll(DrawContext ctx, MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.options.hudHidden) return;
        // While the HUD editor is open it draws the HUD itself (crisply, above the menu blur).
        if (mc.currentScreen instanceof HudEditorScreen && !HudEditorScreen.drawing) return;
        Hud.begin();
        Combat.sampleInput(mc);
        for (Module m : ALL) {
            if (m.isEnabled()) m.render(ctx, mc, p);
        }
        if (Safe.active()) {
            Hud.panel(ctx, "Safe Mode", Hud.GREEN,
                    Row.of(Safe.fromServer() ? "Server: competitive" : "Switched on", Hud.GREEN),
                    Row.of("Restricted modules off", Hud.GRAY));
        }
    }

    public static void tick(MinecraftClient mc) {
        Safe.update(mc);
        Combat.tick(mc);
        if (mc.world == null || mc.player == null) {
            LAST_CART_POS.clear();
            CART_VEL.clear();
            return;
        }
        // Keep a lightweight rolling FPS history for the optional graph.
        if (mc.player.age - lastFpsSampleTick >= 10) {
            FPS_HISTORY[fpsHistoryIndex] = mc.getCurrentFps();
            fpsHistoryIndex = (fpsHistoryIndex + 1) % FPS_HISTORY.length;
            lastFpsSampleTick = mc.player.age;
        }
        // Optional client-only ambience. These effects never change server world state.
        if (enabled("ambient_particles") && mc.player.age % 4 == 0) {
            double x = mc.player.getX() + (AMBIENCE_RANDOM.nextDouble() - 0.5) * 12.0;
            double y = mc.player.getY() + 1.0 + AMBIENCE_RANDOM.nextDouble() * 3.0;
            double z = mc.player.getZ() + (AMBIENCE_RANDOM.nextDouble() - 0.5) * 12.0;
            mc.world.addParticleClient(ParticleTypes.END_ROD, false, true,
                    x, y, z, 0.0, 0.008, 0.0);
        }
        if (enabled("ambient_audio") && mc.player.age % 900 == 0) {
            float pitch = 0.88f + AMBIENCE_RANDOM.nextFloat() * 0.24f;
            mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    SoundEvents.AMBIENT_CAVE, SoundCategory.AMBIENT, 0.18f, pitch,
                    AMBIENCE_RANDOM.nextLong());
        }

        if (!enabled("cart_speed")) return;
        Map<Integer, Vec3d> now = new HashMap<>();
        for (AbstractMinecartEntity c : mc.world.getEntitiesByClass(AbstractMinecartEntity.class,
                mc.player.getBoundingBox().expand(48), e -> true)) {
            Vec3d pos = new Vec3d(c.getX(), c.getY(), c.getZ());
            now.put(c.getId(), pos);
            Vec3d prev = LAST_CART_POS.get(c.getId());
            if (prev != null) {
                Vec3d v = pos.subtract(prev);
                Vec3d old = CART_VEL.getOrDefault(c.getId(), v);
                CART_VEL.put(c.getId(), old.multiply(0.6).add(v.multiply(0.4)));
            }
        }
        LAST_CART_POS.clear();
        LAST_CART_POS.putAll(now);
        CART_VEL.keySet().retainAll(now.keySet());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static int count(ClientPlayerEntity p, Item... items) {
        int n = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            for (Item it : items) {
                if (s.isOf(it)) {
                    n += s.getCount();
                    break;
                }
            }
        }
        return n;
    }

    private static float durabilityFrac(ItemStack s) {
        if (s.isEmpty() || s.getMaxDamage() <= 0) return 1f;
        return (s.getMaxDamage() - s.getDamage()) / (float) s.getMaxDamage();
    }

    private static int fracColor(float f) {
        return f > 0.5f ? Hud.GREEN : f > 0.2f ? Hud.YELLOW : Hud.RED;
    }

    private static double hSpeed(ClientPlayerEntity p) {
        Vec3d v = p.getVelocity();
        return Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;
    }

    /** Nearest living entity along the camera ray, ignoring walls. Returns null if none. */
    private static LivingEntity lookedAt(MinecraftClient mc, double range) {
        if (mc.getCameraEntity() == null || mc.world == null) return null;
        var cam = mc.getCameraEntity();
        Vec3d start = cam.getCameraPosVec(1f);
        Vec3d dir = cam.getRotationVec(1f);
        Vec3d end = start.add(dir.multiply(range));
        LivingEntity best = null;
        double bestD = range * range;
        for (LivingEntity t : mc.world.getEntitiesByClass(LivingEntity.class,
                cam.getBoundingBox().stretch(dir.multiply(range)).expand(1.0),
                e -> e != cam && e.isAlive() && !e.isSpectator())) {
            Optional<Vec3d> hit = t.getBoundingBox().expand(t.getTargetingMargin()).raycast(start, end);
            if (hit.isPresent()) {
                double d = start.squaredDistanceTo(hit.get());
                // A target can be partly exposed around a block edge. Check several
                // points on its bounds instead of rejecting it from one blocked point.
                if (!hasVisiblePoint(mc, cam, t, start, hit.get())) continue;
                if (d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
        }
        return best;
    }

    private static boolean hasVisiblePoint(MinecraftClient mc, net.minecraft.entity.Entity camera,
                                           LivingEntity target, Vec3d start, Vec3d firstHit) {
        var box = target.getBoundingBox();
        double midX = (box.minX + box.maxX) * 0.5;
        double midY = (box.minY + box.maxY) * 0.5;
        double midZ = (box.minZ + box.maxZ) * 0.5;
        Vec3d[] samples = {
                firstHit,
                new Vec3d(midX, box.minY + 0.08, midZ),
                new Vec3d(midX, box.maxY - 0.08, midZ),
                new Vec3d(box.minX + 0.04, midY, midZ),
                new Vec3d(box.maxX - 0.04, midY, midZ),
                new Vec3d(midX, midY, box.minZ + 0.04),
                new Vec3d(midX, midY, box.maxZ - 0.04)
        };
        for (Vec3d sample : samples) {
            BlockHitResult obstruction = mc.world.raycast(new RaycastContext(start, sample,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, camera));
            if (obstruction.getType() == HitResult.Type.MISS
                    || start.squaredDistanceTo(obstruction.getPos()) + 1.0E-4
                    >= start.squaredDistanceTo(sample)) return true;
        }
        return false;
    }

    private static String effectName(StatusEffectInstance e) {
        String n = e.getEffectType().value().getName().getString();
        return e.getAmplifier() > 0 ? n + " " + (e.getAmplifier() + 1) : n;
    }

    // =====================================================================
    // Visuals
    // =====================================================================

    private static void fpsCounter(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Hud.panel(ctx, "Performance", Hud.AQUA, Row.of("FPS: " + mc.getCurrentFps(), Hud.GREEN));
    }

    private static void fpsGraph(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        // Drawn through the shared HUD layout so it flows with (and never overlaps) the other panels.
        int[] ordered = new int[FPS_HISTORY.length];
        for (int i = 0; i < ordered.length; i++) {
            ordered[i] = FPS_HISTORY[(fpsHistoryIndex + i) % FPS_HISTORY.length];
        }
        Hud.graph(ctx, "FPS History", Config.get().accentColor, ordered, mc.getCurrentFps() + " FPS", Hud.GREEN);
    }

    private static void sessionStats(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        int seconds = Math.max(0, p.age / 20);
        double speed = Math.hypot(p.getVelocity().x, p.getVelocity().z) * 20.0;
        Hud.panel(ctx, "Session Stats", Hud.AQUA,
                Row.of("Time: " + Hud.time(seconds), Hud.WHITE),
                Row.of("FPS: " + mc.getCurrentFps(), Hud.GREEN),
                Row.of(String.format("Speed: %.2f blocks/s", speed), Hud.GRAY));
    }

    private static void coordinatesHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Hud.panel(ctx, "Coordinates", Hud.AQUA,
                Row.of(String.format("X: %.1f", p.getX()), Hud.WHITE),
                Row.of(String.format("Y: %.1f", p.getY()), Hud.WHITE),
                Row.of(String.format("Z: %.1f", p.getZ()), Hud.WHITE));
    }

    /** Red -> yellow -> green blend for a 0..1 fraction. */
    private static int gradient(float f) {
        f = Math.max(0f, Math.min(1f, f));
        return f < 0.5f ? blend(Hud.RED, Hud.YELLOW, f * 2f) : blend(Hud.YELLOW, Hud.GREEN, (f - 0.5f) * 2f);
    }

    private static int blend(int a, int b, float t) {
        int out = 0xFF000000;
        for (int s = 0; s <= 16; s += 8) {
            int ca = (a >> s) & 255, cb = (b >> s) & 255;
            out |= (Math.round(ca + (cb - ca) * t) & 255) << s;
        }
        return out;
    }

    private static void armorStatusHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        String[] names = {"Helmet", "Chest", "Leggings", "Boots"};
        boolean pulse = (System.currentTimeMillis() / 300L) % 2L == 0L;
        List<Row> rows = new ArrayList<>();
        long missing = 0;
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = p.getEquippedStack(slots[i]);
            if (stack.isEmpty()) {
                rows.add(Row.of(names[i] + ": empty", Hud.GRAY));
            } else if (stack.getMaxDamage() <= 0) {
                rows.add(Row.of(names[i] + ": unbreakable", Hud.GRAY));
            } else {
                int max = stack.getMaxDamage();
                int left = max - stack.getDamage();
                float f = left / (float) max;
                missing += stack.getDamage();
                // Pulses between red and white below 15% durability.
                int color = f < 0.15f ? (pulse ? Hud.RED : Hud.WHITE) : gradient(f);
                rows.add(Row.bar(names[i] + ": " + Math.round(f * 100) + "% (" + left + "/" + max + ")", f, color));
            }
        }
        if (missing > 0) {
            // Mending repairs 2 durability per XP point; an XP bottle gives 3-11 (about 7).
            long bottles = (long) Math.ceil(Math.ceil(missing / 2.0) / 7.0);
            rows.add(Row.of("Mend set: ~" + bottles + " XP bottles", Hud.GRAY));
        }
        Hud.panel(ctx, "Armor Status", Hud.AQUA, rows.toArray(new Row[0]));
    }

    private static void potionStatusHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        // Shortest time left first; infinite and beacon/conduit (ambient) effects are hidden as clutter.
        List<StatusEffectInstance> effects = new ArrayList<>();
        for (StatusEffectInstance effect : p.getStatusEffects()) {
            if (effect.isInfinite() || effect.isAmbient()) continue;
            effects.add(effect);
        }
        effects.sort(Comparator.comparingInt(StatusEffectInstance::getDuration));
        boolean blink = (System.currentTimeMillis() / 250L) % 2L == 0L;
        List<Row> rows = new ArrayList<>();
        for (StatusEffectInstance effect : effects) {
            int ticks = effect.getDuration();
            int color = ticks < 200 ? (blink ? Hud.RED : Hud.WHITE) : ticks < 600 ? Hud.YELLOW : Hud.WHITE;
            rows.add(Row.of(effectName(effect) + ": " + Hud.time(ticks), color));
        }
        if (rows.isEmpty()) rows.add(Row.of("No timed effects", Hud.GRAY));
        Hud.panel(ctx, "Potion Effects", Hud.AQUA, rows.toArray(new Row[0]));
    }

    private static void keystrokesHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of((mc.options.forwardKey.isPressed() ? "[W]" : " W ") + " " +
                (mc.options.leftKey.isPressed() ? "[A]" : " A ") + " " +
                (mc.options.backKey.isPressed() ? "[S]" : " S ") + " " +
                (mc.options.rightKey.isPressed() ? "[D]" : " D "), Hud.WHITE));
        rows.add(Row.of("Jump " + (mc.options.jumpKey.isPressed() ? "ON" : "OFF") +
                "  Sneak " + (mc.options.sneakKey.isPressed() ? "ON" : "OFF") +
                "  Sprint " + (p.isSprinting() ? "ON" : "OFF"), Hud.WHITE));
        rows.add(Row.of("CPS: " + Combat.lmbCps() + " | " + Combat.rmbCps() + "   Peak: " + Combat.peakCps(),
                Combat.lmbCps() >= 10 ? Hud.GREEN : Hud.WHITE));
        long gap = Combat.recentTapGapMs();
        if (gap >= 0) {
            // Bar fills for tight taps; green under ~150 ms.
            rows.add(Row.bar(Combat.tapKey() + "-tap: " + gap + " ms", 1f - Math.min(1f, gap / 250f), gap <= 150 ? Hud.GREEN : Hud.YELLOW));
        } else {
            rows.add(Row.of("W/S-tap: --", Hud.GRAY));
        }
        int ping = Combat.ping(mc);
        double tps = Combat.tps();
        rows.add(Row.of("Ping: " + (ping < 0 ? "--" : ping + " ms"),
                ping < 0 ? Hud.GRAY : ping < 60 ? Hud.GREEN : ping < 150 ? Hud.YELLOW : Hud.RED));
        rows.add(Row.of("TPS: " + (tps < 0 ? "--" : String.format("%.1f", tps)),
                tps < 0 ? Hud.GRAY : tps >= 19.0 ? Hud.GREEN : tps >= 15.0 ? Hud.YELLOW : Hud.RED));
        Hud.panel(ctx, "Keystrokes", Hud.AQUA, rows.toArray(new Row[0]));
    }

    private static void heldItemHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        ItemStack stack = p.getMainHandStack();
        if (stack.isEmpty()) {
            Hud.panel(ctx, "Held Item", Hud.AQUA, Row.of("Empty hand", Hud.GRAY));
            return;
        }
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(stack.getName().getString() + " x" + stack.getCount(), Hud.WHITE));
        if (stack.isDamageable()) {
            float f = durabilityFrac(stack);
            rows.add(Row.bar("Durability: " + (stack.getMaxDamage() - stack.getDamage()) + "/" + stack.getMaxDamage(), f, fracColor(f)));
        }
        Hud.panel(ctx, "Held Item", Hud.AQUA, rows.toArray(new Row[0]));
    }

    private static void worldTimeHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        long dayTime = mc.world.getTimeOfDay() % 24000L;
        int hours = (int) ((dayTime / 1000 + 6) % 24);
        int minutes = (int) ((dayTime % 1000) * 60 / 1000);
        Hud.panel(ctx, "World Time", Hud.AQUA,
                Row.of(String.format("%02d:%02d", hours, minutes), Hud.WHITE));
    }

    // =====================================================================
    // Crystal & Respawn
    // =====================================================================

    private static void totemStatus(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        int n = count(p, Items.TOTEM_OF_UNDYING);
        boolean off = p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        Hud.panel(ctx, "Totems", n > 0 ? Hud.GOLD : Hud.RED,
                Row.of("Count: " + n, n > 1 ? Hud.GREEN : n == 1 ? Hud.YELLOW : Hud.RED),
                Row.of("Offhand: " + (off ? "ready" : "empty"), off ? Hud.GREEN : Hud.GRAY));
    }

    private static void anchorMeter(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return;
        BlockState st = mc.world.getBlockState(bhr.getBlockPos());
        if (!st.isOf(Blocks.RESPAWN_ANCHOR)) return;
        int charges = st.get(RespawnAnchorBlock.CHARGES);
        Hud.panel(ctx, "Respawn Anchor", Hud.AQUA,
                Row.bar("Charges: " + charges + "/4", charges / 4f, charges > 0 ? Hud.AQUA : Hud.RED),
                Row.of("Blast power: 5.0 (outside the Nether)", Hud.GRAY),
                Row.of("Damage radius: ~10 blocks", Hud.GRAY));
    }

    // =====================================================================
    // Combat
    // =====================================================================

    public static void drawCrosshair(DrawContext ctx, MinecraftClient mc) {
        Config c = Config.get();
        int cx = ctx.getScaledWindowWidth() / 2;
        int cy = ctx.getScaledWindowHeight() / 2;
        int color = mc.targetedEntity != null ? c.crosshairTargetColor : c.crosshairColor;
        int extra = 0;
        if (c.crosshairDynamic && mc.player != null) {
            Vec3d v = mc.player.getVelocity();
            extra = (int) Math.round(Math.sqrt(v.x * v.x + v.z * v.z) * 12.0);
            if (!mc.player.isOnGround()) extra += 2;
        }
        int size = c.crosshairSize;
        int gap = c.crosshairGap + extra;
        int thickness = Math.max(1, c.crosshairThickness);
        if (c.crosshairOutline) drawCrosshairShape(ctx, cx, cy, size, gap, thickness + 2, c.crosshairOutlineColor);
        drawCrosshairShape(ctx, cx, cy, size, gap, thickness, color);
        Combat.crosshairExtras(ctx, mc, cx, cy, size + gap + 5);
    }

    private static void drawCrosshairShape(DrawContext ctx, int cx, int cy, int size, int gap, int thickness, int color) {
        int lo = -thickness / 2;
        int hi = lo + thickness;
        if (Config.get().crosshairShape == 0 || Config.get().crosshairShape == 3) {
            ctx.fill(cx - gap - size, cy + lo, cx - gap, cy + hi, color);
            ctx.fill(cx + gap, cy + lo, cx + gap + size, cy + hi, color);
            ctx.fill(cx + lo, cy - gap - size, cx + hi, cy - gap, color);
            ctx.fill(cx + lo, cy + gap, cx + hi, cy + gap + size, color);
        }
        if (Config.get().crosshairShape == 1 || Config.get().crosshairShape == 3) {
            int r = Math.max(1, thickness);
            ctx.fill(cx - r, cy - r, cx + r, cy + r, color);
        }
        if (Config.get().crosshairShape == 2) drawCircle(ctx, cx, cy, size + gap, thickness, color);
    }

    private static void drawCircle(DrawContext ctx, int cx, int cy, int radius, int thickness, int color) {
        int steps = Math.max(48, radius * 6);
        int lo = -thickness / 2;
        for (int i = 0; i < steps; i++) {
            double a = i * (Math.PI * 2.0 / steps);
            int x = cx + (int) Math.round(Math.cos(a) * radius);
            int y = cy + (int) Math.round(Math.sin(a) * radius);
            ctx.fill(x + lo, y + lo, x + lo + thickness, y + lo + thickness, color);
        }
    }

    private static void targetRing(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Config c = Config.get();
        drawCircle(ctx, ctx.getScaledWindowWidth() / 2, ctx.getScaledWindowHeight() / 2, c.ringRadius, 1, c.ringColor);
    }

    private static void distanceCounter(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        LivingEntity t = lookedAt(mc, 64);
        if (t == null) return;
        double d = p.getEyePos().distanceTo(t.getBoundingBox().getCenter());
        int cx = ctx.getScaledWindowWidth() / 2;
        int cy = ctx.getScaledWindowHeight() / 2;
        ctx.drawTextWithShadow(mc.textRenderer, String.format("%.1f m", d), cx + 12, cy - 4,
                d <= 3.0 ? Hud.GREEN : d <= 6.0 ? Hud.YELLOW : Hud.WHITE);
    }

    private static void sprintIndicator(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        String state = p.isSprinting() ? "SPRINTING" : p.isSneaking() ? "Sneaking" : "Walking";
        int color = p.isSprinting() ? Hud.GREEN : p.isSneaking() ? Hud.YELLOW : Hud.GRAY;
        Hud.panel(ctx, "Movement", color,
                Row.of(state, color),
                Row.of(String.format("Speed: %.2f b/s", hSpeed(p)), Hud.WHITE));
    }

    private static void guardTiming(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        float atk = p.getAttackCooldownProgress(0f);
        List<Row> rows = new ArrayList<>();
        rows.add(Row.bar(atk >= 1f ? "Weapon: READY" : String.format("Weapon: %d%%", Math.round(atk * 100)),
                atk, atk >= 0.9f ? Hud.GREEN : Hud.YELLOW));
        ItemStack shield = shieldStack(p);
        if (shield != null) {
            float cd = p.getItemCooldownManager().getCooldownProgress(shield, 0f);
            rows.add(cd > 0f
                    ? Row.bar(String.format("Shield DISABLED %d%%", Math.round(cd * 100)), cd, Hud.RED)
                    : Row.of("Shield: ready", Hud.GREEN));
        }
        Hud.panel(ctx, "Guard Timing", Hud.AQUA, rows.toArray(new Row[0]));
    }

    private static ItemStack shieldStack(ClientPlayerEntity p) {
        if (p.getMainHandStack().isOf(Items.SHIELD)) return p.getMainHandStack();
        if (p.getOffHandStack().isOf(Items.SHIELD)) return p.getOffHandStack();
        return null;
    }

    private static void shieldStatus(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        ItemStack s = shieldStack(p);
        if (s == null) return;
        float f = durabilityFrac(s);
        float cd = p.getItemCooldownManager().getCooldownProgress(s, 0f);
        Hud.panel(ctx, "Shield", Hud.AQUA,
                Row.bar(String.format("Durability: %d/%d", s.getMaxDamage() - s.getDamage(), s.getMaxDamage()), f, fracColor(f)),
                Row.of(cd > 0f ? "DISABLED" : p.isBlocking() ? "Blocking" : "Ready", cd > 0f ? Hud.RED : p.isBlocking() ? Hud.GREEN : Hud.GRAY));
    }

    private static void abilityCooldown(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        ItemStack s = p.getMainHandStack();
        if (s.isEmpty()) return;
        float cd = p.getItemCooldownManager().getCooldownProgress(s, 0f);
        if (cd <= 0f) return;
        Hud.panel(ctx, "Cooldown", Hud.GOLD,
                Row.bar(s.getName().getString() + " " + Math.round(cd * 100) + "%", cd, Hud.GOLD));
    }

    private static void combatHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        List<Row> rows = new ArrayList<>();
        float hp = p.getHealth();
        float max = p.getMaxHealth();
        String hpText = String.format("Health: %.1f/%.1f", hp, max);
        if (p.getAbsorptionAmount() > 0) hpText += String.format(" (+%.1f)", p.getAbsorptionAmount());
        rows.add(Row.bar(hpText, hp / max, fracColor(hp / max)));
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        String[] names = {"Helmet", "Chest", "Legs", "Boots"};
        for (int i = 0; i < slots.length; i++) {
            ItemStack a = p.getEquippedStack(slots[i]);
            if (a.isEmpty()) continue;
            float f = durabilityFrac(a);
            rows.add(Row.bar(names[i] + ": " + Math.round(f * 100) + "%", f, fracColor(f)));
        }
        int shown = 0;
        for (StatusEffectInstance e : p.getStatusEffects()) {
            if (shown++ >= 6) break;
            rows.add(Row.of(effectName(e) + " " + Hud.time(e.getDuration()), Hud.WHITE));
        }
        Hud.panel(ctx, "Combat", Hud.RED, rows.toArray(new Row[0]));
    }

    private static LivingEntity stickyTarget;
    private static long stickyUntil;

    /** One-letter armour material code for a worn piece, e.g. N for netherite. */
    private static String armorLetter(ItemStack stack) {
        if (stack.isEmpty()) return "-";
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        if (id.startsWith("netherite")) return "N";
        if (id.startsWith("diamond")) return "D";
        if (id.startsWith("iron")) return "I";
        if (id.startsWith("chainmail")) return "C";
        if (id.startsWith("golden")) return "G";
        if (id.startsWith("leather")) return "L";
        if (id.startsWith("turtle")) return "T";
        return id.contains("elytra") ? "E" : "?";
    }

    private static void entityInfo(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        LivingEntity t = lookedAt(mc, 128);
        long now = System.currentTimeMillis();
        if (t != null) {
            stickyTarget = t;
            stickyUntil = now + 2500L;
        } else if (stickyTarget != null && now < stickyUntil && stickyTarget.isAlive()
                && stickyTarget.squaredDistanceTo(p) < 128.0 * 128.0) {
            t = stickyTarget;
        }
        if (t == null) return;

        float hp = t.getHealth(), max = Math.max(1f, t.getMaxHealth()), abs = t.getAbsorptionAmount();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of("Name: " + t.getDisplayName().getString(), Hud.WHITE));
        rows.add(Row.bar(String.format("Health: %.1f / %.1f", hp, max), hp / max, fracColor(hp / max)));
        if (abs > 0f) rows.add(Row.of(String.format("Absorption: +%.1f", abs), Hud.GOLD));

        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        StringBuilder worn = new StringBuilder();
        for (EquipmentSlot slot : slots) worn.append(armorLetter(t.getEquippedStack(slot))).append(' ');
        rows.add(Row.of("Armor: " + t.getArmor() + "  " + worn.toString().trim(), t.getArmor() >= 15 ? Hud.AQUA : Hud.WHITE));

        if (t instanceof PlayerEntity other) {
            int pops = Combat.pops(other.getUuid());
            rows.add(Row.of("Totem pops: " + pops, pops > 0 ? Hud.GOLD : Hud.GRAY));
        }

        // Lethal-damage indicator: would your next hit (armour included, enchantments not) finish them?
        float hit = Combat.estimateHit(p, t);
        if (hit >= hp + abs) rows.add(Row.of("LETHAL on next hit", Hud.RED));
        else rows.add(Row.of(String.format("Next hit: ~%.1f", hit), Hud.GRAY));

        int shown = 0;
        for (StatusEffectInstance e : t.getStatusEffects()) {
            if (shown++ >= 3) break;
            rows.add(Row.of(effectName(e) + ": " + Hud.time(e.getDuration()), Hud.AQUA));
        }
        Hud.panel(ctx, "Target", Hud.RED, rows.toArray(new Row[0]));
    }

    // =====================================================================
    // Mace & Wind
    // =====================================================================

    private static boolean critReady(ClientPlayerEntity p) {
        return p.fallDistance > 0.0f && !p.isOnGround() && !p.isClimbing() && !p.isTouchingWater()
                && !p.hasStatusEffect(StatusEffects.BLINDNESS) && !p.hasVehicle()
                && p.getAttackCooldownProgress(0.5f) > 0.9f && !p.isSprinting();
    }

    private static void critBar(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        boolean ready = critReady(p);
        float atk = p.getAttackCooldownProgress(0f);
        Hud.panel(ctx, "Critical Hit", ready ? Hud.GREEN : Hud.GRAY,
                Row.of(ready ? "CRIT READY" : "No crit", ready ? Hud.GREEN : Hud.RED),
                Row.bar(String.format("Attack charge %d%%", Math.round(atk * 100)), atk, atk >= 0.9f ? Hud.GREEN : Hud.YELLOW),
                Row.of(String.format("Fall distance: %.2f", (double) p.fallDistance), Hud.WHITE),
                Row.of("Tip: don't sprint-hit; fall and swing at full charge", Hud.GRAY));
    }

    /** Bonus damage a mace adds for a given fall distance (before armour). */
    private static double maceBonus(double fall) {
        if (fall <= 1.5) return 0;
        if (fall <= 3.0) return 4.0 * fall;
        if (fall <= 8.0) return 12.0 + 2.0 * (fall - 3.0);
        return 22.0 + (fall - 8.0);
    }

    private static void maceHeight(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        boolean holding = p.getMainHandStack().isOf(Items.MACE);
        double fall = p.fallDistance;
        if (!holding && fall <= 1.5) return;
        boolean smash = fall > 1.5;
        Hud.panel(ctx, "Mace", Hud.GOLD,
                Row.bar(String.format("Fall: %.1f blocks (min 1.5)", fall), (float) (fall / 1.5), smash ? Hud.GREEN : Hud.YELLOW),
                Row.of(smash ? String.format("Bonus damage: +%.1f (%.1f hearts)", maceBonus(fall), maceBonus(fall) / 2.0) : "Smash attack not armed", smash ? Hud.GREEN : Hud.GRAY),
                Row.of(holding ? "Mace in hand" : "Mace not held", holding ? Hud.GREEN : Hud.RED));
    }

    private static void windTrainer(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        boolean wind = p.getMainHandStack().isOf(Items.WIND_CHARGE) || p.getOffHandStack().isOf(Items.WIND_CHARGE);
        boolean mace = p.getMainHandStack().isOf(Items.MACE);
        double vy = p.getVelocity().y;
        String step;
        int color;
        if (p.isOnGround() && !mace && !wind) {
            step = "1. Hold a wind charge";
            color = Hud.GRAY;
        } else if (p.isOnGround() && wind) {
            step = "2. Look down + right-click to launch";
            color = Hud.YELLOW;
        } else if (!p.isOnGround() && vy > 0.1 && !mace) {
            step = "3. Switch to the mace";
            color = Hud.AQUA;
        } else if (!p.isOnGround() && mace && p.fallDistance <= 1.5) {
            step = "4. Wait for the fall...";
            color = Hud.YELLOW;
        } else if (!p.isOnGround() && mace) {
            step = "5. ATTACK NOW!";
            color = Hud.GREEN;
        } else {
            step = "Ready";
            color = Hud.GRAY;
        }
        Hud.panel(ctx, "Wind Burst Trainer", color, Row.of(step, color));
    }

    private static void jumpGauge(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        double vy = p.getVelocity().y * 20.0;
        float frac = (float) Math.min(1.0, Math.abs(vy) / 30.0);
        Hud.panel(ctx, "Vertical Speed", vy >= 0 ? Hud.GREEN : Hud.RED,
                Row.bar(String.format("%+.1f b/s", vy), frac, vy >= 0 ? Hud.GREEN : Hud.RED));
    }

    private static void elytraHud(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        if (!p.isGliding()) return;
        ItemStack chest = p.getEquippedStack(EquipmentSlot.CHEST);
        double speed = p.getVelocity().length() * 20.0;
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(String.format("Pitch: %.1f°", p.getPitch()), Hud.WHITE));
        rows.add(Row.of(String.format("Speed: %.1f b/s (%.0f km/h)", speed, speed * 3.6), Hud.AQUA));
        if (chest.isOf(Items.ELYTRA)) {
            float f = durabilityFrac(chest);
            rows.add(Row.bar(String.format("Durability: %d/%d", chest.getMaxDamage() - chest.getDamage(), chest.getMaxDamage()), f, fracColor(f)));
        }
        Hud.panel(ctx, "Elytra", Hud.AQUA, rows.toArray(new Row[0]));
    }

    // =====================================================================
    // Minecart & Redstone
    // =====================================================================

    private static void cartSpeedometer(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        List<AbstractMinecartEntity> carts = new ArrayList<>(mc.world.getEntitiesByClass(AbstractMinecartEntity.class,
                p.getBoundingBox().expand(32), e -> true));
        if (carts.isEmpty()) return;
        carts.sort(Comparator.comparingDouble(c -> c.squaredDistanceTo(p)));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(3, carts.size()); i++) {
            AbstractMinecartEntity c = carts.get(i);
            Vec3d v = CART_VEL.getOrDefault(c.getId(), Vec3d.ZERO);
            double bps = v.length() * 20.0;
            String dir = bps < 0.05 ? "still" : Direction.getFacing(v.x, v.y, v.z).asString();
            rows.add(Row.of(String.format("%s: %.1f b/s %s (%.0fm)", c.getType().getName().getString(), bps, dir,
                    (double) c.distanceTo(p)), bps > 8 ? Hud.GREEN : Hud.WHITE));
        }
        Hud.panel(ctx, "Minecarts", Hud.GOLD, rows.toArray(new Row[0]));
    }

    private static void railCounter(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        int rails = count(p, Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL);
        int carts = count(p, Items.MINECART, Items.CHEST_MINECART, Items.FURNACE_MINECART, Items.TNT_MINECART,
                Items.HOPPER_MINECART);
        int redstone = count(p, Items.REDSTONE);
        Hud.panel(ctx, "Rail Supplies", Hud.GOLD,
                Row.of("Rails: " + rails, rails > 0 ? Hud.WHITE : Hud.RED),
                Row.of("Minecarts: " + carts, carts > 0 ? Hud.WHITE : Hud.RED),
                Row.of("Redstone: " + redstone, redstone > 0 ? Hud.WHITE : Hud.RED));
    }

    // =====================================================================
    // Utility
    // =====================================================================

    private static boolean throwable(ItemStack s) {
        return s.isOf(Items.ENDER_PEARL) || s.isOf(Items.SNOWBALL) || s.isOf(Items.EGG)
                || s.isOf(Items.SPLASH_POTION) || s.isOf(Items.LINGERING_POTION);
    }

    private static void throwCalc(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        ItemStack held = throwable(p.getMainHandStack()) ? p.getMainHandStack()
                : throwable(p.getOffHandStack()) ? p.getOffHandStack() : null;
        if (held == null) return;
        boolean potion = held.isOf(Items.SPLASH_POTION) || held.isOf(Items.LINGERING_POTION);
        double speed = potion ? 0.5 : 1.5;
        double gravity = potion ? 0.05 : 0.03;
        float pitch = p.getPitch() + (potion ? -20f : 0f);

        Vec3d vel = Vec3d.fromPolar(pitch, p.getYaw()).multiply(speed);
        Vec3d pv = p.getVelocity();
        vel = vel.add(pv.x, p.isOnGround() ? 0.0 : pv.y, pv.z);
        Vec3d pos = new Vec3d(p.getX(), p.getEyeY() - 0.1, p.getZ());
        Vec3d start = pos;

        Vec3d landing = null;
        int ticks = 0;
        for (int i = 0; i < 300; i++) {
            Vec3d next = pos.add(vel);
            BlockHitResult hit = mc.world.raycast(new RaycastContext(pos, next,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            ticks++;
            if (hit.getType() != HitResult.Type.MISS) {
                landing = hit.getPos();
                break;
            }
            pos = next;
            vel = vel.multiply(0.99).subtract(0, gravity, 0);
            if (pos.y < mc.world.getBottomY() - 16) break;
        }

        if (landing == null) {
            Hud.panel(ctx, "Throw Landing", Hud.GRAY, Row.of("No landing found", Hud.RED));
            return;
        }
        double horiz = Math.hypot(landing.x - start.x, landing.z - start.z);
        Hud.panel(ctx, "Throw Landing", Hud.AQUA,
                Row.of(String.format("Lands at %.0f, %.0f, %.0f", landing.x, landing.y, landing.z), Hud.WHITE),
                Row.of(String.format("Distance: %.1f blocks", horiz), Hud.GREEN),
                Row.of(String.format("Flight time: %d ticks (%.1fs)", ticks, ticks / 20.0), Hud.GRAY));
    }

    private static void reachReadout(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        HitResult hr = mc.crosshairTarget;
        if (hr == null || hr.getType() == HitResult.Type.MISS) return;
        double d = p.getEyePos().distanceTo(hr.getPos());
        int cx = ctx.getScaledWindowWidth() / 2;
        int cy = ctx.getScaledWindowHeight() / 2;
        Hud.centered(ctx, String.format("%.2f", d), cx, cy + 12, d <= 3.0 ? Hud.GREEN : d <= 4.5 ? Hud.YELLOW : Hud.RED);
    }
    private static void crystalAssist(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        int crystals = count(p, Items.END_CRYSTAL);
        String target = "No block targeted";
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            target = mc.world.getBlockState(hit.getBlockPos()).getBlock().getName().getString();
        }
        Hud.panel(ctx, "Crystal Assist", Hud.AQUA,
                Row.of("End crystals: " + crystals, crystals > 0 ? Hud.GREEN : Hud.RED),
                Row.of("Target: " + target, Hud.WHITE));
    }

    private static void crystalCombo(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Hud.panel(ctx, "Crystal Setup", Hud.AQUA,
                Row.of("Crystals: " + count(p, Items.END_CRYSTAL), Hud.WHITE),
                Row.of("Obsidian: " + count(p, Items.OBSIDIAN), Hud.WHITE));
    }

    private static void pearlAssist(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Hud.panel(ctx, "Pearl Assist", Hud.AQUA,
                Row.of("Pearls: " + count(p, Items.ENDER_PEARL),
                        count(p, Items.ENDER_PEARL) > 0 ? Hud.GREEN : Hud.RED));
    }

    private static void windGuide(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Hud.panel(ctx, "Wind Guide", Hud.AQUA,
                Row.of("Wind charges: " + count(p, Items.WIND_CHARGE), Hud.WHITE),
                Row.of(String.format("Vertical speed: %+.1f b/s", p.getVelocity().y * 20.0), Hud.WHITE));
    }

    private static void anchorSafety(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockState state = mc.world.getBlockState(hit.getBlockPos());
        if (!state.isOf(Blocks.RESPAWN_ANCHOR)) return;
        int charges = state.get(RespawnAnchorBlock.CHARGES);
        Hud.panel(ctx, "Anchor Safety", charges > 0 ? Hud.RED : Hud.GREEN,
                Row.of(charges > 0 ? "Caution: anchor is charged" : "Anchor is not charged",
                        charges > 0 ? Hud.RED : Hud.GREEN),
                Row.of("Dimension: " + mc.world.getRegistryKey().getValue().getPath(), Hud.GRAY));
    }

    private static void cartSafety(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Hud.panel(ctx, "Cart Safety", Hud.GOLD,
                Row.of("TNT minecarts: " + count(p, Items.TNT_MINECART), Hud.WHITE),
                Row.of("Obsidian: " + count(p, Items.OBSIDIAN), Hud.WHITE),
                Row.of("Building blocks: " + count(p, Items.OBSIDIAN, Items.COBBLESTONE, Items.DEEPSLATE), Hud.WHITE));
    }

    private static void potionInventory(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        Hud.panel(ctx, "Potion Supplies", Hud.AQUA,
                Row.of("Splash potions: " + count(p, Items.SPLASH_POTION), Hud.WHITE),
                Row.of("Lingering potions: " + count(p, Items.LINGERING_POTION), Hud.WHITE),
                Row.of("Drinkable potions: " + count(p, Items.POTION), Hud.WHITE));
    }

    private static void storageDisplay(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockState state = mc.world.getBlockState(hit.getBlockPos());
        String name = state.getBlock().getName().getString();
        String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).getPath();
        if (!(id.contains("chest") || id.contains("barrel") || id.contains("shulker_box")
                || id.contains("hopper") || id.contains("furnace") || id.contains("dispenser")
                || id.contains("dropper") || id.contains("ender_chest"))) return;
        Hud.panel(ctx, "Storage Display", Hud.AQUA,
                Row.of(name, Hud.WHITE),
                Row.of("Block: " + id, Hud.GRAY));
    }
}
