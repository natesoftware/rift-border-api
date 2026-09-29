package com.natesoftware.riftborder.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A circular, volumetric border for one Minecraft world: a cylinder of a given radius around a centre, optionally capped by a
 * ceiling and a floor, that can be shrunk or moved over time and hurts players who stay outside it. Construct one, chain
 * whichever builders apply, such as {@link #withTheme(BorderTheme)} and {@link #withEvents(BorderEvents)}, then
 * {@link #spawn(double)} to bring it up and {@link #remove()} to tear it down. The shape is driven either by hand, through
 * {@link #moveTo(double, double, double, int)}, {@link #setPosition(double, double, double)} and the pause and resume methods,
 * or by a {@link BorderPhaseController} that walks it through a schedule of {@link BorderPhase}s and owns the shape while it
 * runs.
 * <p>
 * While active the border renders its wall to every player in the world and tracks the players it applies to. Where the
 * rift-border resource pack is available, which the RiftBorder plugin reports through {@link WallStyles} or a host can
 * force with {@link #withShaderWall()}, the wall is a shader cylinder mounted on an invisible {@code ItemDisplay} kept near
 * each player and shown to them alone, and players whose client has not loaded the pack, or who chose particles, see a particle
 * wall instead. Without the pack everyone gets particles. Both walls take their
 * colour from {@link BorderTheme#wallColor()}. The damage tracker runs every tick from spawn to removal regardless of the damage
 * rate, warning, sounding and hurting anyone outside the radius, above the ceiling or below the floor, styled by the
 * {@link BorderTheme} and reporting through the {@link BorderEvents}. Every task runs on the main thread and every method
 * expects to be called from it.
 */
public class GameBorder {

    private static final Logger log = LoggerFactory.getLogger(GameBorder.class);

    private static final BorderTheme DEFAULT_THEME = new BorderTheme() {};
    private static final BorderEvents NO_EVENTS = new BorderEvents() {};

    // Every border spawned and not yet removed, across every host sharing this copy of the library, in spawn order.
    private static final List<GameBorder> ACTIVE = new CopyOnWriteArrayList<>();

    /**
     * Sentinel ceiling meaning the border has no upper bound, {@code Double.MAX_VALUE}. It is what {@link #getMaxHeight()} reports
     * until a ceiling is set and the end height to pass to {@link #moveTo(double, double, double, double, int)} or a
     * {@link BorderPhase} to take one away. While a transition animates between this and a real ceiling the world's max build
     * height stands in for it, so the ceiling visibly descends from the top of the world or rises back to it before the sentinel
     * is restored on the final tick.
     */
    public static final double NO_HEIGHT_LIMIT = Double.MAX_VALUE;

    /**
     * Sentinel floor meaning the border has no lower bound, negative {@code Double.MAX_VALUE}. It is what {@link #getMinHeight()}
     * reports until a floor is set and the end height to pass to {@link #moveTo(double, double, double, double, double, int)} or
     * a {@link BorderPhase} to take one away. While a transition animates between this and a real floor the world's min build
     * height stands in for it, so the floor visibly rises from the bottom of the world or sinks back to it before the sentinel is
     * restored on the final tick.
     */
    public static final double NO_MIN_HEIGHT = -Double.MAX_VALUE;

    final Plugin plugin;
    final World world;

    final double initialCenterX;
    final double initialCenterZ;
    final double centerY;

    private double centerX;
    private double centerZ;
    private double radius;
    private double damagePerSecond = 2.0;

    // Y of the volumetric ceiling.
    private double maxHeight = NO_HEIGHT_LIMIT;

    // Y of the volumetric floor.
    private double minHeight = NO_MIN_HEIGHT;

    // Stamped on this border's wall entities so an orphan sweep can tell them apart from another live border's.
    final UUID id = UUID.randomUUID();

    // The Y each viewer's wall display sits at.
    int anchorY;

    Supplier<Set<UUID>> participantSupplier;
    Runnable onShrinkComplete;
    // The phase controller's advance hook - its own slot, so it and the host's onShrinkComplete never overwrite each other.
    Runnable internalShrinkComplete;
    BorderTheme theme = DEFAULT_THEME;
    BorderEvents events = NO_EVENTS;
    // Registered by BorderPhaseController so remove() stops phase progression - teardown order stops being load-bearing on the caller.
    BorderPhaseController controller;
    // Registered by NextBorderIndicator for the same reason.
    NextBorderIndicator indicator;
    // Null means the host set none: the plugin's WallStyles decide, or everyone gets SHADER without it.
    Function<UUID, WallStyle> wallStyleResolver;
    // The RiftBorder plugin's WallStyles, looked up at spawn, or null without the plugin.
    WallStyles wallStyles;

    final BorderRenderer renderer;
    final ParticleBorderRenderer particleRenderer;
    final BorderShrinkAnimator animator;
    final BorderDamageTracker damageTracker;

    private boolean active;

    // Set by withShaderWall(). The server's players have the rift-border pack, so the shader wall has something to draw.
    boolean shaderWall;

    // Resolved at spawn from shaderWall and the WallStyles, so a late opt-in cannot claim wall displays that were never spawned.
    boolean packConfigured;

    /**
     * Creates an inactive border for world centred at (centerX, centerZ). centerY is the height the wall's display geometry is
     * centred on and is fixed for the border's life, while the horizontal centre moves with every transition and returns to
     * these values on each {@link #spawn(double)}. plugin owns every task and wall entity the border creates. The radius is 0
     * until spawn or a shape call sets it, and there is no ceiling or floor until a shape call sets one. Wall displays default to
     * the world's max build height, one block above the highest one. Nothing is scheduled or spawned here.
     */
    public GameBorder(Plugin plugin, World world, double centerX, double centerY, double centerZ) {
        this.plugin = plugin;
        this.world = world;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.initialCenterX = centerX;
        this.initialCenterZ = centerZ;
        // Just outside build height: vanilla draws an entity inside it only while its section is visible, and sky sections rarely are
        this.anchorY = world.getMaxHeight();
        this.renderer = new BorderRenderer(this);
        this.particleRenderer = new ParticleBorderRenderer(this);
        this.animator = new BorderShrinkAnimator(this);
        this.damageTracker = new BorderDamageTracker(this);
    }

    /**
     * Restricts damage, warning subtitles, sounds and the height indicators to players whose UUIDs appear in the set supplier
     * returns, queried every tick by the damage tracker and again by {@link BorderPhaseController} when it snapshots participants
     * for a {@link ShrinkTargetSelector}. Without one, or whenever the supplier returns null, every player in the world is a
     * candidate. A player who drops out of the set while outside is cleared as listed on
     * {@link BorderEvents#onWarningCleared(UUID)}. Wall rendering ignores the set. Returns this for chaining.
     */
    public GameBorder withParticipants(Supplier<Set<UUID>> supplier) {
        this.participantSupplier = supplier;
        return this;
    }

    /**
     * Registers the host's hook for a shrink or move finishing naturally, replacing any earlier one, with null clearing it. It
     * runs on the main thread from the animation task on the tick the interpolation lands, after an attached
     * {@link BorderPhaseController} has already advanced to its next phase through a separate slot, so a hook that reads
     * controller state sees the new phase. It does not fire when the border is removed mid-transition, when a new
     * {@link #moveTo(double, double, double, int)} or {@link #setPosition(double, double, double)} replaces the transition, or
     * when the controller stops or restarts. Returns this for chaining.
     */
    public GameBorder onShrinkComplete(Runnable callback) {
        this.onShrinkComplete = callback;
        return this;
    }

    /**
     * How the border looks and sounds, as described on {@link BorderTheme}, whose defaults apply to any member not overridden.
     * Optional: without one, or with null, the border uses the defaults throughout. Colours are read live, while the title and
     * the sounds are read at {@link #spawn(double)}, so a new theme's title and sounds take effect on the next spawn. Returns this
     * for chaining.
     */
    public GameBorder withTheme(BorderTheme theme) {
        this.theme = theme != null ? theme : DEFAULT_THEME;
        return this;
    }

    /**
     * Where the border reports what happens, as described on {@link BorderEvents}. Optional: without one, or with null, nothing
     * is told. Replaces any earlier events and takes effect from the next event. Returns this for chaining.
     */
    public GameBorder withEvents(BorderEvents events) {
        this.events = events != null ? events : NO_EVENTS;
        return this;
    }

    /**
     * Decides each player's {@link WallStyle} for this border alone, consulted with the player's UUID on every visibility pass,
     * every 10 ticks, and every particle pass, every 20 ticks, so a player can switch mid-game. A null answer means
     * {@link WallStyle#SHADER}. Setting one overrides the styles players chose through {@link WallStyles} for this border, and
     * null hands the choice back to them, or gives everyone SHADER when the RiftBorder plugin is not installed. It is never
     * consulted while the shader wall is off, since {@link WallStyle#PARTICLE} is then forced for every player. Returns this for
     * chaining.
     */
    public GameBorder withWallStyleResolver(Function<UUID, WallStyle> resolver) {
        this.wallStyleResolver = resolver;
        return this;
    }

    /**
     * Forces the shader wall on, as if the server delivered the rift-border resource pack. Normally unnecessary: the RiftBorder
     * plugin reports whether the pack is available through {@link WallStyles}, and the border asks it at spawn. With the
     * shader wall on, {@link #spawn(double)} gives each viewer a wall display coloured with {@link BorderTheme#wallColor()};
     * without it the border renders as particles for everyone. Read at spawn, so on a border
     * already active it takes effect only when the border is spawned again. Returns this for chaining.
     */
    public GameBorder withShaderWall() {
        this.shaderWall = true;
        return this;
    }

    /**
     * Does nothing. Until 4.1.0 the shader wall hung off a fixed grid of displays around the centre, and this sized that grid.
     * The wall now sits on one display per viewer, spawned where they stand, so there is no grid, no radius cap and no
     * force-loaded chunks. Kept so plugins built against earlier versions still compile and run. Returns this for chaining.
     *
     * @deprecated no grid exists since 4.1.0; remove the call. Slated for removal in 5.0.0.
     */
    @Deprecated(since = "4.1.0", forRemoval = true)
    public GameBorder withGrid(int spacing, int maxExtent) {
        return this;
    }

    /**
     * Y each viewer's wall display sits at. Defaults to the world's max build height, one block above the highest one: vanilla
     * clients only draw an entity inside build height while its chunk section is on screen, so a display inside it vanishes
     * whenever the viewer looks away from that section. Keep it at or above the max build height (or below the min). The shader
     * draws the full cylinder whatever the display height. Read whenever a display is spawned, so a change reaches each viewer
     * as their display is next replaced. Returns this for chaining.
     */
    public GameBorder withAnchorY(int y) {
        this.anchorY = y;
        return this;
    }

    // Wall style for a player: the host's resolver, else the player's chosen style, else SHADER. Without the shader wall, particles.
    WallStyle styleFor(UUID uuid) {
        if (!packConfigured) return WallStyle.PARTICLE;
        WallStyle style = null;
        if (wallStyleResolver != null) {
            style = wallStyleResolver.apply(uuid);
        } else if (wallStyles != null) {
            style = wallStyles.styleFor(uuid);
        }
        return style != null ? style : WallStyle.SHADER;
    }

    // The RiftBorder plugin's WallStyles, or null without it - every step null-safe.
    private WallStyles lookUpWallStyles() {
        ServicesManager services = plugin.getServer() != null ? plugin.getServer().getServicesManager() : null;
        return services != null ? services.load(WallStyles.class) : null;
    }

    /**
     * Every border currently spawned on this server, by any plugin sharing this copy of the library, in spawn order. A
     * snapshot: borders spawned or removed afterwards do not change the returned list.
     */
    public static List<GameBorder> activeBorders() {
        return List.copyOf(ACTIVE);
    }

    // The colour both wall styles draw right now: the shrink colour while moving if the theme set one, else the wall colour,
    // else the default.
    Color wallColor() {
        if (animator.isMoving()) {
            Color shrink = theme.shrinkColor();
            if (shrink != null) return shrink;
        }
        Color color = theme.wallColor();
        return color != null ? color : BorderTheme.DEFAULT_WALL_COLOR;
    }

    /**
     * Sets the damage dealt to a player who has been outside the border for at least the one-second grace period, applied once
     * every 20 ticks, to absorption first and then to health, with a tick that would reach zero health killing through an
     * {@code OUTSIDE_BORDER} damage source instead. A value of 0 or less keeps the warning subtitle, the sounds and the tracking but
     * deals no damage and plays no hurt feedback. Defaults to 2.0. An attached {@link BorderPhaseController} overwrites it with
     * each phase's rate as the phase is entered. Takes effect on the next damage check.
     */
    public void setDamagePerSecond(double damage) {
        this.damagePerSecond = damage;
    }

    /** Returns true from the moment {@link #spawn(double)} succeeds until {@link #remove()} runs, and false before and after. */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns true while a shrink, grow or move is in flight and not paused, which is when the wall shows
     * {@link BorderTheme#shrinkColor()}. False while waiting, paused, or after the transition lands.
     */
    public boolean isMoving() {
        return animator.isMoving();
    }

    /**
     * Returns true when (x, z) lies strictly beyond the current radius on the horizontal plane, ignoring the ceiling and floor,
     * which {@link #isAboveHeight(double)} and {@link #isBelowMinHeight(double)} cover. A point exactly on the circle is inside.
     */
    public boolean isOutside(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz > radius * radius;
    }

    /** Current X of the centre in world coordinates, moving every tick while a transition animates. */
    public double getCenterX() {
        return centerX;
    }

    /** Current Z of the centre in world coordinates, moving every tick while a transition animates. */
    public double getCenterZ() {
        return centerZ;
    }

    /** Current radius in blocks, changing every tick while a transition animates, and 0 before the first spawn or shape call. */
    public double getRadius() {
        return radius;
    }

    /**
     * Radius the shader wall's pattern is anchored to: the end radius of the in-flight transition while it shrinks, otherwise the
     * live radius, so a growing transition anchors to the live radius throughout. A fixed anchor keeps the pattern from sliding
     * along the wall mid-shrink and its density converges as the wall lands. Carried to the wall entities as their Z scale.
     */
    public double getPatternRadius() {
        double end = animator.activeEndRadius();
        return !Double.isNaN(end) && end < radius ? end : radius;
    }

    /** Damage per second currently configured, from {@link #setDamagePerSecond(double)} or the active phase. Defaults to 2.0. */
    public double getDamagePerSecond() {
        return damagePerSecond;
    }

    /**
     * Current ceiling Y, or {@link #NO_HEIGHT_LIMIT} when there is none. While a transition animates a ceiling in or out, this is
     * the interpolated value with the world's max build height standing in for the sentinel at whichever end has no ceiling. A
     * transition that keeps the same ceiling at both ends, including none at all, reports that value unchanged throughout.
     */
    public double getMaxHeight() {
        return maxHeight;
    }

    /**
     * Current floor Y, or {@link #NO_MIN_HEIGHT} when there is none. While a transition animates a floor in or out, this is the
     * interpolated value with the world's min build height standing in for the sentinel at whichever end has no floor. A
     * transition that keeps the same floor at both ends, including none at all, reports that value unchanged throughout.
     */
    public double getMinHeight() {
        return minHeight;
    }

    /** World the border lives in, fixed at construction. */
    public World getWorld() {
        return world;
    }

    /** The plugin that created this border and owns its tasks and wall entities. */
    public Plugin getPlugin() {
        return plugin;
    }

    /** Returns true when y is strictly above the current ceiling, and never for a finite y while there is no ceiling. */
    public boolean isAboveHeight(double y) {
        return y > maxHeight;
    }

    /**
     * Returns true while the ceiling is anything other than {@link #NO_HEIGHT_LIMIT}, including mid-transition while a ceiling
     * animates in or out. A border with no ceiling reports false throughout any transition that does not introduce one.
     */
    public boolean hasHeightLimit() {
        return maxHeight != NO_HEIGHT_LIMIT;
    }

    /** Returns true when y is strictly below the current floor, and never for a finite y while there is no floor. */
    public boolean isBelowMinHeight(double y) {
        return y < minHeight;
    }

    /**
     * Returns true while the floor is anything other than {@link #NO_MIN_HEIGHT}, including mid-transition while a floor animates
     * in or out. A border with no floor reports false throughout any transition that does not introduce one.
     */
    public boolean hasMinHeight() {
        return minHeight != NO_MIN_HEIGHT;
    }

    /**
     * Activates the border at initialRadius. The centre returns to the one given at construction and the ceiling and floor to
     * none, so a border spawned again after {@link #remove()} does not keep the last phase's shape. The {@link WallStyles}
     * are then looked up, and the shader wall is on when {@link #withShaderWall()} was called or they report the pack available,
     * held until removal. When it is on, spawn sweeps wall entities left behind by a border of this plugin that was never
     * removed, then gives every player in the world who sees the shader wall an invisible {@code ItemDisplay} where they stand,
     * shown to them alone and carrying the pack's wall model dyed with {@link BorderTheme#wallColor()}. Every 10 ticks after
     * that, a player who has moved more than 32 blocks from their display gets a fresh one where they stand, the old one
     * leaving a tick later, and players who join the world, switch style or leave are given or lose theirs. When it is off,
     * spawn creates no displays, logs one line, and forces {@link WallStyle#PARTICLE} for everyone without consulting the
     * resolver. The particle renderer starts either way,
     * serving players on the particle style every 20 ticks. The damage tracker also always starts, reading the warning subtitle and
     * sound keys from the theme once: from then on every tick classifies each participating survival or adventure player as inside
     * or outside, warns, plays the sounds, every 40 ticks pulses red dust on the ceiling or floor plane around participants
     * inside the radius and within 10 blocks of that plane in any game mode, and deals the configured damage every 20 ticks after
     * a one-second grace, as described on {@link #setDamagePerSecond(double)}, {@link BorderTheme} and {@link BorderEvents}.
     * Throws IllegalStateException when already active, changing nothing.
     */
    public void spawn(double initialRadius) {
        if (active) throw new IllegalStateException("GameBorder already spawned");
        // Reset the whole shape, not just the radius - a border re-spawned after remove() would otherwise keep the last phase's shape.
        this.radius = initialRadius;
        this.centerX = initialCenterX;
        this.centerZ = initialCenterZ;
        this.maxHeight = NO_HEIGHT_LIMIT;
        this.minHeight = NO_MIN_HEIGHT;
        this.active = true;
        this.wallStyles = lookUpWallStyles();
        this.packConfigured = shaderWall || (wallStyles != null && wallStyles.shaderWallAvailable());
        ACTIVE.add(this);

        // Without the pack the wall displays would render nothing - skip them and leave every player on particles.
        if (packConfigured) {
            renderer.spawn();
        } else {
            log.info("[GameBorder] No rift-border pack available - rendering the border as particles for every player");
        }
        particleRenderer.start();
        // Always tracked: the tracker owns the warning subtitle, the enter sounds and the height indicators, not just damage.
        damageTracker.start();
    }

    /**
     * Tears the border down: stops an attached {@link BorderPhaseController} first, so its pending wait is cancelled and an
     * in-flight shrink freezes where it is, stops an attached {@link NextBorderIndicator}, which then needs its own
     * {@link NextBorderIndicator#start()} to pulse again, then cancels any transition of its own without firing
     * {@link #onShrinkComplete(Runnable)}, marks the border inactive, removes every viewer's wall display, stops the particle
     * wall, and stops the damage tracker, which calls {@link BorderEvents#onWarningCleared(UUID)}
     * for everyone still outside and stops the long-outside sound for those it had played to, and drops out of
     * {@link #activeBorders()}. The shape is left as it was, and so are both registrations.
     * Safe to call when not active, and the border can be spawned again after.
     */
    public void remove() {
        ACTIVE.remove(this);
        if (controller != null) controller.stop();
        if (indicator != null) indicator.stop();
        animator.reset();
        active = false;
        renderer.remove();
        particleRenderer.stop();
        damageTracker.stop();
    }

    /**
     * Shrinks, or grows, to endRadius over remainingTicks, keeping the current centre, ceiling and floor. Equivalent to
     * {@link #moveTo(double, double, double, int)} at the current centre, with the same completion and replacement rules.
     */
    public void startShrinking(double endRadius, int remainingTicks) {
        animator.startShrinking(endRadius, remainingTicks);
    }

    /**
     * Animates the border from its current shape to a centre of (targetX, targetZ) and a radius of endRadius over ticks ticks,
     * keeping the current ceiling and floor untouched throughout. The interpolation is linear, advanced by a task every tick, and
     * lands exactly on the end values when the budget runs out, whereupon an attached controller's hook and then
     * {@link #onShrinkComplete(Runnable)} fire. A budget of 0 or less is treated as 1 tick. Replaces any transition in flight,
     * whose completion then never fires. Works before {@link #spawn(double)} too, moving the shape without activating anything.
     * Not for use while a {@link BorderPhaseController} is running, since the controller owns the shape.
     */
    public void moveTo(double targetX, double targetZ, double endRadius, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, maxHeight, minHeight, ticks);
    }

    /**
     * Variant of {@link #moveTo(double, double, double, int)} that also interpolates the ceiling to endHeight, keeping the current
     * floor. A ceiling coming in from {@link #NO_HEIGHT_LIMIT} descends from the world's max build height, and one going out
     * rises to it before the sentinel is restored on the final tick.
     */
    public void moveTo(double targetX, double targetZ, double endRadius, double endHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, minHeight, ticks);
    }

    /**
     * Variant of {@link #moveTo(double, double, double, int)} that interpolates the ceiling to endHeight and the floor to
     * endMinHeight, the floor rising from the world's min build height when it comes in from {@link #NO_MIN_HEIGHT} and sinking
     * back to it before the sentinel is restored. This is the form {@link BorderPhaseController} drives.
     */
    public void moveTo(
        double targetX, double targetZ, double endRadius, double endHeight, double endMinHeight, int ticks) {
        animator.moveTo(targetX, targetZ, endRadius, endHeight, endMinHeight, ticks);
    }

    /**
     * Rewrites how many ticks the in-flight transition has left without changing its endpoints and re-applies the shape at the
     * matching point along the same path immediately: fewer ticks fast-forward, more rewind, and more than the original budget
     * clamps to the start. Works on a paused transition as well. Does nothing while no transition task is running.
     */
    public void setRemainingTicks(int remainingTicks) {
        animator.setRemainingTicks(remainingTicks);
    }

    /**
     * Freezes the in-flight transition where it is. The task keeps running but stops advancing, so the shape holds and
     * {@link #getPatternRadius()} keeps anchoring to the target. Continue with {@link #resumeShrinking(int)}. Has no lasting
     * effect when nothing is in flight.
     */
    public void pauseShrinking() {
        animator.pause();
    }

    /**
     * Continues a paused, or still running, transition toward the same target, re-interpolating from the live shape over a fresh
     * budget of remainingTicks, so the remaining distance is covered in exactly that time whatever fraction was done before. A
     * budget of 0 or less is treated as 1 tick. Completion fires as for {@link #moveTo(double, double, double, int)}. Does nothing
     * when no transition is in progress: before any has started, after one has landed, or since the last
     * {@link #setPosition(double, double, double)}, {@link #remove()}, {@link BorderPhaseController#stop()} or
     * {@link BorderPhaseController#start(int)}.
     */
    public void resumeShrinking(int remainingTicks) {
        animator.resume(remainingTicks);
    }

    /**
     * Snaps the centre to (cx, cz) and the radius to newRadius immediately, keeping the current ceiling and floor, cancelling any
     * transition in flight without firing its completion, and pushing the new shape to the wall entities the same tick. Leaves
     * {@link #resumeShrinking(int)} nothing to resume until the next transition starts.
     */
    public void setPosition(double cx, double cz, double newRadius) {
        animator.setPosition(cx, cz, newRadius, maxHeight, minHeight);
    }

    /**
     * Snap variant of {@link #setPosition(double, double, double)} that also sets the ceiling to newHeight, with
     * {@link #NO_HEIGHT_LIMIT} meaning none. The floor is kept.
     */
    public void setPosition(double cx, double cz, double newRadius, double newHeight) {
        animator.setPosition(cx, cz, newRadius, newHeight, minHeight);
    }

    /**
     * Snap variant of {@link #setPosition(double, double, double)} that sets the ceiling to newHeight and the floor to
     * newMinHeight, with the no-limit sentinels meaning none. This is the form {@link BorderPhaseController} uses when a resync
     * repositions the border.
     */
    public void setPosition(
        double cx, double cz, double newRadius, double newHeight, double newMinHeight) {
        animator.setPosition(cx, cz, newRadius, newHeight, newMinHeight);
    }

    // Package-private write hook used by BorderShrinkAnimator to push interpolated values back.
    void setShape(double centerX, double centerZ, double radius, double maxHeight, double minHeight) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.maxHeight = maxHeight;
        this.minHeight = minHeight;
    }
}
