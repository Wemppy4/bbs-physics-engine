package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import wemppy.bbs_physics.client.forms.PhysicsKeys;
import wemppy.bbs_physics.engine.FilmPhysics;
import wemppy.bbs_physics.engine.PhysicsWorld;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The film's physics knobs, opened from the film editor's gear menu: four rows of named presets
 * where the game's settings used to have six numbers.
 *
 * <p>An author picks "Moon" or "Slow" and knows what they get; the number is one step further,
 * under "Custom", for when no preset is it. A row reads "Custom" whenever the film's value is not
 * one of its presets, so a film tuned by hand never passes for something it is not.</p>
 *
 * <p>Every change is an ordinary edit of the film — undone with the rest, saved with it — and the
 * scene picks it up on its next tick. That is also why there is no OK button: nothing here waits
 * to be applied.</p>
 */
public class UIFilmPhysicsOverlayPanel extends UIOverlayPanel
{
    private static final int WIDTH = 280;
    private static final int CONTROL_WIDTH = 150;
    private static final int GAP = 3;
    private static final int PADDING = 6;

    private final FilmPhysics physics;

    private final UIChoiceButton<Gravity> gravity;
    private final UITrackpad gravityValue;
    private final UIElement gravityCustom;

    private final UIChoiceButton<Speed> speed;
    private final UITrackpad speedValue;
    private final UIElement speedCustom;

    private final UIChoiceButton<Precision> precision;

    private final UIChoiceButton<World> world;
    private final UITrackpad worldRadius;
    private final UITrackpad worldBelow;
    private final UITrackpad worldAbove;
    private final List<UIElement> worldCustom;

    /** Every row in order, the hidden ones included — what the panel's height is summed from. */
    private final List<UIElement> rows;

    /**
     * Whether a row was switched to "Custom". Kept apart from the value: picking "Custom" changes
     * no number, and the row must stay open on a value that still happens to equal a preset.
     */
    private boolean customGravity;
    private boolean customSpeed;
    private boolean customWorld;

    public UIFilmPhysicsOverlayPanel(Film film)
    {
        super(PhysicsKeys.FILM_PHYSICS);

        this.w(WIDTH);

        this.physics = FilmPhysics.of(film);

        this.gravity = choice(Gravity.values(), Icons.GLOBE, PhysicsKeys.FILM_PHYSICS_GRAVITY_TOOLTIP, (preset) ->
        {
            this.customGravity = preset == Gravity.CUSTOM;

            if (!this.customGravity)
            {
                BaseValue.edit(this.physics.gravity, (value) -> value.set(preset.value));
            }
        });
        this.gravityValue = trackpad(this.physics.gravity);
        this.gravityCustom = row(IKey.EMPTY, this.gravityValue);

        this.speed = choice(Speed.values(), Icons.STOPWATCH, PhysicsKeys.FILM_PHYSICS_SPEED_TOOLTIP, (preset) ->
        {
            this.customSpeed = preset == Speed.CUSTOM;

            if (!this.customSpeed)
            {
                BaseValue.edit(this.physics.speed, (value) -> value.set(preset.value));
            }
        });
        this.speedValue = trackpad(this.physics.speed);
        this.speedCustom = row(IKey.EMPTY, this.speedValue);

        this.precision = choice(Precision.values(), Icons.PROCESSOR, PhysicsKeys.FILM_PHYSICS_PRECISION_TOOLTIP, (preset) ->
            BaseValue.edit(this.physics.collisionSteps, (value) -> value.set(preset.steps)));

        this.world = choice(World.values(), Icons.BLOCK, PhysicsKeys.FILM_PHYSICS_WORLD_TOOLTIP, (preset) ->
        {
            this.customWorld = preset == World.CUSTOM;

            if (!this.customWorld)
            {
                /* One edit for the three numbers, so a preset is one step of undo */
                BaseValue.edit(this.physics, (physics) ->
                {
                    physics.worldRadius.set(preset.radius);
                    physics.worldBelow.set(preset.below);
                    physics.worldAbove.set(preset.above);
                });
            }
        });
        this.worldRadius = trackpad(this.physics.worldRadius);
        this.worldBelow = trackpad(this.physics.worldBelow);
        this.worldAbove = trackpad(this.physics.worldAbove);
        this.worldCustom = List.of(
            row(PhysicsKeys.FILM_PHYSICS_WORLD_RADIUS, this.worldRadius),
            row(PhysicsKeys.FILM_PHYSICS_WORLD_BELOW, this.worldBelow),
            row(PhysicsKeys.FILM_PHYSICS_WORLD_ABOVE, this.worldAbove));

        this.rows = List.of(
            row(PhysicsKeys.FILM_PHYSICS_GRAVITY, this.gravity), this.gravityCustom,
            row(PhysicsKeys.FILM_PHYSICS_SPEED, this.speed), this.speedCustom,
            row(PhysicsKeys.FILM_PHYSICS_PRECISION, this.precision),
            row(PhysicsKeys.FILM_PHYSICS_WORLD, this.world),
            this.worldCustom.get(0), this.worldCustom.get(1), this.worldCustom.get(2));

        UIScrollView editor = UI.scrollView(GAP, PADDING, this.rows.toArray(new UIElement[0]));

        editor.relative(this.content).w(1F).h(1F);
        this.content.add(editor);

        UIIcon reset = new UIIcon(Icons.REFRESH, (b) ->
        {
            this.customGravity = false;
            this.customSpeed = false;
            this.customWorld = false;

            BaseValue.edit(this.physics, FilmPhysics::resetAll);
        });

        reset.tooltip(PhysicsKeys.FILM_PHYSICS_RESET, Direction.LEFT);
        this.icons.add(reset);

        /* Read every frame, so an undo while the panel is open shows up in it */
        this.refresh();
        this.valueBinding(() ->
        {
            if (this.refresh())
            {
                this.resize();
            }
        });
    }

    private static <T> UIChoiceButton<T> choice(T[] options, Icon icon, IKey tooltip, Consumer<T> pick)
    {
        UIChoiceButton<T> button = new UIChoiceButton<>(List.of(options),
            (option) -> option instanceof Preset preset && preset.isCustom() ? Icons.EDIT : icon,
            (option) -> ((Preset) option).label());

        button.callback(pick);
        button.tooltip(tooltip);

        return button;
    }

    private static UITrackpad trackpad(ValueFloat value)
    {
        UITrackpad trackpad = new UITrackpad((v) -> BaseValue.edit(value, (x) -> x.set(v.floatValue())));

        trackpad.limit(value);

        return trackpad;
    }

    private static UITrackpad trackpad(ValueInt value)
    {
        UITrackpad trackpad = new UITrackpad((v) -> BaseValue.edit(value, (x) -> x.set(v.intValue())));

        trackpad.limit(value).integer();

        return trackpad;
    }

    private static UIElement row(IKey label, UIElement control)
    {
        return UI.labelRow(label, CONTROL_WIDTH, control);
    }

    /**
     * Puts the film's values into the widgets, and opens or closes the "Custom" rows.
     *
     * @return whether a row was opened or closed, so the panel has to be laid out again
     */
    private boolean refresh()
    {
        float gravity = this.physics.gravity.get();
        Gravity gravityPreset = Gravity.match(gravity);

        this.customGravity |= gravityPreset == null;
        this.gravity.setValue(this.customGravity ? Gravity.CUSTOM : gravityPreset);
        this.gravityValue.setValue(gravity);

        float speed = this.physics.speed.get();
        Speed speedPreset = Speed.match(speed);

        this.customSpeed |= speedPreset == null;
        this.speed.setValue(this.customSpeed ? Speed.CUSTOM : speedPreset);
        this.speedValue.setValue(speed);

        this.precision.setValue(Precision.nearest(this.physics.collisionSteps.get()));

        int radius = this.physics.worldRadius.get();
        int below = this.physics.worldBelow.get();
        int above = this.physics.worldAbove.get();
        World worldPreset = World.match(radius, below, above);

        this.customWorld |= worldPreset == null;
        this.world.setValue(this.customWorld ? World.CUSTOM : worldPreset);
        this.worldRadius.setValue(radius);
        this.worldBelow.setValue(below);
        this.worldAbove.setValue(above);

        boolean changed = show(this.gravityCustom, this.customGravity);

        changed |= show(this.speedCustom, this.customSpeed);

        for (UIElement row : this.worldCustom)
        {
            changed |= show(row, this.customWorld);
        }

        return changed;
    }

    private static boolean show(UIElement element, boolean visible)
    {
        if (element.isVisible() == visible)
        {
            return false;
        }

        element.setVisible(visible);

        return true;
    }

    /** Hugs the visible rows, so opening "Custom" grows the panel instead of making it scroll. */
    @Override
    public int getContentHeight()
    {
        int height = 0;
        int visible = 0;

        for (UIElement row : this.rows)
        {
            if (!row.isVisible())
            {
                continue;
            }

            if (row.area.h <= 0)
            {
                return -1;
            }

            height += row.area.h;
            visible += 1;
        }

        return this.content.getFlex().y.offset + PADDING * 2 + height + GAP * Math.max(visible - 1, 0);
    }

    /* The presets. Labels carry their numbers, so the menu says what a word means. */

    private interface Preset
    {
        IKey label();

        default boolean isCustom()
        {
            return false;
        }
    }

    private enum Gravity implements Preset
    {
        EARTH(PhysicsWorld.EARTH_GRAVITY), MOON(1.62F), HALF(PhysicsWorld.EARTH_GRAVITY / 2F), WEIGHTLESS(0F), CUSTOM(Float.NaN);

        private final float value;
        private final IKey label = L10n.lang("bbs_physics.film_physics.gravity." + this.name().toLowerCase(Locale.ROOT));

        Gravity(float value)
        {
            this.value = value;
        }

        @Override
        public IKey label()
        {
            return this.label;
        }

        @Override
        public boolean isCustom()
        {
            return this == CUSTOM;
        }

        /** The preset holding {@code value}, or null; never "Custom", whose NaN equals nothing. */
        static Gravity match(float value)
        {
            for (Gravity preset : values())
            {
                if (Math.abs(preset.value - value) < 0.001F)
                {
                    return preset;
                }
            }

            return null;
        }
    }

    private enum Speed implements Preset
    {
        VERY_SLOW(0.25F), SLOW(0.5F), NORMAL(1F), FAST(2F), CUSTOM(Float.NaN);

        private final float value;
        private final IKey label = L10n.lang("bbs_physics.film_physics.speed." + this.name().toLowerCase(Locale.ROOT));

        Speed(float value)
        {
            this.value = value;
        }

        @Override
        public IKey label()
        {
            return this.label;
        }

        @Override
        public boolean isCustom()
        {
            return this == CUSTOM;
        }

        static Speed match(float value)
        {
            for (Speed preset : values())
            {
                if (Math.abs(preset.value - value) < 0.001F)
                {
                    return preset;
                }
            }

            return null;
        }
    }

    /** Collision sub-steps, under a name that says what they buy. No "Custom": four is plenty. */
    private enum Precision implements Preset
    {
        FAST(2), NORMAL(PhysicsWorld.COLLISION_STEPS), HIGH(5), MAXIMUM(8);

        private final int steps;
        private final IKey label = L10n.lang("bbs_physics.film_physics.precision." + this.name().toLowerCase(Locale.ROOT));

        Precision(int steps)
        {
            this.steps = steps;
        }

        @Override
        public IKey label()
        {
            return this.label;
        }

        static Precision nearest(int steps)
        {
            Precision best = NORMAL;

            for (Precision preset : values())
            {
                if (Math.abs(preset.steps - steps) < Math.abs(best.steps - steps))
                {
                    best = preset;
                }
            }

            return best;
        }
    }

    /** The world's blocks around the scene: around, below and above, in one word. */
    private enum World implements Preset
    {
        SMALL(16, 16, 12), MEDIUM(32, 32, 24), LARGE(64, 64, 48), HUGE(96, 128, 64), CUSTOM(-1, -1, -1);

        private final int radius;
        private final int below;
        private final int above;
        private final IKey label = L10n.lang("bbs_physics.film_physics.world." + this.name().toLowerCase(Locale.ROOT));

        World(int radius, int below, int above)
        {
            this.radius = radius;
            this.below = below;
            this.above = above;
        }

        @Override
        public IKey label()
        {
            return this.label;
        }

        @Override
        public boolean isCustom()
        {
            return this == CUSTOM;
        }

        static World match(int radius, int below, int above)
        {
            for (World preset : values())
            {
                if (preset.radius == radius && preset.below == below && preset.above == above)
                {
                    return preset;
                }
            }

            return null;
        }
    }
}
