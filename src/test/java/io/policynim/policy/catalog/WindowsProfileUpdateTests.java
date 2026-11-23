package io.policynim.policy.catalog;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsProfileUpdateTests {
    private static final String NAME = "r\u00e9view.toml";
    private static final String OLD = "old\r\n";
    private static final byte[] NEW = "new\r\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void setsOnlyTheReplaceFlagForUpdate() {
        for (int pointerSize : new int[]{4, 8}) {
            byte[] create = WindowsProfileStage.renameInformation(pointerSize, 0x1234L, NAME, false);
            byte[] replace = WindowsProfileStage.renameInformation(pointerSize, 0x1234L, NAME, true);
            assertThat(create[0]).isZero();
            assertThat(replace[0]).isEqualTo((byte) 1);
            create[0] = 1;
            assertThat(replace).isEqualTo(create);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void stagesPrivatelyAndCommitsAfterTheReaderAllowsReplacement(@TempDir Path temp) throws Exception {
        Path parent = temp.toRealPath();
        Path target = Files.writeString(parent.resolve(NAME), OLD);
        try (var update = new WindowsProfileUpdate(parent, NAME)) {
            var original = update.readOriginal();
            assertThat(original.text()).isEqualTo("old\n");
            update.write(NEW);
            update.force();
            checkPrivateStage(parent);
            try (var reader = FileChannel.open(target, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
                update.verify(original);
                assertThatThrownBy(update::replace).isInstanceOf(IOException.class);
            } finally {
                update.discard();
            }
            assertThat(Files.readString(target)).isEqualTo(OLD);
        }
        try (var retry = new WindowsProfileUpdate(parent, NAME)) {
            var original = retry.readOriginal();
            retry.write(NEW);
            retry.force();
            retry.verify(original);
            retry.replace();
            retry.discard();
        }
        assertThat(Files.readAllBytes(target)).isEqualTo(NEW);
        try (var contents = Files.list(parent)) {
            assertThat(contents.toList()).containsExactlyInAnyOrder(target, parent.resolve("." + NAME + ".lock"));
        }
    }

    private static Path stagingDirectory(Path parent) throws IOException {
        try (var entries = Files.list(parent)) {
            return entries.filter(Files::isDirectory).findFirst().orElseThrow();
        }
    }

    private static void checkPrivateStage(Path parent) throws Exception {
        String check = """
            $ErrorActionPreference = 'Stop'
            $acl = Get-Acl -LiteralPath $env:POLICYNIM_TEST_STAGE
            if (!$acl.AreAccessRulesProtected) { throw 'Stage inherited the parent ACL' }
            $sid = [System.Security.Principal.SecurityIdentifier]
            $rules = @($acl.GetAccessRules($true, $true, $sid))
            $expected = @('S-1-5-18', 'S-1-5-32-544', 'S-1-3-4')
            if (Compare-Object $expected @($rules | ForEach-Object { $_.IdentityReference.Value })) {
                throw 'Unexpected principals on stage directory'
            }
            foreach ($rule in $rules) {
                if ($rule.AccessControlType -ne 'Allow' -or $rule.FileSystemRights -ne 'FullControl' -or
                    [int]$rule.InheritanceFlags -ne 3) { throw 'Incorrect stage rights or inheritance' }
            }
            $child = Get-ChildItem -LiteralPath $env:POLICYNIM_TEST_STAGE -Force
            $inherited = @((Get-Acl -LiteralPath $child.FullName).GetAccessRules($false, $true, $sid))
            if ($inherited.Count -eq 0) { throw 'Missing inherited staging file rights' }
            foreach ($rule in $inherited) {
                if ($rule.IdentityReference.Value -notin $expected -or $rule.AccessControlType -ne 'Allow') {
                    throw 'Unexpected inherited principal or access type'
                }
            }
            """;
        var command = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", check)
            .redirectErrorStream(true);
        command.environment().put("POLICYNIM_TEST_STAGE", stagingDirectory(parent).toString());
        var powershell = command.start();
        try {
            assertThat(powershell.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(powershell.exitValue()).as(new String(powershell.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)).isZero();
        } finally {
            powershell.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusesAReplacedEntryAndKeepsItsContents(@TempDir Path temp) throws Exception {
        Path parent = temp.toRealPath();
        Path target = Files.writeString(parent.resolve(NAME), OLD);
        try (var update = new WindowsProfileUpdate(parent, NAME)) {
            var original = update.readOriginal();
            update.write(NEW);
            Path edited = Files.writeString(parent.resolve("edited"), OLD);
            Files.move(edited, target, StandardCopyOption.REPLACE_EXISTING);
            assertThatThrownBy(() -> update.verify(original)).isInstanceOf(IOException.class);
            Path note = Files.writeString(stagingDirectory(parent).resolve("notes.txt"), "keep this");
            assertThatThrownBy(update::discard).isInstanceOf(IOException.class);
            assertThat(Files.readString(note)).isEqualTo("keep this");
        }
        assertThat(Files.readString(target)).isEqualTo(OLD);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void keepsOneStableByteLockedAndReleasesAnInterruptedWaiter(@TempDir Path temp) throws Exception {
        Path parent = temp.toRealPath();
        Path target = Files.writeString(parent.resolve(NAME), OLD);
        Path sibling = parent.resolve("." + NAME + ".lock");
        try (var owner = new WindowsProfileUpdate(parent, NAME);
             var other = FileChannel.open(sibling, StandardOpenOption.WRITE)) {
            assertThat(other.tryLock(0, 1, false)).isNull();
            try (var adjacent = other.tryLock(1, 1, false)) {
                assertThat(adjacent).isNotNull();
            }
        }
        try (var other = FileChannel.open(sibling, StandardOpenOption.WRITE);
             var peer = other.lock(0, 1, false)) {
            var failure = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            var waiter = Thread.ofPlatform().daemon().start(() -> {
                try (var ignored = new WindowsProfileUpdate(parent, NAME)) {
                    failure.set(new AssertionError("The peer still owns the lock."));
                } catch (IOException ex) {
                    failure.set(ex);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = atContentionWait(waiter);
                while (!waiting && waiter.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                    waiting = atContentionWait(waiter);
                }
                assertThat(waiting).isTrue();
            } finally {
                waiter.interrupt();
                waiter.join(5000);
            }
            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedIOException.class);
            assertThat(interrupted.get()).isTrue();
        }
        try (var retry = new WindowsProfileUpdate(parent, NAME)) {
            assertThat(retry.readOriginal().text()).isEqualTo("old\n");
        }
        assertThat(Files.readString(target)).isEqualTo(OLD);
        assertThat(sibling).isRegularFile();
    }

    private static boolean atContentionWait(Thread thread) {
        return thread.getState() == Thread.State.TIMED_WAITING && Arrays.stream(thread.getStackTrace())
            .anyMatch(frame -> frame.getClassName().equals(WindowsProfileUpdate.class.getName()));
    }
}
