package crewx.config;

import java.io.File;
import java.io.IOException;
import net.minecraft.client.Minecraft;

public final class GameDataDirectory {
    private GameDataDirectory() {
    }

    public static File resolve() {
        File current = Minecraft.getMinecraft().mcDataDir;
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return current;

        File shared = new File(appData, ".minecraft");
        File oneClient = new File(appData, "Polyfrost/OneClient/.minecraft");
        try {
            if (shared.isDirectory() && current.getCanonicalFile().equals(oneClient.getCanonicalFile())) {
                return shared;
            }
        } catch (IOException ignored) {
        }
        return current;
    }
}
