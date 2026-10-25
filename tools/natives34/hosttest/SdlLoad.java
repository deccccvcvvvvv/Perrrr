import java.nio.IntBuffer;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Loads the SDL3 bridge through LWJGL's real org.lwjgl.sdl bindings (-Dorg.lwjgl.sdl.libname=<bridge .so>):
 * initialises every *$Functions class (each resolves all of its functions, as when Minecraft first touches it),
 * then performs a few calls whose results cross the JNI/struct boundary.
 */
public class SdlLoad {
    public static void main(String[] args) throws Exception {
        int classes = 0;
        try (JarFile jar = new JarFile(args[0])) {
            for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (n.startsWith("org/lwjgl/sdl/") && n.endsWith("$Functions.class")) {
                    Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), true, SdlLoad.class.getClassLoader());
                    classes++;
                }
            }
        }
        check(classes > 40, "function classes initialised: " + classes);
        check("Android".equals(SDLPlatform.SDL_GetPlatform()), "SDL_GetPlatform");
        check(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), "SDL_Init");
        IntBuffer displays = SDLVideo.SDL_GetDisplays();
        check(displays != null && displays.remaining() == 1 && displays.get(0) == 1, "SDL_GetDisplays");
        SDLStdinc.SDL_free(displays);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Event ev = SDL_Event.calloc(stack);
            ev.key().type(SDLEvents.SDL_EVENT_KEY_DOWN).scancode(SDLScancode.SDL_SCANCODE_W).key(SDLKeycode.SDLK_W).down(true);
            check(SDLEvents.SDL_PushEvent(ev), "SDL_PushEvent");
            SDL_Event out = SDL_Event.calloc(stack);
            check(SDLEvents.SDL_PollEvent(out) && out.type() == SDLEvents.SDL_EVENT_KEY_DOWN && out.key().scancode() == SDLScancode.SDL_SCANCODE_W && out.key().down(), "event round trip");
        }
        check(SDLVideo.SDL_GetDisplayName(1).equals("Android display"), "display name");
        System.out.println("LWJGL SDL bindings: OK (" + classes + " function classes)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what + " -- " + SDLError.SDL_GetError()); System.exit(1); }
    }
}
