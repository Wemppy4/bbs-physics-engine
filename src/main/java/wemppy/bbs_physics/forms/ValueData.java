package wemppy.bbs_physics.forms;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;

/**
 * A value holding a lump of data of whatever shape its owner likes.
 *
 * <p>This is where each physics modifier keeps what cannot be animated — flags, bone sets, joints,
 * markup — as one blob under a single key on the form. The numbers that <em>can</em> be animated
 * are values of their own beside it ({@link PhysicsKnobValue}), because only a value BBS can see
 * becomes a timeline track.</p>
 *
 * <p>BBS used to carry this class itself and dropped it in 2.6, so the addon carries its own. It is
 * a handful of lines either way: a payload that copies on the way in and on the way out, so nobody
 * ends up editing the blob another form is holding.</p>
 */
public class ValueData extends BaseValueBasic<BaseType>
{
    public ValueData(String id)
    {
        super(id, null);
    }

    /**
     * A blob is edited in place all over the addon, so the default captured at construction has to
     * be a copy of its own — otherwise {@code reset()} would hand back whatever the last edit left
     * behind.
     */
    @Override
    protected BaseType copyValue(BaseType value)
    {
        return value == null ? null : value.copy();
    }

    @Override
    public BaseType toData()
    {
        return this.value == null ? null : this.value.copy();
    }

    @Override
    public void fromData(BaseType data)
    {
        this.value = data == null ? null : data.copy();
    }
}
