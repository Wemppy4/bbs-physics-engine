package wemppy.bbs_physics.engine;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

/**
 * The scene-wide knobs of one film's physics: gravity, speed, how hard collisions are worked out,
 * and how much of the world takes part.
 *
 * <p>They used to be game settings, which made a film come out differently on every machine it
 * was opened on: the same crate fell under whatever gravity the viewer happened to have set. They
 * are part of the simulation's arithmetic, not a display option, so they travel with the film
 * like its keyframes do. Blender keeps gravity in the scene's properties for the same reason
 * (§7.4).</p>
 *
 * <p>One value per film, not tracks: what changes mid-shot is the bodies, and the world they fall
 * in stays put.</p>
 */
public class FilmPhysics extends ValueGroup
{
    /** Namespaced, so BBS keeps the group in the film's file even with the addon removed. */
    public static final String KEY = "bbs_physics:physics";

    /** What a film without the addon's values reads — a film built outside the game, say. */
    private static final FilmPhysics DEFAULTS = new FilmPhysics();

    /**
     * How hard things fall, in blocks per second squared. Earth is 9.81 and is the default; half
     * gravity reads as slow motion without touching a single keyframe, and zero is a space shot.
     */
    public final ValueFloat gravity = new ValueFloat("gravity", PhysicsWorld.EARTH_GRAVITY, 0F, 40F);

    /** Physical time per film tick; animation is still sampled at the original film tick. */
    public final ValueFloat speed = new ValueFloat("speed", 1F, 0.1F, 4F);

    /**
     * How many times Jolt re-solves collisions inside one film tick. A tick is 50 ms, which is long
     * for a solver aimed at 60 Hz frames, so two is the floor for stacked bodies not sinking into
     * each other; more costs time and buys stability in a pile.
     *
     * <p>Fixed per recording rather than adaptive, deliberately: a value that drifted with the frame
     * rate would make a film stop being reproducible.</p>
     */
    public final ValueInt collisionSteps = new ValueInt("collision_steps", PhysicsWorld.COLLISION_STEPS, 1, 8);

    /**
     * How far around the scene the world's blocks are collected, in blocks. There is no right
     * answer: a shot in a room needs a fraction of what a shot over a canyon does, and the cost is
     * a block scan whose volume is this cubed. Beyond the region there is simply no ground, and a
     * body that leaves it falls forever — which looks exactly like falling through the floor, so
     * the status readout names it rather than leaving it to be guessed at.
     */
    public final ValueInt worldRadius = new ValueInt("world_radius", 32, 8, 96);

    /**
     * How deep under the scene the ground is collected. Reaches further than up: things fall, and a
     * scene on a ledge, a rooftop or a table over a drop needs ground well below it.
     */
    public final ValueInt worldBelow = new ValueInt("world_below", 32, 4, 128);

    /** How high over the scene ceilings and overhangs are collected. */
    public final ValueInt worldAbove = new ValueInt("world_above", 24, 4, 128);

    public FilmPhysics()
    {
        super(KEY);

        this.add(this.gravity);
        this.add(this.speed);
        this.add(this.collisionSteps);
        this.add(this.worldRadius);
        this.add(this.worldBelow);
        this.add(this.worldAbove);
    }

    /** The film's physics knobs; the defaults for a film that carries none. */
    public static FilmPhysics of(Film film)
    {
        return film instanceof PhysicsFilm physics ? physics.bbs_physics$physics() : DEFAULTS;
    }

    /** Puts every knob back to its default. */
    public void resetAll()
    {
        this.gravity.reset();
        this.speed.reset();
        this.collisionSteps.reset();
        this.worldRadius.reset();
        this.worldBelow.reset();
        this.worldAbove.reset();
    }
}
