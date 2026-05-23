// Port of linuxdoom-1.10/m_menu.c — full menu system.
//
// Eight menus in a tree: Main → {NewGame → Episode/Skill, Options
// → Sound, LoadGame, SaveGame, ReadThis 1+2, QuitDOOM}. Skull
// cursor animates two frames at ~8 tic intervals. The menu eats
// input via mResponder and paints via mDrawer.
//
// F-key shortcuts (active even with menu closed):
//   F1=help F2=save F3=load F4=sound F5=detail F6=quicksave
//   F7=endgame F8=messages F9=quickload F10=quit F11=gamma
//   +/- screen size
//
// Confirmation prompts (NIGHTMARE / ENDGAME / QSPROMPT / QLPROMPT)
// route through M_StartMessage which holds the prompt + dispatches
// the user's y/n press to a Java IntConsumer (the C function-pointer
// `messageRoutine`).
//
// Deferred: mouse / joystick navigation (only keyboard input is
// queued by the JavaFX shell), the screen-size slider's actual
// view-resize wiring (R_SetViewSize call kept inert until detail-
// level toggling is exposed via the menu).
package doom.ui;

import doom.play.Player;
import doom.system.MArgv;
import doom.render.Patch;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.DEvent;
import doom.system.DMain;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.Dstrings;
import doom.system.GGame;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

public final class MMenu {

    // m_menu.c lines 128–129.
    public static final int SKULLXOFF  = -32;
    public static final int LINEHEIGHT = 16;
    public static final int SAVESTRINGSIZE = 24;

    public static boolean menuactive;
    public static boolean inhelpscreens;

    // m_menu.c — defaulted values.
    public static int mouseSensitivity = 5;
    public static int detailLevel;
    public static int screenblocks = 10;
    public static int screenSize;
    public static int quickSaveSlot = -1;

    // Message popup state.
    public static int     messageToPrint;
    public static String  messageString;
    public static IntConsumer messageRoutine;
    public static boolean messageNeedsInput;
    public static boolean messageLastMenuActive;

    // Save-game string entry state.
    public static int    saveStringEnter;
    public static int    saveSlot;
    public static int    saveCharIndex;
    public static String saveOldString;
    public static final String[] savegamestrings = new String[10];

    static {
        for (int i = 0; i < 10; i++) savegamestrings[i] = Dstrings.EMPTYSTRING;
    }

    // m_menu.c lines 109–116 — gamma-level messages indexed by usegamma.
    public static final String[] gammamsg = {
            Dstrings.GAMMALVL0, Dstrings.GAMMALVL1, Dstrings.GAMMALVL2,
            Dstrings.GAMMALVL3, Dstrings.GAMMALVL4,
    };

    //
    // m_menu.c lines 140–167 — menuitem_t + menu_t.
    //
    public static final class MenuItem {
        public int status;       // 0=no cursor, 1=ok, 2=arrows ok
        public String name;      // patch name (or empty for blank line)
        public IntConsumer routine;
        public char alphaKey;

        public MenuItem(int status, String name, IntConsumer routine, char alphaKey) {
            this.status = status; this.name = name;
            this.routine = routine; this.alphaKey = alphaKey;
        }
    }

    public static final class Menu {
        public int numitems;
        public Menu prevMenu;
        public MenuItem[] menuitems;
        public Runnable routine;     // draw routine
        public int x, y;
        public int lastOn;
    }

    public static int itemOn;
    public static int skullAnimCounter;
    public static int whichSkull;
    public static final String[] skullName = { "M_SKULL1", "M_SKULL2" };
    public static Menu currentMenu;

    // Menu definitions — populated by static initialiser after the
    // menuitem arrays below.
    public static final Menu MainDef    = new Menu();
    public static final Menu EpiDef     = new Menu();
    public static final Menu NewDef     = new Menu();
    public static final Menu OptionsDef = new Menu();
    public static final Menu ReadDef1   = new Menu();
    public static final Menu ReadDef2   = new Menu();
    public static final Menu SoundDef   = new Menu();
    public static final Menu LoadDef    = new Menu();
    public static final Menu SaveDef    = new Menu();

    // m_menu.c lines 250–268 — Main menu.
    public static final int newgame_idx  = 0;
    public static final int options_idx  = 1;
    public static final int loadgame_idx = 2;
    public static final int savegame_idx = 3;
    public static final int readthis_idx = 4;
    public static final int quitdoom_idx = 5;
    public static final int main_end     = 6;
    public static final MenuItem[] MainMenu = {
            new MenuItem(1, "M_NGAME",  MMenu::mNewGame,  'n'),
            new MenuItem(1, "M_OPTION", MMenu::mOptions,  'o'),
            new MenuItem(1, "M_LOADG",  MMenu::mLoadGame, 'l'),
            new MenuItem(1, "M_SAVEG",  MMenu::mSaveGame, 's'),
            new MenuItem(1, "M_RDTHIS", MMenu::mReadThis, 'r'),
            new MenuItem(1, "M_QUITG",  MMenu::mQuitDOOM, 'q'),
    };

    // m_menu.c lines 284–300 — Episode menu.
    public static final int ep_end = 4;
    public static final MenuItem[] EpisodeMenu = {
            new MenuItem(1, "M_EPI1", MMenu::mEpisode, 'k'),
            new MenuItem(1, "M_EPI2", MMenu::mEpisode, 't'),
            new MenuItem(1, "M_EPI3", MMenu::mEpisode, 'i'),
            new MenuItem(1, "M_EPI4", MMenu::mEpisode, 't'),
    };

    // m_menu.c lines 315–332 — New Game (skill) menu.
    public static final int killthings = 0;
    public static final int toorough   = 1;
    public static final int hurtme     = 2;
    public static final int violence   = 3;
    public static final int nightmare  = 4;
    public static final int newg_end   = 5;
    public static final MenuItem[] NewGameMenu = {
            new MenuItem(1, "M_JKILL", MMenu::mChooseSkill, 'i'),
            new MenuItem(1, "M_ROUGH", MMenu::mChooseSkill, 'h'),
            new MenuItem(1, "M_HURT",  MMenu::mChooseSkill, 'h'),
            new MenuItem(1, "M_ULTRA", MMenu::mChooseSkill, 'u'),
            new MenuItem(1, "M_NMARE", MMenu::mChooseSkill, 'n'),
    };

    // m_menu.c lines 339–372 — Options menu.
    public static final int endgame_idx   = 0;
    public static final int messages_idx  = 1;
    public static final int detail_idx    = 2;
    public static final int scrnsize_idx  = 3;
    public static final int option_empty1 = 4;
    public static final int mousesens_idx = 5;
    public static final int option_empty2 = 6;
    public static final int soundvol_idx  = 7;
    public static final int opt_end       = 8;
    public static final MenuItem[] OptionsMenu = {
            new MenuItem( 1, "M_ENDGAM", MMenu::mEndGame,           'e'),
            new MenuItem( 1, "M_MESSG",  MMenu::mChangeMessages,    'm'),
            new MenuItem( 1, "M_DETAIL", MMenu::mChangeDetail,      'g'),
            new MenuItem( 2, "M_SCRNSZ", MMenu::mSizeDisplay,       's'),
            new MenuItem(-1, "",         null,                       '\0'),
            new MenuItem( 2, "M_MSENS",  MMenu::mChangeSensitivity, 'm'),
            new MenuItem(-1, "",         null,                       '\0'),
            new MenuItem( 1, "M_SVOL",   MMenu::mSound,             's'),
    };

    public static final int read1_end = 1;
    public static final MenuItem[] ReadMenu1 = {
            new MenuItem(1, "", MMenu::mReadThis2, '\0'),
    };
    public static final int read2_end = 1;
    public static final MenuItem[] ReadMenu2 = {
            new MenuItem(1, "", MMenu::mFinishReadThis, '\0'),
    };

    public static final int sfx_vol_idx   = 0;
    public static final int sfx_empty1    = 1;
    public static final int music_vol_idx = 2;
    public static final int sfx_empty2    = 3;
    public static final int sound_end     = 4;
    public static final MenuItem[] SoundMenu = {
            new MenuItem( 2, "M_SFXVOL", MMenu::mSfxVol,   's'),
            new MenuItem(-1, "",         null,              '\0'),
            new MenuItem( 2, "M_MUSVOL", MMenu::mMusicVol, 'm'),
            new MenuItem(-1, "",         null,              '\0'),
    };

    public static final int load_end = 6;
    public static final MenuItem[] LoadMenu = {
            new MenuItem(1, "", MMenu::mLoadSelect, '1'),
            new MenuItem(1, "", MMenu::mLoadSelect, '2'),
            new MenuItem(1, "", MMenu::mLoadSelect, '3'),
            new MenuItem(1, "", MMenu::mLoadSelect, '4'),
            new MenuItem(1, "", MMenu::mLoadSelect, '5'),
            new MenuItem(1, "", MMenu::mLoadSelect, '6'),
    };
    public static final MenuItem[] SaveMenu = {
            new MenuItem(1, "", MMenu::mSaveSelect, '1'),
            new MenuItem(1, "", MMenu::mSaveSelect, '2'),
            new MenuItem(1, "", MMenu::mSaveSelect, '3'),
            new MenuItem(1, "", MMenu::mSaveSelect, '4'),
            new MenuItem(1, "", MMenu::mSaveSelect, '5'),
            new MenuItem(1, "", MMenu::mSaveSelect, '6'),
    };

    static {
        MainDef.numitems  = main_end;   MainDef.prevMenu = null;
        MainDef.menuitems = MainMenu;   MainDef.routine  = MMenu::mDrawMainMenu;
        MainDef.x = 97; MainDef.y = 64; MainDef.lastOn = 0;

        EpiDef.numitems  = ep_end;     EpiDef.prevMenu = MainDef;
        EpiDef.menuitems = EpisodeMenu; EpiDef.routine  = MMenu::mDrawEpisode;
        EpiDef.x = 48; EpiDef.y = 63;  EpiDef.lastOn = 0;

        NewDef.numitems  = newg_end;    NewDef.prevMenu = EpiDef;
        NewDef.menuitems = NewGameMenu; NewDef.routine  = MMenu::mDrawNewGame;
        NewDef.x = 48; NewDef.y = 63;   NewDef.lastOn = hurtme;

        OptionsDef.numitems  = opt_end;     OptionsDef.prevMenu = MainDef;
        OptionsDef.menuitems = OptionsMenu; OptionsDef.routine  = MMenu::mDrawOptions;
        OptionsDef.x = 60; OptionsDef.y = 37; OptionsDef.lastOn = 0;

        ReadDef1.numitems  = read1_end;  ReadDef1.prevMenu = MainDef;
        ReadDef1.menuitems = ReadMenu1;  ReadDef1.routine  = MMenu::mDrawReadThis1;
        ReadDef1.x = 280; ReadDef1.y = 185; ReadDef1.lastOn = 0;

        ReadDef2.numitems  = read2_end;  ReadDef2.prevMenu = ReadDef1;
        ReadDef2.menuitems = ReadMenu2;  ReadDef2.routine  = MMenu::mDrawReadThis2;
        ReadDef2.x = 330; ReadDef2.y = 175; ReadDef2.lastOn = 0;

        SoundDef.numitems  = sound_end;  SoundDef.prevMenu = OptionsDef;
        SoundDef.menuitems = SoundMenu;  SoundDef.routine  = MMenu::mDrawSound;
        SoundDef.x = 80; SoundDef.y = 64; SoundDef.lastOn = 0;

        LoadDef.numitems  = load_end;    LoadDef.prevMenu = MainDef;
        LoadDef.menuitems = LoadMenu;    LoadDef.routine  = MMenu::mDrawLoad;
        LoadDef.x = 80; LoadDef.y = 54;  LoadDef.lastOn = 0;

        SaveDef.numitems  = load_end;    SaveDef.prevMenu = MainDef;
        SaveDef.menuitems = SaveMenu;    SaveDef.routine  = MMenu::mDrawSave;
        SaveDef.x = 80; SaveDef.y = 54;  SaveDef.lastOn = 0;
    }

    private static int epi;

    // m_menu.c lines 947–948 — Options icon names.
    public static final String[] detailNames = { "M_GDHIGH", "M_GDLOW" };
    public static final String[] msgNames    = { "M_MSGOFF", "M_MSGON" };

    private MMenu() {
    }

    //
    // M_ReadSaveStrings
    // m_menu.c lines 511–536.
    public static void mReadSaveStrings() {
        for (int i = 0; i < load_end; i++) {
            String path = "doomsav" + i + ".dsg";
            String desc = GGame.gReadSaveFileDescription(path);
            if (desc == null) {
                savegamestrings[i] = Dstrings.EMPTYSTRING;
                LoadMenu[i].status = 0;
            } else {
                savegamestrings[i] = desc;
                LoadMenu[i].status = 1;
            }
        }
    }

    //
    // M_DrawLoad / M_DrawSave / M_DrawSaveLoadBorder
    // m_menu.c lines 542–626.
    public static void mDrawLoad() {
        try {
            VVideo.vDrawPatchDirect(72, 28, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_LOADG", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_LOADG: %s", e.getMessage()); }
        for (int i = 0; i < load_end; i++) {
            mDrawSaveLoadBorder(LoadDef.x, LoadDef.y + LINEHEIGHT * i);
            mWriteText(LoadDef.x, LoadDef.y + LINEHEIGHT * i, savegamestrings[i]);
        }
    }

    public static void mDrawSaveLoadBorder(int x, int y) {
        try {
            VVideo.vDrawPatchDirect(x - 8, y + 7, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_LSLEFT", ZZone.PU_CACHE)));
            Patch ctr = Patch.fromLump(WWad.wCacheLumpName("M_LSCNTR", ZZone.PU_CACHE));
            for (int i = 0; i < 24; i++) {
                VVideo.vDrawPatchDirect(x, y + 7, 0, ctr);
                x += 8;
            }
            VVideo.vDrawPatchDirect(x, y + 7, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_LSRGHT", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_LSL/M: %s", e.getMessage()); }
    }

    public static void mLoadSelect(int choice) {
        GGame.gLoadGame("doomsav" + choice + ".dsg");
        mClearMenus();
    }

    public static void mLoadGame(int choice) {
        if (Doomstat.netgame) {
            mStartMessage(Dstrings.LOADNET, null, false);
            return;
        }
        mSetupNextMenu(LoadDef);
        mReadSaveStrings();
    }

    public static void mDrawSave() {
        try {
            VVideo.vDrawPatchDirect(72, 28, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_SAVEG", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_SAVEG: %s", e.getMessage()); }
        for (int i = 0; i < load_end; i++) {
            mDrawSaveLoadBorder(LoadDef.x, LoadDef.y + LINEHEIGHT * i);
            mWriteText(LoadDef.x, LoadDef.y + LINEHEIGHT * i, savegamestrings[i]);
        }
        if (saveStringEnter != 0) {
            int i = mStringWidth(savegamestrings[saveSlot]);
            mWriteText(LoadDef.x + i, LoadDef.y + LINEHEIGHT * saveSlot, "_");
        }
    }

    public static void mDoSave(int slot) {
        GGame.gSaveGame(slot, savegamestrings[slot]);
        mClearMenus();
        if (quickSaveSlot == -2) quickSaveSlot = slot;
    }

    public static void mSaveSelect(int choice) {
        saveStringEnter = 1;
        saveSlot = choice;
        saveOldString = savegamestrings[choice];
        if (Dstrings.EMPTYSTRING.equals(savegamestrings[choice])) {
            savegamestrings[choice] = "";
        }
        saveCharIndex = savegamestrings[choice].length();
    }

    public static void mSaveGame(int choice) {
        if (!Doomstat.usergame) {
            mStartMessage(Dstrings.SAVEDEAD, null, false);
            return;
        }
        if (Doomstat.gamestate != Doomstat.GS_LEVEL) return;
        mSetupNextMenu(SaveDef);
        mReadSaveStrings();
    }

    // Quicksave / Quickload — m_menu.c lines 679–743.
    public static String tempstring = "";

    public static void mQuickSaveResponse(int ch) {
        if (ch == 'y') {
            mDoSave(quickSaveSlot);
            SSound.sStartSound(null, Sounds.sfx_swtchx);
        }
    }

    public static void mQuickSave() {
        if (!Doomstat.usergame) {
            SSound.sStartSound(null, Sounds.sfx_oof);
            return;
        }
        if (Doomstat.gamestate != Doomstat.GS_LEVEL) return;
        if (quickSaveSlot < 0) {
            mStartControlPanel();
            mReadSaveStrings();
            mSetupNextMenu(SaveDef);
            quickSaveSlot = -2;
            return;
        }
        tempstring = String.format(Dstrings.QSPROMPT, savegamestrings[quickSaveSlot]);
        mStartMessage(tempstring, MMenu::mQuickSaveResponse, true);
    }

    public static void mQuickLoadResponse(int ch) {
        if (ch == 'y') {
            mLoadSelect(quickSaveSlot);
            SSound.sStartSound(null, Sounds.sfx_swtchx);
        }
    }

    public static void mQuickLoad() {
        if (Doomstat.netgame) {
            mStartMessage(Dstrings.QLOADNET, null, false);
            return;
        }
        if (quickSaveSlot < 0) {
            mStartMessage(Dstrings.QSAVESPOT, null, false);
            return;
        }
        tempstring = String.format(Dstrings.QLPROMPT, savegamestrings[quickSaveSlot]);
        mStartMessage(tempstring, MMenu::mQuickLoadResponse, true);
    }

    // ReadThis pages — m_menu.c lines 752–794.
    public static void mDrawReadThis1() {
        inhelpscreens = true;
        try {
            String lump = (Doomstat.gamemode == Doomstat.GameMode.commercial)
                    ? "HELP" : "HELP1";
            VVideo.vDrawPatchDirect(0, 0, 0,
                    Patch.fromLump(WWad.wCacheLumpName(lump, ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("HELP: %s", e.getMessage()); }
    }

    public static void mDrawReadThis2() {
        inhelpscreens = true;
        try {
            String lump = (Doomstat.gamemode == Doomstat.GameMode.retail
                    || Doomstat.gamemode == Doomstat.GameMode.commercial)
                    ? "CREDIT" : "HELP2";
            VVideo.vDrawPatchDirect(0, 0, 0,
                    Patch.fromLump(WWad.wCacheLumpName(lump, ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("HELP2: %s", e.getMessage()); }
    }

    // Sound volume submenu — m_menu.c lines 800–848.
    public static void mDrawSound() {
        try {
            VVideo.vDrawPatchDirect(60, 38, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_SVOL", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_SVOL: %s", e.getMessage()); }
        mDrawThermo(SoundDef.x, SoundDef.y + LINEHEIGHT * (sfx_vol_idx + 1),
                16, SSound.snd_SfxVolume);
        mDrawThermo(SoundDef.x, SoundDef.y + LINEHEIGHT * (music_vol_idx + 1),
                16, SSound.snd_MusicVolume);
    }

    public static void mSound(int choice) { mSetupNextMenu(SoundDef); }

    public static void mSfxVol(int choice) {
        switch (choice) {
            case 0: if (SSound.snd_SfxVolume > 0)  SSound.snd_SfxVolume--; break;
            case 1: if (SSound.snd_SfxVolume < 15) SSound.snd_SfxVolume++; break;
            default: break;
        }
        SSound.sSetSfxVolume(SSound.snd_SfxVolume);
    }

    public static void mMusicVol(int choice) {
        switch (choice) {
            case 0: if (SSound.snd_MusicVolume > 0)  SSound.snd_MusicVolume--; break;
            case 1: if (SSound.snd_MusicVolume < 15) SSound.snd_MusicVolume++; break;
            default: break;
        }
        SSound.sSetMusicVolume(SSound.snd_MusicVolume);
    }

    // Main + NewGame + Episode + Options menus — m_menu.c lines
    // 856–971. Draw routines + click handlers.
    public static void mDrawMainMenu() {
        try {
            VVideo.vDrawPatchDirect(94, 2, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_DOOM", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_DOOM: %s", e.getMessage()); }
    }

    public static void mDrawNewGame() {
        try {
            VVideo.vDrawPatchDirect(96, 14, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_NEWG", ZZone.PU_CACHE)));
            VVideo.vDrawPatchDirect(54, 38, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_SKILL", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_NEWG: %s", e.getMessage()); }
    }

    public static void mNewGame(int choice) {
        if (Doomstat.netgame && !Doomstat.demoplayback) {
            mStartMessage(Dstrings.NEWGAME, null, false);
            return;
        }
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            mSetupNextMenu(NewDef);
        } else {
            mSetupNextMenu(EpiDef);
        }
    }

    public static void mDrawEpisode() {
        try {
            VVideo.vDrawPatchDirect(54, 38, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_EPISOD", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_EPISOD: %s", e.getMessage()); }
    }

    public static void mVerifyNightmare(int ch) {
        if (ch != 'y') return;
        GGame.gDeferedInitNew(Doomstat.sk_nightmare, epi + 1, 1);
        mClearMenus();
    }

    public static void mChooseSkill(int choice) {
        if (choice == nightmare) {
            mStartMessage(Dstrings.NIGHTMARE, MMenu::mVerifyNightmare, true);
            return;
        }
        GGame.gDeferedInitNew(choice, epi + 1, 1);
        mClearMenus();
    }

    public static void mEpisode(int choice) {
        if (Doomstat.gamemode == Doomstat.GameMode.shareware && choice != 0) {
            mStartMessage(Dstrings.SWSTRING, null, false);
            mSetupNextMenu(ReadDef1);
            return;
        }
        if (Doomstat.gamemode == Doomstat.GameMode.registered && choice > 2) {
            // UltimateDOOM-only episode → force back to E1.
            choice = 0;
        }
        epi = choice;
        mSetupNextMenu(NewDef);
    }

    public static void mDrawOptions() {
        try {
            VVideo.vDrawPatchDirect(108, 15, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_OPTTTL", ZZone.PU_CACHE)));
            VVideo.vDrawPatchDirect(OptionsDef.x + 175,
                    OptionsDef.y + LINEHEIGHT * detail_idx, 0,
                    Patch.fromLump(WWad.wCacheLumpName(
                            detailNames[detailLevel], ZZone.PU_CACHE)));
            VVideo.vDrawPatchDirect(OptionsDef.x + 120,
                    OptionsDef.y + LINEHEIGHT * messages_idx, 0,
                    Patch.fromLump(WWad.wCacheLumpName(
                            msgNames[HuStuff.showMessages ? 1 : 0], ZZone.PU_CACHE)));
            mDrawThermo(OptionsDef.x, OptionsDef.y + LINEHEIGHT * (mousesens_idx + 1),
                    10, mouseSensitivity);
            mDrawThermo(OptionsDef.x, OptionsDef.y + LINEHEIGHT * (scrnsize_idx + 1),
                    9, screenSize);
        } catch (IOException e) { throw ISystem.iError("M_OPT: %s", e.getMessage()); }
    }

    public static void mOptions(int choice) { mSetupNextMenu(OptionsDef); }

    public static void mChangeMessages(int choice) {
        HuStuff.showMessages = !HuStuff.showMessages;
        GGame.players[GGame.consoleplayer].message =
                HuStuff.showMessages ? Dstrings.MSGON : Dstrings.MSGOFF;
        HuStuff.messageDontfuckwithme = true;
    }

    public static void mEndGameResponse(int ch) {
        if (ch != 'y') return;
        currentMenu.lastOn = itemOn;
        mClearMenus();
        // Bounce back to the title-screen demo loop.
        DMain.dStartTitle();
    }

    public static void mEndGame(int choice) {
        if (!Doomstat.usergame) {
            SSound.sStartSound(null, Sounds.sfx_oof);
            return;
        }
        if (Doomstat.netgame) {
            mStartMessage(Dstrings.NETEND, null, false);
            return;
        }
        mStartMessage(Dstrings.ENDGAME, MMenu::mEndGameResponse, true);
    }

    public static void mReadThis(int choice)       { mSetupNextMenu(ReadDef1); }
    public static void mReadThis2(int choice)      { mSetupNextMenu(ReadDef2); }
    public static void mFinishReadThis(int choice) { mSetupNextMenu(MainDef); }

    // m_menu.c lines 1054–1108 — quit-DOOM with random snippet.
    public static final int[] quitsounds = {
            Sounds.sfx_pldeth, Sounds.sfx_dmpain, Sounds.sfx_popain,
            Sounds.sfx_slop,   Sounds.sfx_telept, Sounds.sfx_posit1,
            Sounds.sfx_posit3, Sounds.sfx_sgtatk,
    };
    public static final int[] quitsounds2 = {
            Sounds.sfx_vilact, Sounds.sfx_getpow, Sounds.sfx_boscub,
            Sounds.sfx_slop,   Sounds.sfx_skeswg, Sounds.sfx_kntdth,
            Sounds.sfx_bspact, Sounds.sfx_sgtatk,
    };

    public static void mQuitResponse(int ch) {
        if (ch != 'y') return;
        if (!Doomstat.netgame) {
            if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                SSound.sStartSound(null, quitsounds2[(doom.play.PTick.gametic >> 2) & 7]);
            } else {
                SSound.sStartSound(null, quitsounds[(doom.play.PTick.gametic >> 2) & 7]);
            }
        }
        ISystem.iQuit();
    }

    public static void mQuitDOOM(int choice) {
        int slot = (doom.play.PTick.gametic % (Dstrings.NUM_QUITMESSAGES - 2)) + 1;
        String msg = Dstrings.endmsg[slot];
        if (msg == null) msg = Dstrings.endmsg[0];
        mStartMessage(msg + "\n\n" + Dstrings.DOSY, MMenu::mQuitResponse, true);
    }

    public static void mChangeSensitivity(int choice) {
        switch (choice) {
            case 0: if (mouseSensitivity > 0) mouseSensitivity--; break;
            case 1: if (mouseSensitivity < 9) mouseSensitivity++; break;
            default: break;
        }
    }

    public static void mChangeDetail(int choice) {
        detailLevel = 1 - detailLevel;
        // C's M_ChangeDetail had this branch commented out — DOS
        // builds dropped low-detail support. RMain.rSetViewSize +
        // detailshift in the Java port already drive
        // rDrawColumnLow / rDrawSpanLow, so light it up.
        doom.render.RMain.rSetViewSize(screenblocks, detailLevel);
    }

    public static void mSizeDisplay(int choice) {
        switch (choice) {
            case 0:
                if (screenSize > 0) { screenblocks--; screenSize--; }
                break;
            case 1:
                if (screenSize < 8) { screenblocks++; screenSize++; }
                break;
            default: break;
        }
        // Defer the actual resize through setsizeneeded so it
        // lands at the next dDisplay (matches the C — direct
        // mutation from menu callbacks can race the in-flight
        // renderer otherwise).
        doom.render.RMain.rSetViewSize(screenblocks, detailLevel);
    }

    // Menu painting primitives — m_menu.c lines 1183–1338.
    public static void mDrawThermo(int x, int y, int thermWidth, int thermDot) {
        try {
            int xx = x;
            VVideo.vDrawPatchDirect(xx, y, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_THERML", ZZone.PU_CACHE)));
            xx += 8;
            Patch midPatch = Patch.fromLump(WWad.wCacheLumpName("M_THERMM", ZZone.PU_CACHE));
            for (int i = 0; i < thermWidth; i++) {
                VVideo.vDrawPatchDirect(xx, y, 0, midPatch);
                xx += 8;
            }
            VVideo.vDrawPatchDirect(xx, y, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_THERMR", ZZone.PU_CACHE)));
            VVideo.vDrawPatchDirect((x + 8) + thermDot * 8, y, 0,
                    Patch.fromLump(WWad.wCacheLumpName("M_THERMO", ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_THERMO: %s", e.getMessage()); }
    }

    public static void mStartMessage(String string, IntConsumer routine, boolean input) {
        messageLastMenuActive = menuactive;
        messageToPrint = 1;
        messageString = string;
        messageRoutine = routine;
        messageNeedsInput = input;
        menuactive = true;
    }

    public static void mStopMessage() {
        menuactive = messageLastMenuActive;
        messageToPrint = 0;
    }

    public static int mStringWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = Character.toUpperCase(s.charAt(i)) - HuStuff.HU_FONTSTART;
            if (c < 0 || c >= HuStuff.HU_FONTSIZE || HuStuff.huFont[c] == null) {
                w += 4;
            } else {
                w += HuStuff.huFont[c].width;
            }
        }
        return w;
    }

    public static int mStringHeight(String s) {
        int height = (HuStuff.huFont[0] != null) ? HuStuff.huFont[0].height : 8;
        int h = height;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') h += height;
        }
        return h;
    }

    public static void mWriteText(int x, int y, String s) {
        int cx = x;
        int cy = y;
        for (int idx = 0; idx < s.length(); idx++) {
            char ch = s.charAt(idx);
            if (ch == '\n') { cx = x; cy += 12; continue; }
            int c = Character.toUpperCase(ch) - HuStuff.HU_FONTSTART;
            if (c < 0 || c >= HuStuff.HU_FONTSIZE || HuStuff.huFont[c] == null) {
                cx += 4;
                continue;
            }
            int w = HuStuff.huFont[c].width;
            if (cx + w > Doomdef.SCREENWIDTH) break;
            VVideo.vDrawPatchDirect(cx, cy, 0, HuStuff.huFont[c]);
            cx += w;
        }
    }

    //
    // M_Responder
    // m_menu.c lines 1349–1716.
    public static boolean mResponder(DEvent ev) {
        int ch = -1;
        if (ev.type == DEvent.EV_KEYDOWN) {
            ch = ev.data1;
        }
        if (ch == -1) return false;

        // Save Game string input.
        if (saveStringEnter != 0) {
            switch (ch) {
                case Doomdef.KEY_BACKSPACE:
                    if (saveCharIndex > 0) {
                        saveCharIndex--;
                        savegamestrings[saveSlot] =
                                savegamestrings[saveSlot].substring(0, saveCharIndex);
                    }
                    break;
                case Doomdef.KEY_ESCAPE:
                    saveStringEnter = 0;
                    savegamestrings[saveSlot] = saveOldString;
                    break;
                case Doomdef.KEY_ENTER:
                    saveStringEnter = 0;
                    if (!savegamestrings[saveSlot].isEmpty()) {
                        mDoSave(saveSlot);
                    }
                    break;
                default: {
                    int c = Character.toUpperCase((char) ch);
                    if (c != 32) {
                        if (c - HuStuff.HU_FONTSTART < 0
                                || c - HuStuff.HU_FONTSTART >= HuStuff.HU_FONTSIZE) {
                            break;
                        }
                    }
                    if (c >= 32 && c <= 127
                            && saveCharIndex < SAVESTRINGSIZE - 1
                            && mStringWidth(savegamestrings[saveSlot])
                                    < (SAVESTRINGSIZE - 2) * 8) {
                        savegamestrings[saveSlot] += (char) c;
                        saveCharIndex++;
                    }
                    break;
                }
            }
            return true;
        }

        // Messages that need input.
        if (messageToPrint != 0) {
            if (messageNeedsInput
                    && !(ch == ' ' || ch == 'n' || ch == 'y'
                            || ch == Doomdef.KEY_ESCAPE)) {
                return false;
            }
            menuactive = messageLastMenuActive;
            messageToPrint = 0;
            if (messageRoutine != null) {
                messageRoutine.accept(ch);
            }
            menuactive = false;
            SSound.sStartSound(null, Sounds.sfx_swtchx);
            return true;
        }

        if (DMain.devparm && ch == Doomdef.KEY_F1) {
            GGame.gScreenShot();
            return true;
        }

        // F-Keys (only when no menu is showing).
        if (!menuactive) {
            switch (ch) {
                case Doomdef.KEY_MINUS:
                    if (AmMap.automapactive) return false;
                    mSizeDisplay(0);
                    SSound.sStartSound(null, Sounds.sfx_stnmov);
                    return true;
                case Doomdef.KEY_EQUALS:
                    if (AmMap.automapactive) return false;
                    mSizeDisplay(1);
                    SSound.sStartSound(null, Sounds.sfx_stnmov);
                    return true;
                case Doomdef.KEY_F1:
                    mStartControlPanel();
                    if (Doomstat.gamemode == Doomstat.GameMode.retail) {
                        currentMenu = ReadDef2;
                    } else {
                        currentMenu = ReadDef1;
                    }
                    itemOn = 0;
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    return true;
                case Doomdef.KEY_F2:
                    mStartControlPanel();
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    mSaveGame(0);
                    return true;
                case Doomdef.KEY_F3:
                    mStartControlPanel();
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    mLoadGame(0);
                    return true;
                case Doomdef.KEY_F4:
                    mStartControlPanel();
                    currentMenu = SoundDef;
                    itemOn = sfx_vol_idx;
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    return true;
                case Doomdef.KEY_F5:
                    mChangeDetail(0);
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    return true;
                case Doomdef.KEY_F6:
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    mQuickSave();
                    return true;
                case Doomdef.KEY_F7:
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    mEndGame(0);
                    return true;
                case Doomdef.KEY_F8:
                    mChangeMessages(0);
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    return true;
                case Doomdef.KEY_F9:
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    mQuickLoad();
                    return true;
                case Doomdef.KEY_F10:
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                    mQuitDOOM(0);
                    return true;
                case Doomdef.KEY_F11:
                    VVideo.usegamma = (VVideo.usegamma + 1) % 5;
                    GGame.players[GGame.consoleplayer].message = gammamsg[VVideo.usegamma];
                    try {
                        IVideo.iSetPalette(WWad.wCacheLumpName("PLAYPAL", ZZone.PU_CACHE));
                    } catch (IOException | ISystem.DoomError e) {
                        // best-effort — silently skip the palette
                        // upload if PLAYPAL isn't available (test
                        // harness without a WAD).
                    }
                    return true;
                default:
                    break;
            }
        }

        // Pop up the menu if closed and ESC is pressed. ENTER is
        // accepted too — a small port deviation from the C (which
        // only honours ESC here) so the UX matches what most
        // users expect from later Doom builds / source ports.
        if (!menuactive) {
            if (ch == Doomdef.KEY_ESCAPE || ch == Doomdef.KEY_ENTER) {
                mStartControlPanel();
                SSound.sStartSound(null, Sounds.sfx_swtchn);
                return true;
            }
            return false;
        }

        // Keys usable within the menu.
        switch (ch) {
            case Doomdef.KEY_DOWNARROW: {
                do {
                    if (itemOn + 1 > currentMenu.numitems - 1) itemOn = 0;
                    else itemOn++;
                    SSound.sStartSound(null, Sounds.sfx_pstop);
                } while (currentMenu.menuitems[itemOn].status == -1);
                return true;
            }
            case Doomdef.KEY_UPARROW: {
                do {
                    if (itemOn == 0) itemOn = currentMenu.numitems - 1;
                    else itemOn--;
                    SSound.sStartSound(null, Sounds.sfx_pstop);
                } while (currentMenu.menuitems[itemOn].status == -1);
                return true;
            }
            case Doomdef.KEY_LEFTARROW:
                if (currentMenu.menuitems[itemOn].routine != null
                        && currentMenu.menuitems[itemOn].status == 2) {
                    SSound.sStartSound(null, Sounds.sfx_stnmov);
                    currentMenu.menuitems[itemOn].routine.accept(0);
                }
                return true;
            case Doomdef.KEY_RIGHTARROW:
                if (currentMenu.menuitems[itemOn].routine != null
                        && currentMenu.menuitems[itemOn].status == 2) {
                    SSound.sStartSound(null, Sounds.sfx_stnmov);
                    currentMenu.menuitems[itemOn].routine.accept(1);
                }
                return true;
            case Doomdef.KEY_ENTER:
                if (currentMenu.menuitems[itemOn].routine != null
                        && currentMenu.menuitems[itemOn].status != 0) {
                    currentMenu.lastOn = itemOn;
                    if (currentMenu.menuitems[itemOn].status == 2) {
                        currentMenu.menuitems[itemOn].routine.accept(1);
                        SSound.sStartSound(null, Sounds.sfx_stnmov);
                    } else {
                        currentMenu.menuitems[itemOn].routine.accept(itemOn);
                        SSound.sStartSound(null, Sounds.sfx_pistol);
                    }
                }
                return true;
            case Doomdef.KEY_ESCAPE:
                currentMenu.lastOn = itemOn;
                mClearMenus();
                SSound.sStartSound(null, Sounds.sfx_swtchx);
                return true;
            case Doomdef.KEY_BACKSPACE:
                currentMenu.lastOn = itemOn;
                if (currentMenu.prevMenu != null) {
                    currentMenu = currentMenu.prevMenu;
                    itemOn = currentMenu.lastOn;
                    SSound.sStartSound(null, Sounds.sfx_swtchn);
                }
                return true;
            default: {
                for (int i = itemOn + 1; i < currentMenu.numitems; i++) {
                    if (currentMenu.menuitems[i].alphaKey == ch) {
                        itemOn = i;
                        SSound.sStartSound(null, Sounds.sfx_pstop);
                        return true;
                    }
                }
                for (int i = 0; i <= itemOn; i++) {
                    if (currentMenu.menuitems[i].alphaKey == ch) {
                        itemOn = i;
                        SSound.sStartSound(null, Sounds.sfx_pstop);
                        return true;
                    }
                }
                break;
            }
        }
        return false;
    }

    //
    // M_StartControlPanel
    // m_menu.c lines 1722–1732.
    public static void mStartControlPanel() {
        if (menuactive) return;
        menuactive = true;
        currentMenu = MainDef;
        itemOn = currentMenu.lastOn;
    }

    //
    // M_Drawer
    // m_menu.c lines 1740–1805.
    public static void mDrawer() {
        inhelpscreens = false;

        if (messageToPrint != 0) {
            int start = 0;
            int y = 100 - mStringHeight(messageString) / 2;
            while (start < messageString.length()) {
                int nl = messageString.indexOf('\n', start);
                String line;
                if (nl < 0) {
                    line = messageString.substring(start);
                    start = messageString.length();
                } else {
                    line = messageString.substring(start, nl);
                    start = nl + 1;
                }
                int x = 160 - mStringWidth(line) / 2;
                mWriteText(x, y, line);
                y += (HuStuff.huFont[0] != null) ? HuStuff.huFont[0].height : 8;
            }
            return;
        }

        if (!menuactive) return;

        if (currentMenu.routine != null) {
            currentMenu.routine.run();
        }

        int x = currentMenu.x;
        int y = currentMenu.y;
        int max = currentMenu.numitems;

        try {
            for (int i = 0; i < max; i++) {
                if (!currentMenu.menuitems[i].name.isEmpty()) {
                    VVideo.vDrawPatchDirect(x, y, 0,
                            Patch.fromLump(WWad.wCacheLumpName(
                                    currentMenu.menuitems[i].name, ZZone.PU_CACHE)));
                }
                y += LINEHEIGHT;
            }
            VVideo.vDrawPatchDirect(x + SKULLXOFF,
                    currentMenu.y - 5 + itemOn * LINEHEIGHT, 0,
                    Patch.fromLump(WWad.wCacheLumpName(
                            skullName[whichSkull], ZZone.PU_CACHE)));
        } catch (IOException e) { throw ISystem.iError("M_Drawer: %s", e.getMessage()); }
    }

    //
    // M_ClearMenus / M_SetupNextMenu / M_Ticker / M_Init
    // m_menu.c lines 1811–1892.
    public static void mClearMenus() {
        menuactive = false;
    }

    public static void mSetupNextMenu(Menu m) {
        currentMenu = m;
        itemOn = currentMenu.lastOn;
    }

    public static void mTicker() {
        if (--skullAnimCounter <= 0) {
            whichSkull ^= 1;
            skullAnimCounter = 8;
        }
    }

    public static void mInit() {
        // Reset per-menu cursor positions to their initial values.
        // The C process restarts from scratch each run, so the
        // static struct initialisers are equivalent to a reset;
        // in the JavaFX port the JVM survives across in-process
        // relaunches and would otherwise carry the prior session's
        // `lastOn` (e.g. landing on QUIT GAME after the user
        // navigated there last time).
        MainDef.lastOn    = 0;
        EpiDef.lastOn     = 0;
        NewDef.lastOn     = hurtme;
        OptionsDef.lastOn = 0;
        SoundDef.lastOn   = 0;
        LoadDef.lastOn    = 0;
        SaveDef.lastOn    = 0;

        currentMenu = MainDef;
        menuactive = false;
        itemOn = currentMenu.lastOn;
        whichSkull = 0;
        skullAnimCounter = 10;
        screenSize = screenblocks - 3;
        messageToPrint = 0;
        messageString = null;
        messageLastMenuActive = menuactive;
        quickSaveSlot = -1;

        switch (Doomstat.gamemode) {
            case commercial:
                MainMenu[readthis_idx] = MainMenu[quitdoom_idx];
                MainDef.numitems--;
                MainDef.y += 8;
                NewDef.prevMenu = MainDef;
                ReadDef1.routine = MMenu::mDrawReadThis1;
                ReadDef1.x = 330;
                ReadDef1.y = 165;
                ReadMenu1[0].routine = MMenu::mFinishReadThis;
                break;
            case shareware:
            case registered:
                EpiDef.numitems--;
                break;
            case retail:
            default:
                break;
        }
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP_MARGV = MArgv.class;
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_CHARSETS = StandardCharsets.class;
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_PLAYER = Player.class;
}
