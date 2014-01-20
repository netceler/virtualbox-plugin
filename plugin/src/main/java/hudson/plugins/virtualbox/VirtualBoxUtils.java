package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;

import com.sun.xml.ws.commons.virtualbox_3_1.IVirtualBox;
import com.sun.xml.ws.commons.virtualbox_3_1.IWebsessionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Mihai Serban
 */
public final class VirtualBoxUtils {

  // public methods
  public static long startVm(VirtualBoxMachine machine, String snapshotName, String virtualMachineType) {
    synchronized (getLock(machine.getName())) {
      return getVboxControl(machine.getHost()).startVm(machine, snapshotName, virtualMachineType);
    }
  }

  public static long stopVm(VirtualBoxMachine machine, String snapshotName, String virtualMachineStopMode) {
    synchronized (getLock(machine.getName())) {
      return getVboxControl(machine.getHost()).stopVm(machine, snapshotName, virtualMachineStopMode);
    }
  }

  public static List<VirtualBoxMachine> getMachines(VirtualBoxCloud host) {
    return getVboxControl(host).getMachines(host);
  }

  public static String[] getSnapshots(VirtualBoxCloud host, String virtualMachineName) {
    return getVboxControl(host).getSnapshots(virtualMachineName);
  }

  public static String getMacAddress(VirtualBoxMachine machine) {
    return getVboxControl(machine.getHost()).getMacAddress(machine);
  }

  public static void disconnectAll() {
    for (Map.Entry<String, VirtualBoxControl> entry: vboxControls.entrySet()) {
      entry.getValue().disconnect();
    }
    vboxControls.clear();
  }

  // private methods
  private VirtualBoxUtils() {
  }

  /**
   * Cache connections to VirtualBox hosts
   * TODO: keep the connections alive with a noop
   */
  private static HashMap<String, VirtualBoxControl> vboxControls = new HashMap<String, VirtualBoxControl>();

  private static VirtualBoxControl getVboxControl(VirtualBoxCloud host) {
    synchronized (vboxControls) {
      VirtualBoxControl vboxControl = vboxControls.get(host.toString());
      if (null != vboxControl) {
        if (vboxControl.isConnected()) {
          return vboxControl;
        }
        logInfo("Lost connection to " + host.getUrl() + ", reconnecting");
        vboxControls.remove(host.toString()).disconnect(); // force a reconnect
      }

      vboxControl = createVboxControl(host);
      vboxControls.put(host.toString(), vboxControl);

      return vboxControl;
    }
  }

  private static VirtualBoxControl createVboxControl(VirtualBoxCloud host) {
    VirtualBoxControl vboxControl = null;

    logInfo("Trying to connect to " + host.getUrl() + ", user " + host.getUsername());
    IWebsessionManager manager = new IWebsessionManager(host.getUrl());
    IVirtualBox vbox = manager.logon(host.getUsername(), host.getPassword());
    String version = vbox.getVersion();
    manager.disconnect(vbox);

    logInfo("Creating connection to VirtualBox version " + version);
    if (version.startsWith("4.3")) {
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

  private static Map<String,Object> locks = new HashMap<String,Object>();

  private synchronized static Object getLock(String name) {
      Object lock = locks.get(name);
      if (lock == null) {
          lock = new Object();
          locks.put(name, lock);
      }
      return lock;
  }

}
