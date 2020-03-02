package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;

import java.util.List;

/**
 * @author Mihai Serban
 */
public final class VirtualBoxUtils {

  // public methods
  public static long startVm(final VirtualBoxMachine machine, final String snapshotName, final String virtualMachineType) {
    return executeWithVboxControl(machine.getHost(), new VBoxControlCallback<Long>() {
      @Override
    public Long doWithVboxControl(final VirtualBoxControl vboxControl) {
        return vboxControl.startVm(machine, snapshotName, virtualMachineType);
      }
    });
  }

  public static long stopVm(final VirtualBoxMachine machine, final String snapshotName, final String virtualMachineStopMode) {
    return executeWithVboxControl(machine.getHost(), new VBoxControlCallback<Long>() {
      @Override
    public Long doWithVboxControl(final VirtualBoxControl vboxControl) {
        return vboxControl.stopVm(machine, snapshotName, virtualMachineStopMode);
      }
    });
  }

  public static List<VirtualBoxMachine> getMachines(final VirtualBoxCloud host) {
    return executeWithVboxControl(host, new VBoxControlCallback<List<VirtualBoxMachine>>() {
      @Override
    public List<VirtualBoxMachine> doWithVboxControl(final VirtualBoxControl vboxControl) {
        return vboxControl.getMachines(host);
      }
    });
  }

  public static String[] getSnapshots(final VirtualBoxCloud host, final String virtualMachineName) {
    return executeWithVboxControl(host, new VBoxControlCallback<String[]>() {
      @Override
    public String[] doWithVboxControl(final VirtualBoxControl vboxControl) {
        return vboxControl.getSnapshots(virtualMachineName);
      }
    });
  }

  public static String getMacAddress(final VirtualBoxMachine machine) {
    return executeWithVboxControl(machine.getHost(), new VBoxControlCallback<String>() {
      @Override
    public String doWithVboxControl(final VirtualBoxControl vboxControl) {
        return vboxControl.getMacAddress(machine);
      }
    });
  }

  // private methods
  private VirtualBoxUtils() {
  }

  private synchronized static <T> T executeWithVboxControl(final VirtualBoxCloud host, final VBoxControlCallback<T> vBoxControlCallback) {
    final VirtualBoxControl vboxControl = getVboxControl(host);
    try {
      return vBoxControlCallback.doWithVboxControl(vboxControl);
    } finally {
      vboxControl.disconnect();
    }
  }

  private static VirtualBoxControl getVboxControl(final VirtualBoxCloud host) {
    return createVboxControl(host);
  }

  private static VirtualBoxControl createVboxControl(final VirtualBoxCloud host) {
    VirtualBoxControl vboxControl = null;

    logInfo("Trying to connect to " + host.getUrl() + ", user " + host.getUsername());
    String version = null;

    try {
      final org.virtualbox_6_1.VirtualBoxManager manager = org.virtualbox_6_1.VirtualBoxManager.createInstance(null);
      manager.connect(host.getUrl(), host.getUsername(), host.getPassword().getPlainText());
      version = manager.getVBox().getVersion();
      manager.disconnect();
    } catch (final Exception e) { 
      // fallback to old method
      final com.sun.xml.ws.commons.virtualbox_3_1.IWebsessionManager manager = new com.sun.xml.ws.commons.virtualbox_3_1.IWebsessionManager(host.getUrl());
      final com.sun.xml.ws.commons.virtualbox_3_1.IVirtualBox vbox = manager.logon(host.getUsername(), host.getPassword().getPlainText());
      version = vbox.getVersion();
      manager.disconnect(vbox);
    }

    logInfo("Creating connection to VirtualBox version " + version);
    if (version.startsWith("6.1")) {
        vboxControl = new VirtualBoxControlV61(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("6.0")) {
        vboxControl = new VirtualBoxControlV60(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("5.2")) {
        vboxControl = new VirtualBoxControlV52(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("5.1")) {
      vboxControl = new VirtualBoxControlV51(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("5.0")) {
      vboxControl = new VirtualBoxControlV50(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("4.3")) {
      vboxControl = new VirtualBoxControlV43(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("4.2")) {
      vboxControl = new VirtualBoxControlV42(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("4.1")) {
      vboxControl = new VirtualBoxControlV41(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("4.0")) {
      vboxControl = new VirtualBoxControlV40(host.getUrl(), host.getUsername(), host.getPassword());
    } else if (version.startsWith("3.")) {
      vboxControl = new VirtualBoxControlV31(host.getUrl(), host.getUsername(), host.getPassword());
    } else {
      logError("VirtualBox version " + version + " not supported.");
      throw new UnsupportedOperationException("VirtualBox version " + version + " not supported.");
    }

    logInfo("Connected to VirtualBox version " + version + " on host " + host.getUrl());
    return vboxControl;
  }

}
