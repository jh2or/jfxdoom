package doom.ai;

import doom.play.*;
import doom.render.Line;
import doom.system.Doomdef;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AmmunitionPolicyTest {
    private Line[] lines;
    private Thinker next, previous;
    @BeforeEach void setup() {
        lines=PSetup.lines;PSetup.lines=new Line[0];
        next=PTick.thinkercap.next;previous=PTick.thinkercap.prev;
        PTick.thinkercap.next=PTick.thinkercap.prev=PTick.thinkercap;
    }
    @AfterEach void restore() {PSetup.lines=lines;PTick.thinkercap.next=next;PTick.thinkercap.prev=previous;}
    private Observation observe(int ready,int preferred,int bullets,int shells,int weapons) {
        Mobj enemy=new Mobj();enemy.type=Info.MT_SHOTGUY;enemy.health=30;enemy.x=80*65536;
        enemy.radius=20*65536;enemy.height=56*65536;
        var inventory=new Inventory(0,0,bullets,shells,0,ready,Doomdef.wp_nochange);
        return new Observation(0,0,0,100,bullets+4*shells,weapons,8,enemy,0,56,16,0,0,preferred,0,inventory,List.of());
    }
    @Test void noAmmoSkipsFightAndKeepsMovingTowardObjectiveInBothModes() {
        for (LearningStrategy mode:LearningStrategy.values()) for (int melee:new int[]{Doomdef.wp_fist,Doomdef.wp_chainsaw}) {
            var o=observe(melee,melee,0,0,3|(1<<melee));
            var controller=new LocalController(true,2048,mode);
            Action a=controller.towards(o,new MotionProbe(o),400,0,0,true).action();
            assertEquals(0,a.buttons()&Doomdef.BT_ATTACK);
            assertTrue(a.forward()!=0||a.side()!=0,"No-ammo encounters must not pause navigation to fight");
            assertNull(new SurvivalCombat().choose(o,new MotionProbe(o),0));
        }
    }
    @Test void finalGuardSuppressesRecoveryAttackAndPreservesInteraction() {
        var o=observe(Doomdef.wp_chainsaw,Doomdef.wp_chainsaw,0,0,3|(1<<Doomdef.wp_chainsaw));
        var proposal=new Explorer.Burst(new Action(25,0,0,Doomdef.BT_ATTACK|Doomdef.BT_USE),2);
        for (LearningStrategy mode:LearningStrategy.values()) {
            var a=new LocalController(true,2048,mode).protect(o,proposal,0).action();
            assertEquals(0,a.buttons()&Doomdef.BT_ATTACK);assertNotEquals(0,a.buttons()&Doomdef.BT_USE);assertEquals(25,a.forward());
        }
    }
    @Test void waitsForLoadedGunInsteadOfPunchingDuringWeaponSwitchThenCanFire() {
        Mobj enemy=new Mobj();enemy.type=Info.MT_POSSESSED;enemy.health=60;enemy.x=450*65536;
        var base=observe(Doomdef.wp_fist,Doomdef.wp_pistol,20,0,3);
        var switching=new Observation(0,0,0,100,20,3,8,enemy,0,56,16,0,0,Doomdef.wp_pistol,0,base.inventory(),List.of());
        var c=new LocalController(true,2048,LearningStrategy.SURVIVAL);
        var a=c.towards(switching,new MotionProbe(switching),500,0,0,true).action();
        assertEquals(0,a.buttons()&Doomdef.BT_ATTACK);assertNotEquals(0,a.buttons()&Doomdef.BT_CHANGE);
        var loaded=new Observation(0,0,0,100,20,3,8,enemy,0,56,16,0,0,Doomdef.wp_pistol,0,new Inventory(0,0,20,0,0,Doomdef.wp_pistol,Doomdef.wp_nochange),List.of());
        a=c.towards(loaded,new MotionProbe(loaded),500,0,30,true).action();assertNotEquals(0,a.buttons()&Doomdef.BT_ATTACK);
    }
    @Test void ammunitionMustMatchAnOwnedRangedWeapon() {
        Inventory i=new Inventory(0,0,0,1,1,Doomdef.wp_fist,Doomdef.wp_nochange,1,0,false);
        assertFalse(i.loadedRangedWeapon(3,Doomdef.wp_shotgun));
        assertFalse(i.loadedRangedWeapon(3,Doomdef.wp_pistol));
        assertFalse(i.loadedRangedWeapon(1<<Doomdef.wp_chainsaw,Doomdef.wp_chainsaw));
        assertTrue(i.loadedRangedWeapon(1<<Doomdef.wp_shotgun,Doomdef.wp_shotgun));
        assertTrue(i.loadedRangedWeapon(1<<Doomdef.wp_missile,Doomdef.wp_missile));
        assertFalse(i.loadedRangedWeapon(1<<Doomdef.wp_bfg,Doomdef.wp_bfg));
    }
}
