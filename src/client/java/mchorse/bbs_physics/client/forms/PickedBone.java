package mchorse.bbs_physics.client.forms;

/**
 * The bone the author is currently working on, remembered across the panels that list bones.
 *
 * <p>Two things need it. Clicking a body part in the viewport rebuilds the whole form editor, so a
 * panel's own field cannot survive it — the freshly built panel reads the bone back from here
 * instead of dropping the selection or snapping to the first bone in the list. And moving between
 * the Collision and Physics tabs lands on the same bone rather than wherever that tab was left.</p>
 *
 * <p>BBS keeps one of these for every editor that lists bones, so posing a hand and then opening
 * physics lands on that hand. CML has no such shared memory, so this one is the addon's own: the
 * two physics panels agree with each other, but not with the pose or IK tabs next door.</p>
 */
public final class PickedBone
{
    private static String bone = "";

    private PickedBone()
    {}

    public static String get()
    {
        return bone;
    }

    public static void set(String picked)
    {
        bone = picked == null ? "" : picked;
    }
}
