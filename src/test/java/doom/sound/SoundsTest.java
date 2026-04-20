package doom.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.sound.Sounds.Sfxinfo;
import doom.sound.Sounds.Musicinfo;
import org.junit.jupiter.api.Test;

class SoundsTest {

    @Test
    void musicEnumCountMatchesArrayLength() {
        assertEquals(Sounds.NUMMUSIC, Sounds.S_music.length);
    }

    @Test
    void sfxEnumCountMatchesArrayLength() {
        assertEquals(Sounds.NUMSFX, Sounds.S_sfx.length);
    }

    // S_music[0] is the { 0 } dummy in C — name null, lumpnum 0.
    @Test
    void musicNoneEntryIsNulled() {
        Musicinfo m = Sounds.S_music[Sounds.mus_None];
        assertNotNull(m, "S_music[mus_None] should be a Musicinfo, not null");
        assertNull(m.name);
        assertEquals(0, m.lumpnum);
    }

    // Spot-check first, middle, last music entries against sounds.c.
    @Test
    void musicNamesSpotCheck() {
        assertEquals("e1m1", Sounds.S_music[Sounds.mus_e1m1].name);
        assertEquals("inter", Sounds.S_music[Sounds.mus_inter].name);
        assertEquals("doom", Sounds.S_music[Sounds.mus_doom].name);
        assertEquals("dm2int", Sounds.S_music[Sounds.mus_dm2int].name);
    }

    // sounds.c line 117: sfx[0] is `{ "none", false,  0, 0, -1, -1, 0 }`.
    @Test
    void sfxNoneEntryMatchesC() {
        Sfxinfo s = Sounds.S_sfx[Sounds.sfx_None];
        assertEquals("none", s.name);
        assertFalse(s.singularity);
        assertEquals(0, s.priority);
        assertNull(s.link);
        assertEquals(-1, s.pitch);
        assertEquals(-1, s.volume);
        assertEquals(0, s.usefulness);
    }

    // sounds.c line 119: `{ "pistol", false, 64, 0, -1, -1, 0 }`.
    @Test
    void pistolEntryMatchesC() {
        Sfxinfo s = Sounds.S_sfx[Sounds.sfx_pistol];
        assertEquals("pistol", s.name);
        assertFalse(s.singularity);
        assertEquals(64, s.priority);
        assertNull(s.link);
    }

    // `itemup` is one of the few that use singularity=true (sounds.c line 150).
    @Test
    void itemupIsSingularity() {
        Sfxinfo s = Sounds.S_sfx[Sounds.sfx_itemup];
        assertEquals("itemup", s.name);
        assertTrue(s.singularity);
        assertEquals(78, s.priority);
    }

    // sounds.c line 204: chgun links to pistol and overrides pitch=150.
    @Test
    void chgunLinksToPistolWithOverrides() {
        Sfxinfo s = Sounds.S_sfx[Sounds.sfx_chgun];
        assertEquals("chgun", s.name);
        assertFalse(s.singularity);
        assertEquals(64, s.priority);
        assertSame(Sounds.S_sfx[Sounds.sfx_pistol], s.link);
        assertEquals(150, s.pitch);
        assertEquals(0, s.volume);
    }

    // Priorities on the monster-sight SFX cluster (sounds.c 161–168).
    @Test
    void monsterSightPrioritiesMatchC() {
        assertEquals(94, Sounds.S_sfx[Sounds.sfx_brssit].priority);
        assertEquals(92, Sounds.S_sfx[Sounds.sfx_cybsit].priority);
        assertEquals(90, Sounds.S_sfx[Sounds.sfx_spisit].priority);
    }

    // Last real sfx entry — sounds.c line 226.
    @Test
    void radioIsLastSfx() {
        assertEquals(Sounds.NUMSFX - 1, Sounds.sfx_radio);
        assertEquals("radio", Sounds.S_sfx[Sounds.sfx_radio].name);
        assertEquals(60, Sounds.S_sfx[Sounds.sfx_radio].priority);
    }

    // No gaps in either array — every enum slot got populated.
    @Test
    void everySfxSlotPopulated() {
        for (int i = 0; i < Sounds.NUMSFX; i++) {
            assertNotNull(Sounds.S_sfx[i], "S_sfx[" + i + "] is null");
        }
    }

    @Test
    void everyMusicSlotPopulated() {
        for (int i = 0; i < Sounds.NUMMUSIC; i++) {
            assertNotNull(Sounds.S_music[i], "S_music[" + i + "] is null");
        }
    }
}
