package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.policynim.cli.ProfileCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs a packaged transaction at the held or contended lock boundary for another installed client. */
public final class ProfileUpdateLockPeer {
    private ProfileUpdateLockPeer() { }

    public static void main(String[] args) throws Exception {
        String role = args[0];
        Path marker = Path.of(args[1]);
        String name = args[2];
        String token = args[3];
        if (role.equals("hold")) {
            var report = ProfileUpdate.apply(Path.of(name), token, (parent, entry) -> {
                var files = ProfileUpdate.open(parent, entry);
                try {
                    Files.createFile(marker);
                    if (System.in.read() != '+') {
                        throw new IOException("The test owner stopped before staging.");
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    try (files) {
                        throw failure;
                    }
                }
                return files;
            });
            var result = JsonNodeFactory.instance.objectNode().put("status", "ok").put("path", name);
            result.set("diff", report);
            System.out.println(result);
        } else if (role.equals("wait")) {
            Thread caller = Thread.currentThread();
            Thread.ofPlatform().daemon().start(() -> signalContention(caller, marker));
            System.exit(ProfileCommand.run(new String[]{"profile", "update", name, "--expect-digest", token,
                "--format", "json"}, System.out, System.err));
        } else {
            throw new IllegalArgumentException("Unknown lock test role.");
        }
    }

    private static void signalContention(Thread caller, Path marker) {
        try {
            while (caller.isAlive()) {
                boolean acquiring = false;
                boolean sleeping = false;
                for (var frame : caller.getStackTrace()) {
                    acquiring |= (frame.getClassName().equals(PosixProfileUpdate.class.getName())
                            && frame.getMethodName().equals("acquire"))
                        || (frame.getClassName().equals(WindowsProfileUpdate.class.getName())
                            && frame.getMethodName().equals("acquireLock"));
                    sleeping |= frame.getClassName().equals(Thread.class.getName()) && frame.getMethodName().startsWith("sleep");
                }
                if (acquiring && sleeping) {
                    Files.createFile(marker);
                    return;
                }
                Thread.sleep(5);
            }
        } catch (IOException | InterruptedException error) {
            throw new IllegalStateException("Couldn't record the Java lock wait.", error);
        }
    }
}
