package wemppy.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import wemppy.bbs_physics.client.collision.CollisionCollector;
import wemppy.bbs_physics.client.collision.CollisionShapes;

import java.util.List;

/**
 * Mass by material — Blender's {@code Calculate Mass}, which is one of those small things that make
 * a panel feel like an instrument rather than a form to fill in (§7.7).
 *
 * <p>Nobody knows what a crate weighs in kilograms. Everybody knows a crate is made of wood. The
 * volume comes from the collision markup the form already has, so the number that lands in the box
 * is the weight of <em>this</em> shape in <em>that</em> material — and it stays an ordinary number
 * afterwards, free to be overwritten by hand.</p>
 */
public final class BodyMaterials
{
    /** Density in kilograms per cubic metre, and a block is a metre (§8). */
    public record Material(IKey label, float density)
    {}

    public static final List<Material> ALL = List.of(
        new Material(PhysicsKeys.MATERIAL_CORK, 240F),
        new Material(PhysicsKeys.MATERIAL_WOOD, 750F),
        new Material(PhysicsKeys.MATERIAL_WATER, 1000F),
        new Material(PhysicsKeys.MATERIAL_RUBBER, 1400F),
        new Material(PhysicsKeys.MATERIAL_CONCRETE, 2400F),
        new Material(PhysicsKeys.MATERIAL_STONE, 2500F),
        new Material(PhysicsKeys.MATERIAL_IRON, 7900F),
        new Material(PhysicsKeys.MATERIAL_GOLD, 19300F)
    );

    private BodyMaterials()
    {}

    /**
     * What this form would weigh made of {@code material}, or 0 when nothing is marked up yet —
     * there is no volume to weigh, and guessing one would be worse than saying nothing.
     *
     * <p>Every primitive is taken as its box. A capsule counted as its box is a third too heavy in
     * principle, and nobody has ever noticed the difference between a 12 kg crate and a 16 kg one
     * in a shot; being in the right order of magnitude is the whole value of this button.</p>
     */
    public static float estimate(Form form, Material material)
    {
        float volume = 0F;

        for (CollisionCollector.Piece piece : CollisionCollector.collectBody(form, "", null))
        {
            for (CollisionShapes.SubShape sub : piece.shapes())
            {
                volume += 8F * sub.half().x * sub.half().y * sub.half().z;
            }
        }

        return volume * material.density();
    }
}
