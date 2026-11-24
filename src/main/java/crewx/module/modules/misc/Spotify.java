package crewx.module.modules.misc;

import crewx.module.Module;
import crewx.spotify.GuiSpotify;
import crewx.spotify.SpotifyService;
import crewx.spotify.SpotifyWebLauncher;
import net.minecraft.client.Minecraft;

/** Opens the standalone Spotify controls without running work during gameplay. */
public final class Spotify extends Module {
    private static volatile SpotifyService service;

    public Spotify() {
        super("Spotify", false);
    }

    private static SpotifyService service() {
        SpotifyService current = service;
        if (current == null) {
            synchronized (Spotify.class) {
                current = service;
                if (current == null) {
                    service = current = new SpotifyService(Minecraft.getMinecraft().mcDataDir);
                }
            }
        }
        return current;
    }

    @Override
    public void setEnabled(boolean enabled) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled) {
            super.setEnabled(false);
            if (mc != null && (mc.currentScreen instanceof GuiSpotify
                    || (mc.currentScreen != null && "crewx.spotify.SpotifyWebScreen".equals(
                            mc.currentScreen.getClass().getName())))) {
                mc.displayGuiScreen(null);
            }
            return;
        }
        if (mc == null || mc.thePlayer == null) {
            // Never reopen Spotify from a saved config on the title screen.
            super.setEnabled(false);
            return;
        }
        SpotifyService spotify = service();
        super.setEnabled(true);
        if (spotify.getClientId().isEmpty()
                || !SpotifyWebLauncher.open(mc, mc.currentScreen, spotify)) {
            mc.displayGuiScreen(new GuiSpotify(mc.currentScreen, spotify));
        }
    }
}
