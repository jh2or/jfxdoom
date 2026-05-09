// Port of linuxdoom-1.10/d_event.h event_t (lines 50–60).
//
// Typed event record posted by the platform input layer (i_video.c
// I_StartTic in C, JfxDoomApp's setOnKeyPressed/Released here) and
// consumed by D_ProcessEvents → G_Responder.
package doom.system;

public final class DEvent {

    // d_event.h evtype_t (lines 38–48).
    public static final int EV_KEYDOWN = 0;
    public static final int EV_KEYUP   = 1;
    public static final int EV_MOUSE   = 2;
    public static final int EV_JOYSTICK = 3;

    public int type;
    public int data1;  // keycode (key events) or button bitmask (mouse / joystick)
    public int data2;  // mouse / joystick X delta
    public int data3;  // mouse / joystick Y delta

    public DEvent() {
    }

    public DEvent(int type, int data1) {
        this.type = type;
        this.data1 = data1;
    }
}
