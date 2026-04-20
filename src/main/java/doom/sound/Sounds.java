// Port of linuxdoom-1.10/sounds.c and sounds.h.
// Static sound-effect and music tables plus the enum identifiers used
// to index them. S_sfx is mutated at runtime by I_Sound / S_Sound
// (usefulness, data, lumpnum), so entries are plain mutable records.
package doom.sound;

public final class Sounds {

    // sfxinfo_t — sounds.h lines 30–62.
    public static final class Sfxinfo {
        // up to 6-character name
        public String name;

        // Sfx singularity (only one at a time)
        public boolean singularity;

        // Sfx priority
        public int priority;

        // referenced sound if a link
        public Sfxinfo link;

        // pitch if a link
        public int pitch;

        // volume if a link
        public int volume;

        // sound data
        public Object data;

        // this is checked every second to see if sound
        // can be thrown out (if 0, then decrement, if -1,
        // then throw out, if > 0, then it is in use)
        public int usefulness;

        // lump number of sfx
        public int lumpnum;

        public Sfxinfo(String name, boolean singularity, int priority,
                       Sfxinfo link, int pitch, int volume, int usefulness) {
            this.name = name;
            this.singularity = singularity;
            this.priority = priority;
            this.link = link;
            this.pitch = pitch;
            this.volume = volume;
            this.usefulness = usefulness;
        }
    }

    // musicinfo_t — sounds.h lines 70–84.
    public static final class Musicinfo {
        // up to 6-character name
        public String name;

        // lump number of music
        public int lumpnum;

        // music data
        public Object data;

        // music handle once registered
        public int handle;

        public Musicinfo(String name, int lumpnum) {
            this.name = name;
            this.lumpnum = lumpnum;
        }
    }

    // musicenum_t — sounds.h lines 99–170.
    public static final int mus_None    = 0;
    public static final int mus_e1m1    = 1;
    public static final int mus_e1m2    = 2;
    public static final int mus_e1m3    = 3;
    public static final int mus_e1m4    = 4;
    public static final int mus_e1m5    = 5;
    public static final int mus_e1m6    = 6;
    public static final int mus_e1m7    = 7;
    public static final int mus_e1m8    = 8;
    public static final int mus_e1m9    = 9;
    public static final int mus_e2m1    = 10;
    public static final int mus_e2m2    = 11;
    public static final int mus_e2m3    = 12;
    public static final int mus_e2m4    = 13;
    public static final int mus_e2m5    = 14;
    public static final int mus_e2m6    = 15;
    public static final int mus_e2m7    = 16;
    public static final int mus_e2m8    = 17;
    public static final int mus_e2m9    = 18;
    public static final int mus_e3m1    = 19;
    public static final int mus_e3m2    = 20;
    public static final int mus_e3m3    = 21;
    public static final int mus_e3m4    = 22;
    public static final int mus_e3m5    = 23;
    public static final int mus_e3m6    = 24;
    public static final int mus_e3m7    = 25;
    public static final int mus_e3m8    = 26;
    public static final int mus_e3m9    = 27;
    public static final int mus_inter   = 28;
    public static final int mus_intro   = 29;
    public static final int mus_bunny   = 30;
    public static final int mus_victor  = 31;
    public static final int mus_introa  = 32;
    public static final int mus_runnin  = 33;
    public static final int mus_stalks  = 34;
    public static final int mus_countd  = 35;
    public static final int mus_betwee  = 36;
    public static final int mus_doom    = 37;
    public static final int mus_the_da  = 38;
    public static final int mus_shawn   = 39;
    public static final int mus_ddtblu  = 40;
    public static final int mus_in_cit  = 41;
    public static final int mus_dead    = 42;
    public static final int mus_stlks2  = 43;
    public static final int mus_theda2  = 44;
    public static final int mus_doom2   = 45;
    public static final int mus_ddtbl2  = 46;
    public static final int mus_runni2  = 47;
    public static final int mus_dead2   = 48;
    public static final int mus_stlks3  = 49;
    public static final int mus_romero  = 50;
    public static final int mus_shawn2  = 51;
    public static final int mus_messag  = 52;
    public static final int mus_count2  = 53;
    public static final int mus_ddtbl3  = 54;
    public static final int mus_ampie   = 55;
    public static final int mus_theda3  = 56;
    public static final int mus_adrian  = 57;
    public static final int mus_messg2  = 58;
    public static final int mus_romer2  = 59;
    public static final int mus_tense   = 60;
    public static final int mus_shawn3  = 61;
    public static final int mus_openin  = 62;
    public static final int mus_evil    = 63;
    public static final int mus_ultima  = 64;
    public static final int mus_read_m  = 65;
    public static final int mus_dm2ttl  = 66;
    public static final int mus_dm2int  = 67;
    public static final int NUMMUSIC    = 68;

    // sfxenum_t — sounds.h lines 177–289.
    public static final int sfx_None    = 0;
    public static final int sfx_pistol  = 1;
    public static final int sfx_shotgn  = 2;
    public static final int sfx_sgcock  = 3;
    public static final int sfx_dshtgn  = 4;
    public static final int sfx_dbopn   = 5;
    public static final int sfx_dbcls   = 6;
    public static final int sfx_dbload  = 7;
    public static final int sfx_plasma  = 8;
    public static final int sfx_bfg     = 9;
    public static final int sfx_sawup   = 10;
    public static final int sfx_sawidl  = 11;
    public static final int sfx_sawful  = 12;
    public static final int sfx_sawhit  = 13;
    public static final int sfx_rlaunc  = 14;
    public static final int sfx_rxplod  = 15;
    public static final int sfx_firsht  = 16;
    public static final int sfx_firxpl  = 17;
    public static final int sfx_pstart  = 18;
    public static final int sfx_pstop   = 19;
    public static final int sfx_doropn  = 20;
    public static final int sfx_dorcls  = 21;
    public static final int sfx_stnmov  = 22;
    public static final int sfx_swtchn  = 23;
    public static final int sfx_swtchx  = 24;
    public static final int sfx_plpain  = 25;
    public static final int sfx_dmpain  = 26;
    public static final int sfx_popain  = 27;
    public static final int sfx_vipain  = 28;
    public static final int sfx_mnpain  = 29;
    public static final int sfx_pepain  = 30;
    public static final int sfx_slop    = 31;
    public static final int sfx_itemup  = 32;
    public static final int sfx_wpnup   = 33;
    public static final int sfx_oof     = 34;
    public static final int sfx_telept  = 35;
    public static final int sfx_posit1  = 36;
    public static final int sfx_posit2  = 37;
    public static final int sfx_posit3  = 38;
    public static final int sfx_bgsit1  = 39;
    public static final int sfx_bgsit2  = 40;
    public static final int sfx_sgtsit  = 41;
    public static final int sfx_cacsit  = 42;
    public static final int sfx_brssit  = 43;
    public static final int sfx_cybsit  = 44;
    public static final int sfx_spisit  = 45;
    public static final int sfx_bspsit  = 46;
    public static final int sfx_kntsit  = 47;
    public static final int sfx_vilsit  = 48;
    public static final int sfx_mansit  = 49;
    public static final int sfx_pesit   = 50;
    public static final int sfx_sklatk  = 51;
    public static final int sfx_sgtatk  = 52;
    public static final int sfx_skepch  = 53;
    public static final int sfx_vilatk  = 54;
    public static final int sfx_claw    = 55;
    public static final int sfx_skeswg  = 56;
    public static final int sfx_pldeth  = 57;
    public static final int sfx_pdiehi  = 58;
    public static final int sfx_podth1  = 59;
    public static final int sfx_podth2  = 60;
    public static final int sfx_podth3  = 61;
    public static final int sfx_bgdth1  = 62;
    public static final int sfx_bgdth2  = 63;
    public static final int sfx_sgtdth  = 64;
    public static final int sfx_cacdth  = 65;
    public static final int sfx_skldth  = 66;
    public static final int sfx_brsdth  = 67;
    public static final int sfx_cybdth  = 68;
    public static final int sfx_spidth  = 69;
    public static final int sfx_bspdth  = 70;
    public static final int sfx_vildth  = 71;
    public static final int sfx_kntdth  = 72;
    public static final int sfx_pedth   = 73;
    public static final int sfx_skedth  = 74;
    public static final int sfx_posact  = 75;
    public static final int sfx_bgact   = 76;
    public static final int sfx_dmact   = 77;
    public static final int sfx_bspact  = 78;
    public static final int sfx_bspwlk  = 79;
    public static final int sfx_vilact  = 80;
    public static final int sfx_noway   = 81;
    public static final int sfx_barexp  = 82;
    public static final int sfx_punch   = 83;
    public static final int sfx_hoof    = 84;
    public static final int sfx_metal   = 85;
    public static final int sfx_chgun   = 86;
    public static final int sfx_tink    = 87;
    public static final int sfx_bdopn   = 88;
    public static final int sfx_bdcls   = 89;
    public static final int sfx_itmbk   = 90;
    public static final int sfx_flame   = 91;
    public static final int sfx_flamst  = 92;
    public static final int sfx_getpow  = 93;
    public static final int sfx_bospit  = 94;
    public static final int sfx_boscub  = 95;
    public static final int sfx_bossit  = 96;
    public static final int sfx_bospn   = 97;
    public static final int sfx_bosdth  = 98;
    public static final int sfx_manatk  = 99;
    public static final int sfx_mandth  = 100;
    public static final int sfx_sssit   = 101;
    public static final int sfx_ssdth   = 102;
    public static final int sfx_keenpn  = 103;
    public static final int sfx_keendt  = 104;
    public static final int sfx_skeact  = 105;
    public static final int sfx_skesit  = 106;
    public static final int sfx_skeatk  = 107;
    public static final int sfx_radio   = 108;
    public static final int NUMSFX      = 109;

    // S_music[] — sounds.c lines 37–107. Ordering matches musicenum_t.
    // First slot is the { 0 } "none" dummy (name null, lumpnum 0).
    public static final Musicinfo[] S_music = new Musicinfo[NUMMUSIC];

    // S_sfx[] — sounds.c lines 114–227. Ordering matches sfxenum_t.
    // The `chgun` entry links to `sfx_pistol` with a pitch override;
    // in C this is `&S_sfx[sfx_pistol]` self-reference, which we
    // patch up after array construction.
    public static final Sfxinfo[] S_sfx = new Sfxinfo[NUMSFX];

    static {
        S_music[mus_None]   = new Musicinfo(null, 0);
        S_music[mus_e1m1]   = new Musicinfo("e1m1", 0);
        S_music[mus_e1m2]   = new Musicinfo("e1m2", 0);
        S_music[mus_e1m3]   = new Musicinfo("e1m3", 0);
        S_music[mus_e1m4]   = new Musicinfo("e1m4", 0);
        S_music[mus_e1m5]   = new Musicinfo("e1m5", 0);
        S_music[mus_e1m6]   = new Musicinfo("e1m6", 0);
        S_music[mus_e1m7]   = new Musicinfo("e1m7", 0);
        S_music[mus_e1m8]   = new Musicinfo("e1m8", 0);
        S_music[mus_e1m9]   = new Musicinfo("e1m9", 0);
        S_music[mus_e2m1]   = new Musicinfo("e2m1", 0);
        S_music[mus_e2m2]   = new Musicinfo("e2m2", 0);
        S_music[mus_e2m3]   = new Musicinfo("e2m3", 0);
        S_music[mus_e2m4]   = new Musicinfo("e2m4", 0);
        S_music[mus_e2m5]   = new Musicinfo("e2m5", 0);
        S_music[mus_e2m6]   = new Musicinfo("e2m6", 0);
        S_music[mus_e2m7]   = new Musicinfo("e2m7", 0);
        S_music[mus_e2m8]   = new Musicinfo("e2m8", 0);
        S_music[mus_e2m9]   = new Musicinfo("e2m9", 0);
        S_music[mus_e3m1]   = new Musicinfo("e3m1", 0);
        S_music[mus_e3m2]   = new Musicinfo("e3m2", 0);
        S_music[mus_e3m3]   = new Musicinfo("e3m3", 0);
        S_music[mus_e3m4]   = new Musicinfo("e3m4", 0);
        S_music[mus_e3m5]   = new Musicinfo("e3m5", 0);
        S_music[mus_e3m6]   = new Musicinfo("e3m6", 0);
        S_music[mus_e3m7]   = new Musicinfo("e3m7", 0);
        S_music[mus_e3m8]   = new Musicinfo("e3m8", 0);
        S_music[mus_e3m9]   = new Musicinfo("e3m9", 0);
        S_music[mus_inter]  = new Musicinfo("inter", 0);
        S_music[mus_intro]  = new Musicinfo("intro", 0);
        S_music[mus_bunny]  = new Musicinfo("bunny", 0);
        S_music[mus_victor] = new Musicinfo("victor", 0);
        S_music[mus_introa] = new Musicinfo("introa", 0);
        S_music[mus_runnin] = new Musicinfo("runnin", 0);
        S_music[mus_stalks] = new Musicinfo("stalks", 0);
        S_music[mus_countd] = new Musicinfo("countd", 0);
        S_music[mus_betwee] = new Musicinfo("betwee", 0);
        S_music[mus_doom]   = new Musicinfo("doom", 0);
        S_music[mus_the_da] = new Musicinfo("the_da", 0);
        S_music[mus_shawn]  = new Musicinfo("shawn", 0);
        S_music[mus_ddtblu] = new Musicinfo("ddtblu", 0);
        S_music[mus_in_cit] = new Musicinfo("in_cit", 0);
        S_music[mus_dead]   = new Musicinfo("dead", 0);
        S_music[mus_stlks2] = new Musicinfo("stlks2", 0);
        S_music[mus_theda2] = new Musicinfo("theda2", 0);
        S_music[mus_doom2]  = new Musicinfo("doom2", 0);
        S_music[mus_ddtbl2] = new Musicinfo("ddtbl2", 0);
        S_music[mus_runni2] = new Musicinfo("runni2", 0);
        S_music[mus_dead2]  = new Musicinfo("dead2", 0);
        S_music[mus_stlks3] = new Musicinfo("stlks3", 0);
        S_music[mus_romero] = new Musicinfo("romero", 0);
        S_music[mus_shawn2] = new Musicinfo("shawn2", 0);
        S_music[mus_messag] = new Musicinfo("messag", 0);
        S_music[mus_count2] = new Musicinfo("count2", 0);
        S_music[mus_ddtbl3] = new Musicinfo("ddtbl3", 0);
        S_music[mus_ampie]  = new Musicinfo("ampie", 0);
        S_music[mus_theda3] = new Musicinfo("theda3", 0);
        S_music[mus_adrian] = new Musicinfo("adrian", 0);
        S_music[mus_messg2] = new Musicinfo("messg2", 0);
        S_music[mus_romer2] = new Musicinfo("romer2", 0);
        S_music[mus_tense]  = new Musicinfo("tense", 0);
        S_music[mus_shawn3] = new Musicinfo("shawn3", 0);
        S_music[mus_openin] = new Musicinfo("openin", 0);
        S_music[mus_evil]   = new Musicinfo("evil", 0);
        S_music[mus_ultima] = new Musicinfo("ultima", 0);
        S_music[mus_read_m] = new Musicinfo("read_m", 0);
        S_music[mus_dm2ttl] = new Musicinfo("dm2ttl", 0);
        S_music[mus_dm2int] = new Musicinfo("dm2int", 0);

        // S_sfx[0] needs to be a dummy for odd reasons.
        S_sfx[sfx_None]   = new Sfxinfo("none",   false,  0, null, -1, -1, 0);
        S_sfx[sfx_pistol] = new Sfxinfo("pistol", false, 64, null, -1, -1, 0);
        S_sfx[sfx_shotgn] = new Sfxinfo("shotgn", false, 64, null, -1, -1, 0);
        S_sfx[sfx_sgcock] = new Sfxinfo("sgcock", false, 64, null, -1, -1, 0);
        S_sfx[sfx_dshtgn] = new Sfxinfo("dshtgn", false, 64, null, -1, -1, 0);
        S_sfx[sfx_dbopn]  = new Sfxinfo("dbopn",  false, 64, null, -1, -1, 0);
        S_sfx[sfx_dbcls]  = new Sfxinfo("dbcls",  false, 64, null, -1, -1, 0);
        S_sfx[sfx_dbload] = new Sfxinfo("dbload", false, 64, null, -1, -1, 0);
        S_sfx[sfx_plasma] = new Sfxinfo("plasma", false, 64, null, -1, -1, 0);
        S_sfx[sfx_bfg]    = new Sfxinfo("bfg",    false, 64, null, -1, -1, 0);
        S_sfx[sfx_sawup]  = new Sfxinfo("sawup",  false, 64, null, -1, -1, 0);
        S_sfx[sfx_sawidl] = new Sfxinfo("sawidl", false, 118, null, -1, -1, 0);
        S_sfx[sfx_sawful] = new Sfxinfo("sawful", false, 64, null, -1, -1, 0);
        S_sfx[sfx_sawhit] = new Sfxinfo("sawhit", false, 64, null, -1, -1, 0);
        S_sfx[sfx_rlaunc] = new Sfxinfo("rlaunc", false, 64, null, -1, -1, 0);
        S_sfx[sfx_rxplod] = new Sfxinfo("rxplod", false, 70, null, -1, -1, 0);
        S_sfx[sfx_firsht] = new Sfxinfo("firsht", false, 70, null, -1, -1, 0);
        S_sfx[sfx_firxpl] = new Sfxinfo("firxpl", false, 70, null, -1, -1, 0);
        S_sfx[sfx_pstart] = new Sfxinfo("pstart", false, 100, null, -1, -1, 0);
        S_sfx[sfx_pstop]  = new Sfxinfo("pstop",  false, 100, null, -1, -1, 0);
        S_sfx[sfx_doropn] = new Sfxinfo("doropn", false, 100, null, -1, -1, 0);
        S_sfx[sfx_dorcls] = new Sfxinfo("dorcls", false, 100, null, -1, -1, 0);
        S_sfx[sfx_stnmov] = new Sfxinfo("stnmov", false, 119, null, -1, -1, 0);
        S_sfx[sfx_swtchn] = new Sfxinfo("swtchn", false, 78, null, -1, -1, 0);
        S_sfx[sfx_swtchx] = new Sfxinfo("swtchx", false, 78, null, -1, -1, 0);
        S_sfx[sfx_plpain] = new Sfxinfo("plpain", false, 96, null, -1, -1, 0);
        S_sfx[sfx_dmpain] = new Sfxinfo("dmpain", false, 96, null, -1, -1, 0);
        S_sfx[sfx_popain] = new Sfxinfo("popain", false, 96, null, -1, -1, 0);
        S_sfx[sfx_vipain] = new Sfxinfo("vipain", false, 96, null, -1, -1, 0);
        S_sfx[sfx_mnpain] = new Sfxinfo("mnpain", false, 96, null, -1, -1, 0);
        S_sfx[sfx_pepain] = new Sfxinfo("pepain", false, 96, null, -1, -1, 0);
        S_sfx[sfx_slop]   = new Sfxinfo("slop",   false, 78, null, -1, -1, 0);
        S_sfx[sfx_itemup] = new Sfxinfo("itemup", true,  78, null, -1, -1, 0);
        S_sfx[sfx_wpnup]  = new Sfxinfo("wpnup",  true,  78, null, -1, -1, 0);
        S_sfx[sfx_oof]    = new Sfxinfo("oof",    false, 96, null, -1, -1, 0);
        S_sfx[sfx_telept] = new Sfxinfo("telept", false, 32, null, -1, -1, 0);
        S_sfx[sfx_posit1] = new Sfxinfo("posit1", true,  98, null, -1, -1, 0);
        S_sfx[sfx_posit2] = new Sfxinfo("posit2", true,  98, null, -1, -1, 0);
        S_sfx[sfx_posit3] = new Sfxinfo("posit3", true,  98, null, -1, -1, 0);
        S_sfx[sfx_bgsit1] = new Sfxinfo("bgsit1", true,  98, null, -1, -1, 0);
        S_sfx[sfx_bgsit2] = new Sfxinfo("bgsit2", true,  98, null, -1, -1, 0);
        S_sfx[sfx_sgtsit] = new Sfxinfo("sgtsit", true,  98, null, -1, -1, 0);
        S_sfx[sfx_cacsit] = new Sfxinfo("cacsit", true,  98, null, -1, -1, 0);
        S_sfx[sfx_brssit] = new Sfxinfo("brssit", true,  94, null, -1, -1, 0);
        S_sfx[sfx_cybsit] = new Sfxinfo("cybsit", true,  92, null, -1, -1, 0);
        S_sfx[sfx_spisit] = new Sfxinfo("spisit", true,  90, null, -1, -1, 0);
        S_sfx[sfx_bspsit] = new Sfxinfo("bspsit", true,  90, null, -1, -1, 0);
        S_sfx[sfx_kntsit] = new Sfxinfo("kntsit", true,  90, null, -1, -1, 0);
        S_sfx[sfx_vilsit] = new Sfxinfo("vilsit", true,  90, null, -1, -1, 0);
        S_sfx[sfx_mansit] = new Sfxinfo("mansit", true,  90, null, -1, -1, 0);
        S_sfx[sfx_pesit]  = new Sfxinfo("pesit",  true,  90, null, -1, -1, 0);
        S_sfx[sfx_sklatk] = new Sfxinfo("sklatk", false, 70, null, -1, -1, 0);
        S_sfx[sfx_sgtatk] = new Sfxinfo("sgtatk", false, 70, null, -1, -1, 0);
        S_sfx[sfx_skepch] = new Sfxinfo("skepch", false, 70, null, -1, -1, 0);
        S_sfx[sfx_vilatk] = new Sfxinfo("vilatk", false, 70, null, -1, -1, 0);
        S_sfx[sfx_claw]   = new Sfxinfo("claw",   false, 70, null, -1, -1, 0);
        S_sfx[sfx_skeswg] = new Sfxinfo("skeswg", false, 70, null, -1, -1, 0);
        S_sfx[sfx_pldeth] = new Sfxinfo("pldeth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_pdiehi] = new Sfxinfo("pdiehi", false, 32, null, -1, -1, 0);
        S_sfx[sfx_podth1] = new Sfxinfo("podth1", false, 70, null, -1, -1, 0);
        S_sfx[sfx_podth2] = new Sfxinfo("podth2", false, 70, null, -1, -1, 0);
        S_sfx[sfx_podth3] = new Sfxinfo("podth3", false, 70, null, -1, -1, 0);
        S_sfx[sfx_bgdth1] = new Sfxinfo("bgdth1", false, 70, null, -1, -1, 0);
        S_sfx[sfx_bgdth2] = new Sfxinfo("bgdth2", false, 70, null, -1, -1, 0);
        S_sfx[sfx_sgtdth] = new Sfxinfo("sgtdth", false, 70, null, -1, -1, 0);
        S_sfx[sfx_cacdth] = new Sfxinfo("cacdth", false, 70, null, -1, -1, 0);
        S_sfx[sfx_skldth] = new Sfxinfo("skldth", false, 70, null, -1, -1, 0);
        S_sfx[sfx_brsdth] = new Sfxinfo("brsdth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_cybdth] = new Sfxinfo("cybdth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_spidth] = new Sfxinfo("spidth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_bspdth] = new Sfxinfo("bspdth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_vildth] = new Sfxinfo("vildth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_kntdth] = new Sfxinfo("kntdth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_pedth]  = new Sfxinfo("pedth",  false, 32, null, -1, -1, 0);
        S_sfx[sfx_skedth] = new Sfxinfo("skedth", false, 32, null, -1, -1, 0);
        S_sfx[sfx_posact] = new Sfxinfo("posact", true,  120, null, -1, -1, 0);
        S_sfx[sfx_bgact]  = new Sfxinfo("bgact",  true,  120, null, -1, -1, 0);
        S_sfx[sfx_dmact]  = new Sfxinfo("dmact",  true,  120, null, -1, -1, 0);
        S_sfx[sfx_bspact] = new Sfxinfo("bspact", true,  100, null, -1, -1, 0);
        S_sfx[sfx_bspwlk] = new Sfxinfo("bspwlk", true,  100, null, -1, -1, 0);
        S_sfx[sfx_vilact] = new Sfxinfo("vilact", true,  100, null, -1, -1, 0);
        S_sfx[sfx_noway]  = new Sfxinfo("noway",  false, 78, null, -1, -1, 0);
        S_sfx[sfx_barexp] = new Sfxinfo("barexp", false, 60, null, -1, -1, 0);
        S_sfx[sfx_punch]  = new Sfxinfo("punch",  false, 64, null, -1, -1, 0);
        S_sfx[sfx_hoof]   = new Sfxinfo("hoof",   false, 70, null, -1, -1, 0);
        S_sfx[sfx_metal]  = new Sfxinfo("metal",  false, 70, null, -1, -1, 0);
        // chgun links to pistol, pitch 150. C: { "chgun", false, 64, &S_sfx[sfx_pistol], 150, 0, 0 }.
        S_sfx[sfx_chgun]  = new Sfxinfo("chgun",  false, 64, S_sfx[sfx_pistol], 150, 0, 0);
        S_sfx[sfx_tink]   = new Sfxinfo("tink",   false, 60, null, -1, -1, 0);
        S_sfx[sfx_bdopn]  = new Sfxinfo("bdopn",  false, 100, null, -1, -1, 0);
        S_sfx[sfx_bdcls]  = new Sfxinfo("bdcls",  false, 100, null, -1, -1, 0);
        S_sfx[sfx_itmbk]  = new Sfxinfo("itmbk",  false, 100, null, -1, -1, 0);
        S_sfx[sfx_flame]  = new Sfxinfo("flame",  false, 32, null, -1, -1, 0);
        S_sfx[sfx_flamst] = new Sfxinfo("flamst", false, 32, null, -1, -1, 0);
        S_sfx[sfx_getpow] = new Sfxinfo("getpow", false, 60, null, -1, -1, 0);
        S_sfx[sfx_bospit] = new Sfxinfo("bospit", false, 70, null, -1, -1, 0);
        S_sfx[sfx_boscub] = new Sfxinfo("boscub", false, 70, null, -1, -1, 0);
        S_sfx[sfx_bossit] = new Sfxinfo("bossit", false, 70, null, -1, -1, 0);
        S_sfx[sfx_bospn]  = new Sfxinfo("bospn",  false, 70, null, -1, -1, 0);
        S_sfx[sfx_bosdth] = new Sfxinfo("bosdth", false, 70, null, -1, -1, 0);
        S_sfx[sfx_manatk] = new Sfxinfo("manatk", false, 70, null, -1, -1, 0);
        S_sfx[sfx_mandth] = new Sfxinfo("mandth", false, 70, null, -1, -1, 0);
        S_sfx[sfx_sssit]  = new Sfxinfo("sssit",  false, 70, null, -1, -1, 0);
        S_sfx[sfx_ssdth]  = new Sfxinfo("ssdth",  false, 70, null, -1, -1, 0);
        S_sfx[sfx_keenpn] = new Sfxinfo("keenpn", false, 70, null, -1, -1, 0);
        S_sfx[sfx_keendt] = new Sfxinfo("keendt", false, 70, null, -1, -1, 0);
        S_sfx[sfx_skeact] = new Sfxinfo("skeact", false, 70, null, -1, -1, 0);
        S_sfx[sfx_skesit] = new Sfxinfo("skesit", false, 70, null, -1, -1, 0);
        S_sfx[sfx_skeatk] = new Sfxinfo("skeatk", false, 70, null, -1, -1, 0);
        S_sfx[sfx_radio]  = new Sfxinfo("radio",  false, 60, null, -1, -1, 0);
    }

    private Sounds() {
    }
}
