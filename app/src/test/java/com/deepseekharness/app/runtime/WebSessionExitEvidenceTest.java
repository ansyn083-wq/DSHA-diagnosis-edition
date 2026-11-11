package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebPidIdentity;
import com.deepseekharness.app.util.WebSessionProof;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** 退出核验使用内核身份；完整恢复仍须读取原实例的环境和鉴权信息。 */
public final class WebSessionExitEvidenceTest {
  private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
  private static final WebSessionProof.Saved SAVED =
      new WebSessionProof.Saved(BOOT, 7, BOOT, "ab".repeat(32), 42, 100, "web", 41, 90, "launcher");

  private static WebProcessManager.ProcessState live(int pid, long started) {
    return new WebProcessManager.ProcessState(
        WebProcessManager.Kind.OTHER,
        WebPidIdentity.parse(pid + " (process) S 1 1 1 " + "0 ".repeat(15) + started + " 0 0", pid),
        "process");
  }

  private static final class Kernel extends WebProcessManager {
    final Map<Integer, ProcessState> processes = new HashMap<>();
    final ArrayDeque<ProcessState> observations = new ArrayDeque<>();
    String boot = BOOT;
    Integer uid = 10001;
    int inspections, environmentReads;

    Kernel() {
      super(null);
      processes.put(42, live(42, 100));
      processes.put(41, live(41, 90));
    }

    void both(ProcessState state) {
      processes.put(42, state);
      processes.put(41, state);
    }

    @Override
    String recoveryBoot() {
      return boot;
    }

    @Override
    ProcessState inspect(int pid) {
      inspections++;
      return observations.isEmpty() ? processes.get(pid) : observations.removeFirst();
    }

    @Override
    Integer processOwner(int pid) {
      return uid;
    }

    @Override
    String readProcessFile(int pid, String name) throws IOException {
      assertEquals("environ", name);
      environmentReads++;
      throw new IOException("PROC_ENVIRONMENT_DENIED");
    }

    @Override
    public boolean verifySession(WebSessionProof.Saved saved) throws IOException {
      return recoveryProcess(saved.webPid()).alive()
          && recoveryProcess(saved.launcherPid()).alive();
    }
  }

  @Test
  public void foreignUidReuseConfirmsExitWhileFullRecoveryRemainsRejected() throws Exception {
    Kernel kernel = new Kernel();
    kernel.uid = 10002;
    kernel.both(new WebProcessManager.ProcessState(WebProcessManager.Kind.OTHER, null, "", true));
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
    assertTrue(kernel.recoveredLauncherExited(SAVED));
    assertEquals(0, kernel.environmentReads);
    assertThrows(IOException.class, () -> kernel.recoveryProcess(SAVED.webPid()));
  }

  @Test
  public void changedBirthConfirmsExitWithoutReadingReplacementEnvironment() throws Exception {
    Kernel kernel = new Kernel();
    kernel.processes.put(42, live(42, 101));
    kernel.processes.put(41, live(41, 91));
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
    assertTrue(kernel.recoveredLauncherExited(SAVED));
    assertEquals(0, kernel.environmentReads);
  }

  @Test
  public void hiddenSignalZeroEpermNeedsTwoConsistentKernelObservations() throws Exception {
    Kernel kernel = new Kernel();
    kernel.uid = null;
    kernel.both(
        new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, "", false, true));
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
    assertEquals(2, kernel.inspections);
    assertTrue(kernel.recoveredLauncherExited(SAVED));
    assertEquals(4, kernel.inspections);
    assertEquals(0, kernel.environmentReads);
  }

  @Test
  public void ordinaryProcDenialKeepsExitUnknown() {
    Kernel kernel = new Kernel();
    kernel.both(new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, ""));
    assertEquals(WebProcessManager.SessionObservation.UNKNOWN, kernel.observeSession(SAVED));
    assertThrows(IOException.class, () -> kernel.recoveredLauncherExited(SAVED));
  }

  @Test
  public void liveIdentityOnSecondObservationKeepsLauncherPending() throws Exception {
    Kernel kernel = new Kernel();
    kernel.observations.add(
        new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, "", false, true));
    kernel.observations.add(live(41, 90));
    assertFalse(kernel.recoveredLauncherExited(SAVED));
    assertEquals(2, kernel.inspections);
    assertEquals(0, kernel.environmentReads);
  }

  @Test
  public void ordinaryDenialOnSecondObservationKeepsLauncherExitUnknown() {
    Kernel kernel = new Kernel();
    kernel.observations.add(
        new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, "", false, true));
    kernel.observations.add(
        new WebProcessManager.ProcessState(WebProcessManager.Kind.DENIED, null, ""));
    assertThrows(IOException.class, () -> kernel.recoveredLauncherExited(SAVED));
    assertEquals(2, kernel.inspections);
  }

  @Test
  public void unchangedBirthWithUnreadableEnvironmentDoesNotProveExit() throws Exception {
    Kernel kernel = new Kernel();
    assertEquals(WebProcessManager.SessionObservation.UNKNOWN, kernel.observeSession(SAVED));
    assertFalse(kernel.recoveredLauncherExited(SAVED));
    assertTrue(kernel.environmentReads > 0);
  }

  @Test
  public void missingPidAndNewBootConfirmExitWithoutEnvironmentReads() throws Exception {
    Kernel kernel = new Kernel();
    kernel.both(new WebProcessManager.ProcessState(WebProcessManager.Kind.GONE, null, ""));
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
    assertTrue(kernel.recoveredLauncherExited(SAVED));
    int inspected = kernel.inspections;
    kernel.boot = "other-boot";
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
    assertTrue(kernel.recoveredLauncherExited(SAVED));
    assertEquals(inspected, kernel.inspections);
    assertEquals(0, kernel.environmentReads);
  }
}
