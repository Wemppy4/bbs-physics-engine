package wemppy.bbs_physics.client.chain;

import mchorse.bbs_mod.forms.forms.Form;
import wemppy.bbs_physics.chain.FormChain;
import wemppy.bbs_physics.chain.FormChains;

import java.util.List;
import java.util.Set;

/**
 * Silences BBS's own chain solver on the bones our chain modifier drives.
 *
 * <p><b>Why it has to exist at all.</b> A strand claimed here becomes a rigid body led by Jolt and
 * written back through {@code orient}/{@code offset}; BBS's solver runs a moment later in the same
 * constraint phase and would write the same bones from its own simulation. Two owners for one
 * strand is a strand doing neither thing — the exact case Р6 named when the collision markup was
 * told to leave chain bones alone.</p>
 *
 * <p><b>How.</b> Not by cancelling the solver — an author may well keep a skirt on the old physics
 * while the hair moves to ours, and cancelling wholesale would take both. Instead a claimed chain
 * is never compiled: BBS then honestly has nothing to say about those bones and everything else
 * runs untouched.</p>
 *
 * <p>Up to BBS 2.4 a model's chains were one stored blob, and this filtered a copy of it — which
 * meant caching the copy, since the compiler keyed its work by the blob's identity and a fresh one
 * every frame recompiled every chain of every model. Since 2.6 a chain is declared on the bone it
 * starts at, and BBS compiles it by walking those bones, so the claim is simply answered per bone
 * as it walks. Nothing is copied and there is nothing left to cache.</p>
 */
public final class ChainMute
{
    private ChainMute()
    {}

    /**
     * Whether our chain modifier owns the strand BBS is about to compile.
     *
     * <p>A chain is claimed when <em>any</em> of its bones is: a strand half-owned is the same
     * fight as a strand wholly owned.</p>
     *
     * @param bones the chain's bones, root to end, or null when BBS has not walked them yet — in
     *              which case the two ends are all there is to go on.
     */
    public static boolean claims(Form form, String root, String end, List<String> bones)
    {
        if (form == null)
        {
            return false;
        }

        FormChain chain = FormChains.get(form);

        if (!chain.enabled() || chain.bones().isEmpty())
        {
            return false;
        }

        Set<String> claimed = chain.bones();

        if (bones != null)
        {
            for (int i = 0; i < bones.size(); i++)
            {
                if (claimed.contains(bones.get(i)))
                {
                    return true;
                }
            }

            return false;
        }

        return claimed.contains(root) || (end != null && !end.isEmpty() && claimed.contains(end));
    }
}
