package crewx.inject;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class MappingRepositoryTest {
    @Test
    public void bundledDocumentContainsRuntimeProfilesAndMappingResources() throws Exception {
        assertNotNull(MappingRepository.profile("notch"));
        assertNotNull(MappingRepository.profile("ornithe"));
        assertNotNull(MappingRepository.profile("cheatbreaker"));

        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                MappingRepository.openResource("notch-joined.srg"), StandardCharsets.UTF_8))) {
            assertEquals("PK: . net/minecraft/src", lines.readLine());
        }
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                MappingRepository.openResource("cheatbreaker189.tiny"), StandardCharsets.UTF_8))) {
            assertEquals("tiny\t2\t0\tnamed\tcheatbreaker", lines.readLine());
        }
    }

    @Test
    public void unknownProfilesAreNotSilentlyTreatedAsMappings() {
        JsonObject unknown = MappingRepository.profile("not-a-client");
        assertEquals(null, unknown);
    }
}
