package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logFatalError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.ObjectStreamException;

/**
 * {@link ComputerLauncher} for VirtualBox that waits for the instance to really come up before processing to
 * the real user-specified {@link ComputerLauncher}.
 * <p>
 * TODO check relaunch during launch
 * </p>
 *
 * @author Evgeny Mandrikov
 */
public class VirtualBoxComputerLauncher extends DelegatingComputerLauncher {

  private transient VirtualBoxMachine virtualMachine;

  @Deprecated
  private transient ComputerLauncher delegate;

  private String hostName;

  private String virtualMachineName;

  private String snapshotName;

  private String virtualMachineType;

  private String virtualMachineStopMode;

  private int startupWaitingPeriodSeconds;

  @DataBoundConstructor
  public VirtualBoxComputerLauncher(ComputerLauncher launcher, String hostName, String virtualMachineName,
      String snapshotName, String virtualMachineType, String virtualMachineStopMode, int startupWaitingPeriodSeconds) {
    super(launcher);
    this.hostName = hostName;
    this.virtualMachineName = virtualMachineName;
    this.snapshotName = snapshotName;
    this.virtualMachineType = virtualMachineType;
    this.virtualMachineStopMode = virtualMachineStopMode;
    this.startupWaitingPeriodSeconds = startupWaitingPeriodSeconds;
    lookupVirtualMachineHandle();
  }

  /**
   * Migrates instances from the old parent class to the new parent class.
   * @return the deserialized instance.
   * @throws ObjectStreamException if something went wrong.
   */
  private Object readResolve() throws ObjectStreamException {
    if (delegate != null) {
      return new VirtualBoxComputerLauncher(delegate, hostName, virtualMachineName, snapshotName, virtualMachineType,
              virtualMachineStopMode, startupWaitingPeriodSeconds);
    }
    return this;
  }

  private synchronized VirtualBoxMachine lookupVirtualMachineHandle() {
    if (virtualMachine == null) {
      virtualMachine = VirtualBoxPlugin.getVirtualBoxMachine(hostName, virtualMachineName);
    }
    return virtualMachine;
  }

  @Override
  public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
    log(listener, "Launching node " + virtualMachineName);
    try {
      // Connect to VirtualBox host
      if (lookupVirtualMachineHandle() == null) {
        log(listener, "Virtual machine " + virtualMachineName + " not found, retrying ...");
        if (lookupVirtualMachineHandle() == null) {
          log(listener, "Unable to find specified machine (" + virtualMachineName + ") on host " + hostName);
          throw new Exception("Unable to find specified machine (" + virtualMachineName + ") on host " + hostName);
        }
      }
      log(listener, Messages.VirtualBoxLauncher_startVM(virtualMachineName));
      long result = VirtualBoxUtils.startVm(lookupVirtualMachineHandle(), snapshotName, virtualMachineType);
      if (result != 0) {
        log(listener, "Unable to launch virtual machine " + virtualMachineName + ", giving up :(");
        return;
      }
    } catch (Throwable e) {
      log(listener, "Exception while launching virtual machine " + virtualMachineName, e);
      return;
    }
    // Stage 2 of the launch. Called after the VirtualBox instance comes up.
    boolean successful = false;
    log(listener, "Waiting for " + startupWaitingPeriodSeconds + " seconds for the virtual machine to be ready");
    Thread.sleep(startupWaitingPeriodSeconds * 1000);
    successful = delegateLaunch(computer, listener);
    if (!successful) {
      log(listener, "Virtual machine still not ready :(");
      return;
    }
  }

  /**
   * @param computer {@link hudson.model.Computer} for which agent should be launched
   * @param listener The progress of the launch, as well as any error, should be sent to this listener.
   * @return true, if successfully launched, otherwise false
   */
  protected boolean delegateLaunch(SlaveComputer computer, TaskListener listener) {
    try {
      log(listener, "Starting stage 2 launcher (" + getLauncher().getClass().getSimpleName() + ")");
      super.launch(computer, listener);
      log(listener, "Stage 2 launcher completed");
      return computer.isOnline();
    } catch (IOException e) {
      log(listener, "Unable to launch", e);
      return false;
    } catch (InterruptedException e) {
      log(listener, "Unable to launch", e);
      return false;
    }
  }

  @Override
  public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
    // Stage 2 of the afterDisconnect
    log(listener, "Starting stage 2 afterDisconnect");
    super.afterDisconnect(computer, listener);
    log(listener, "Stage 2 afterDisconnect completed");

    try {
      log(listener, Messages.VirtualBoxLauncher_stopVM(virtualMachineName));
      long result = VirtualBoxUtils.stopVm(lookupVirtualMachineHandle(), snapshotName, virtualMachineStopMode);
      if (result != 0) {
        listener.fatalError("Unable to stop");
      }
    } catch (Throwable e) {
      log(listener, "Unable to stop virtual machine " + virtualMachineName, e);
    }
  }

  @Override
  public Descriptor<ComputerLauncher> getDescriptor() {
    // Don't allow creation of launcher from UI
    throw new UnsupportedOperationException();
  }

  private void log(TaskListener listener, String message) {
      listener.getLogger().println("[VirtualBox] " + message);
      logInfo(message);
    }

  private void log(TaskListener listener, String message, Throwable thrown) {
      listener.fatalError("[VirtualBox] " + message + " : " + thrown.getMessage());
      thrown.printStackTrace(listener.getLogger());
      logFatalError(message, thrown);
    }

}
