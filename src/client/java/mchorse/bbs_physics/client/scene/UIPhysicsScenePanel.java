package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.BBSPhysicsSettings;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import mchorse.bbs_physics.engine.PhysicsWorld;

/**
 * The scene's own physics panel — Blender's Rigid Body World, in the dashboard (§7.4).
 *
 * <p>Everything here is a property of the <em>scene</em> rather than of any one object: how hard
 * things fall, how hard the solver works, how much of the world takes part, and how far the film
 * has been recorded. Blender keeps exactly these in the scene's properties, and for the same
 * reason — asking "what is gravity" of a crate is asking the wrong thing.</p>
 *
 * <p>It also takes in the plumbing that used to sit in the mod's settings screen: "blocks around /
 * below / above" are three numbers nobody can decide on until something has already fallen through
 * the world, so they are offered as a choice of region first — room, outdoors, large — with the
 * numbers themselves kept for the case where that guess was wrong.</p>
 */
public class UIPhysicsScenePanel extends UIDashboardPanel
{
    /** Room, outdoors, large: the three answers behind the numbers. Radius, below, above. */
    private static final int[][] REGIONS = {{16, 16, 16}, {32, 32, 24}, {64, 48, 48}};

    private final UIScrollView options;

    private final UILabel status;
    private final UILabel bar;
    private final UIButton computeAll;
    private final UIButton reset;

    private final UITrackpad gravity;
    private final UITrackpad steps;
    private final UICirculate region;
    private final UITrackpad radius;
    private final UITrackpad below;
    private final UITrackpad above;
    private final UIToggle debug;

    public UIPhysicsScenePanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.status = UI.label(PhysicsKeys.SCENE_NONE).background(Colors.A50);
        this.bar = UI.label(PhysicsKeys.SCENE_CACHE_EMPTY);

        this.computeAll = new UIButton(PhysicsKeys.SCENE_COMPUTE, (b) -> this.computeAll());
        this.computeAll.tooltip(PhysicsKeys.SCENE_COMPUTE_TOOLTIP);

        this.reset = new UIButton(PhysicsKeys.SCENE_RESET, (b) -> this.reset());
        this.reset.tooltip(PhysicsKeys.SCENE_RESET_TOOLTIP);

        this.gravity = new UITrackpad((v) -> BBSPhysicsSettings.gravity.set(v.floatValue()));
        this.gravity.limit(0D, 40D).increment(0.5D);
        this.gravity.tooltip(PhysicsKeys.SCENE_GRAVITY_TOOLTIP);

        this.steps = new UITrackpad((v) -> BBSPhysicsSettings.collisionSteps.set(v.intValue()));
        this.steps.limit(1D, 8D).increment(1D).integer();
        this.steps.tooltip(PhysicsKeys.SCENE_STEPS_TOOLTIP);

        this.region = new UICirculate((b) -> this.setRegion(b.getValue()));
        this.region.addLabel(PhysicsKeys.SCENE_REGION_ROOM);
        this.region.addLabel(PhysicsKeys.SCENE_REGION_OUTDOORS);
        this.region.addLabel(PhysicsKeys.SCENE_REGION_LARGE);
        this.region.addLabel(PhysicsKeys.SCENE_REGION_CUSTOM);
        this.region.tooltip(PhysicsKeys.SCENE_REGION_TOOLTIP);

        this.radius = this.blocks((v) -> BBSPhysicsSettings.worldRadius.set(v.intValue()));
        this.below = this.blocks((v) -> BBSPhysicsSettings.worldBelow.set(v.intValue()));
        this.above = this.blocks((v) -> BBSPhysicsSettings.worldAbove.set(v.intValue()));

        this.debug = new UIToggle(PhysicsKeys.SCENE_DEBUG, (b) -> BBSPhysicsSettings.debug.set(b.getValue()));

        this.options = UI.scrollView(UIConstants.MARGIN, UIConstants.SCROLL_PADDING,
            this.status,
            this.bar,
            UI.row(this.computeAll, this.reset),
            UI.label(PhysicsKeys.SCENE_WORLD),
            UI.labelRow(PhysicsKeys.SCENE_GRAVITY, this.gravity),
            UI.labelRow(PhysicsKeys.SCENE_STEPS, this.steps),
            UI.label(PhysicsKeys.SCENE_REGION),
            this.region,
            UI.row(this.radius, this.below, this.above),
            this.debug
        );

        this.options.relative(this).x(0.5F).y(20).w(320).h(1F, -40).anchorX(0.5F);

        this.add(this.options);
    }

    private UITrackpad blocks(java.util.function.Consumer<Double> consumer)
    {
        UITrackpad pad = new UITrackpad(consumer);

        pad.limit(4D, 128D).increment(4D).integer();

        return pad;
    }

    /* Acting */

    private void computeAll()
    {
        FilmScene scene = FilmScenes.getAny();

        if (scene != null)
        {
            scene.computeAll();
        }
    }

    private void reset()
    {
        FilmScene scene = FilmScenes.getAny();

        if (scene != null)
        {
            scene.invalidate();
        }
    }

    /** The three numbers behind a region, or nothing at all when the author picked "custom". */
    private void setRegion(int index)
    {
        if (index < 0 || index >= REGIONS.length)
        {
            return;
        }

        BBSPhysicsSettings.worldRadius.set(REGIONS[index][0]);
        BBSPhysicsSettings.worldBelow.set(REGIONS[index][1]);
        BBSPhysicsSettings.worldAbove.set(REGIONS[index][2]);
    }

    /* Syncing */

    @Override
    public void appear()
    {
        super.appear();

        this.sync();
    }

    @Override
    public void update()
    {
        super.update();

        /* Once a tick, which is what makes the recording's progress readable as progress. */
        this.sync();
    }

    private void sync()
    {
        if (BBSPhysicsSettings.gravity == null)
        {
            return;
        }

        this.gravity.setValue(BBSPhysicsSettings.gravity.get());
        this.steps.setValue(BBSPhysicsSettings.collisionSteps.get());
        this.radius.setValue(BBSPhysicsSettings.worldRadius.get());
        this.below.setValue(BBSPhysicsSettings.worldBelow.get());
        this.above.setValue(BBSPhysicsSettings.worldAbove.get());
        this.debug.setValue(BBSPhysicsSettings.debug.get());
        this.region.setValue(this.regionIndex());

        FilmScene scene = FilmScenes.getAny();

        if (scene == null)
        {
            this.status.label = PhysicsKeys.SCENE_NONE;
            this.bar.label = PhysicsKeys.SCENE_CACHE_EMPTY;

            return;
        }

        SceneStatus status = scene.getStatus();

        this.status.label = PhysicsKeys.SCENE_STATUS.format(status.bodies(), status.filmTick());
        this.bar.label = PhysicsKeys.SCENE_CACHE.format(Math.max(0, status.computed()), status.end(), Math.round(status.progress() * 100F));
    }

    private int regionIndex()
    {
        for (int i = 0; i < REGIONS.length; i++)
        {
            if (BBSPhysicsSettings.worldRadius.get() == REGIONS[i][0]
                && BBSPhysicsSettings.worldBelow.get() == REGIONS[i][1]
                && BBSPhysicsSettings.worldAbove.get() == REGIONS[i][2])
            {
                return i;
            }
        }

        return REGIONS.length;
    }

    @Override
    public boolean needsBackground()
    {
        return true;
    }
}
