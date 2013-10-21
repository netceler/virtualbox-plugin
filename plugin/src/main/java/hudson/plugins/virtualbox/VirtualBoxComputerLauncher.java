package hudson.plugins.virtualbox;

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ComputerLauncher} for VirtualBox that waits for the instance to really come up before processing to
 * the real user-specified {@link ComputerLauncher}.
 * <p>
 * TODO check relaunch during launch
 * </p>
 *
 * @author Evgeny Mandrikov
 */
public class VirtualBoxComputerLauncher extends ComputerLauncher {
  private static final Logger LOG = Logger.getLogger(VirtualBoxComputerLauncher.class.getName());

  private transient VirtualBoxMachine virtualMachine;

  private ComputerLauncher delegate;

  private String hostName;

  private String virtualMachineName;

  private String virtualMachineType;

  private String virtualMachineStopMode;

  private int startupWaitingPeriodSeconds;

  @DataBoundConstructor
  public VirtualBoxComputerLauncher(ComputerLauncher delegate, String hostName, String virtualMachineName,
      String virtualMachineType, String virtualMachineStopMode, int startupWaitingPeriodSeconds) {
    this.delegate = delegate;
    this.hostName = hostName;
    this.virtualMachineName = virtualMachineName;
    this.virtualMachineType = virtualMachineType;
    this.virtualMachineStopMode = virtualMachineStopMode;
    this.startupWaitingPeriodSeconds = startupWaitingPeriodSeconds;
    lookupVirtualMachineHandle();
  }

  private void lookupVirtualMachineHandle() {
    virtualMachine = VirtualBoxPlugin.getVirtualBoxMachine(hostName, virtualMachineName);
  }

  @Override
  public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
    log(listener, "Launching node " + virtualMachineName);
    try {
      // Connect to VirtualBox host
      if (virtualMachine == null) {
        listener.getLogger().println("Virtual machine " + virtualMachineName + " not found, retrying ...");
        lookupVirtualMachineHandle();
        if (virtualMachine == null) {
          listener.fatalError("Unable to find specified machine (" + virtualMachineName + ") on host " + hostName);
          throw new Exception("Unable to find specified machine (" + virtualMachineName + ") on host " + hostName);
        }
      }
      log(listener, Messages.VirtualBoxLauncher_startVM(virtualMachine));
      long result = VirtualBoxUtils.startVm(virtualMachine, virtualMachineType, new VirtualBoxTaskListenerLog(listener, "[VirtualBox] "));
      if (result != 0) {
        listener.fatalError("Unable to launch");
        return;
      }
    } catch (Throwable e) {
      listener.fatalError(e.getMessage(), e);
      e.printStackTrace(listener.getLogger());
      LOG.log(Level.WARNING, e.getMessage(), e);
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
      log(listener, "Starting stage 2 launcher (" + delegate.getClass().getSimpleName() + ")");
      getCore().launch(computer, listener);
      log(listener, "Stage 2 launcher completed");
      return computer.isOnline();
    } catch (IOException e) {
      log(listener, "Unable to launch: " + e.getMessage());
      return false;
    } catch (InterruptedException e) {
      log(listener, "Unable to launch: " + e.getMessage());
      return false;
    }
  }

  private static void log(TaskListener listener, String message) {
    listener.getLogger().println("[VirtualBox] " + message);
  }

  @Override
  public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
    log(listener, "Starting stage 2 beforeDisconnect");
    getCore().beforeDisconnect(computer, listener);
    log(listener, "Stage 2 beforeDisconnect completed");
  }

  @Override
  public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
    // Stage 2 of the afterDisconnect
    log(listener, "Starting stage 2 afterDisconnect");
    getCore().afterDisconnect(computer, listener);
    log(listener, "Stage 2 afterDisconnect completed");

    try {
      log(listener, Messages.VirtualBoxLauncher_stopVM(virtualMachine));
      long result = VirtualBoxUtils.stopVm(virtualMachine, virtualMachineStopMode, new VirtualBoxTaskListenerLog(listener, "[VirtualBox] "));
      if (result != 0) {
        listener.fatalError("Unable to stop");
      }
    } catch (Throwable e) {
      listener.fatalError(e.getMessage(), e);
    }
  }

  /**
   * @return delegation target
   */
  public ComputerLauncher getCore() {
    return delegate;
  }

  @Override
  public Descriptor<ComputerLauncher> getDescriptor() {
    // Don't allow creation of launcher from UI
    throw new UnsupportedOperationException();
  }
}
