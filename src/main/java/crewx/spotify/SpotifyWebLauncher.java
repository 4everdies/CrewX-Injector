package crewx.spotify;

import crewx.inject.CrewXBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * Opens the original Spotify web UI inside the game through MCEF. The native
 * GuiSpotify remains the fallback when the embedded browser cannot start.
 */
public final class SpotifyWebLauncher {
    private SpotifyWebLauncher() {
    }

    public static boolean open(Minecraft mc, GuiScreen parent, SpotifyService service) {
        if (mc == null || service == null) return false;
        if (!hasMcef()) return false;
        if (McefRuntime.state == McefRuntime.State.FAILED) return false;
        return openEmbedded(mc, parent, service);
    }

    private static boolean hasMcef() {
        try {
            Class.forName("net.montoyo.mcef.MCEF", false,
                    SpotifyWebLauncher.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean openEmbedded(Minecraft mc, GuiScreen parent, SpotifyService service) {
        try {
            Class<?> type = Class.forName("crewx.spotify.SpotifyWebScreen");
            GuiScreen screen = (GuiScreen) type.getConstructor(GuiScreen.class, SpotifyService.class)
                    .newInstance(parent, service);
            mc.displayGuiScreen(screen);
            return true;
        } catch (Throwable error) {
            CrewXBootstrap.log("Spotify embedded web screen unavailable: " + error);
            try {
                if (mc.currentScreen != parent) mc.displayGuiScreen(parent);
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}
