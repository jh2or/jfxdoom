// Port of linuxdoom-1.10/info.h mobjinfo_t (lines 1305–1331).
// One row of the global mobjinfo[] table — per-monster/projectile/
// item static parameters that don't change during play. The 23-field
// layout matches the C struct order exactly (preserved for any
// pointer-arithmetic / index callers that might land later).
package doom.play;

public final class MobjInfo {

    public final int doomednum;     // editor thing-type number, -1 if not placeable
    public final int spawnstate;    // Info.S_*
    public final int spawnhealth;
    public final int seestate;      // state when first seen by AI
    public final int seesound;      // Sounds.sfx_*
    public final int reactiontime;  // tic delay between AI decisions
    public final int attacksound;
    public final int painstate;
    public final int painchance;
    public final int painsound;
    public final int meleestate;
    public final int missilestate;
    public final int deathstate;
    public final int xdeathstate;   // gibbed-death state
    public final int deathsound;
    public final int speed;         // movement speed (chase) — fixed_t for missiles
    public final int radius;        // fixed_t
    public final int height;        // fixed_t
    public final int mass;
    public final int damage;        // missile contact damage
    public final int activesound;
    public final int flags;         // Mobj.MF_*
    public final int raisestate;

    public MobjInfo(int doomednum, int spawnstate, int spawnhealth,
                    int seestate, int seesound, int reactiontime,
                    int attacksound, int painstate, int painchance,
                    int painsound, int meleestate, int missilestate,
                    int deathstate, int xdeathstate, int deathsound,
                    int speed, int radius, int height, int mass,
                    int damage, int activesound, int flags, int raisestate) {
        this.doomednum = doomednum;
        this.spawnstate = spawnstate;
        this.spawnhealth = spawnhealth;
        this.seestate = seestate;
        this.seesound = seesound;
        this.reactiontime = reactiontime;
        this.attacksound = attacksound;
        this.painstate = painstate;
        this.painchance = painchance;
        this.painsound = painsound;
        this.meleestate = meleestate;
        this.missilestate = missilestate;
        this.deathstate = deathstate;
        this.xdeathstate = xdeathstate;
        this.deathsound = deathsound;
        this.speed = speed;
        this.radius = radius;
        this.height = height;
        this.mass = mass;
        this.damage = damage;
        this.activesound = activesound;
        this.flags = flags;
        this.raisestate = raisestate;
    }
}
