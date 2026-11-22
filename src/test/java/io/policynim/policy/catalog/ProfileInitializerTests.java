package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileInitializerTests {
    @TempDir
    Path workspace;

    @Test
    void createsThePortableTwoPackProfileBeforeItsDestinationDirectoryExists() throws Exception {
        var expected = new ObjectMapper().readTree(getClass().getResource("/parity/catalog/profile-authoring.json"));
        for (var entry : expected.required("files").properties()) {
            var file = workspace.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue().textValue());
        }
        var file = workspace.resolve("profiles/team.toml");
        var profile = ProfileInitializer.initialize(file, List.of(workspace.resolve("team").toString(),
            workspace.resolve("base").toString()), "review");
        assertThat(Files.readString(file)).isEqualTo(expected.get("authoredProfile").textValue());
        assertThat(ProfileToml.read(file).json()).isEqualTo(profile.json());
        assertThat(ProfileResolver.resolve(file).list().get("effective_count").intValue()).isEqualTo(3);
    }
}
