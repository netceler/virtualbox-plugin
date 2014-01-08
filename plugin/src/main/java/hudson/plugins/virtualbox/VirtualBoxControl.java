package hudson.plugins.virtualbox;

import java.util.List;

/**
 * @author Mihai Serban
 */
public interface VirtualBoxControl {
  public long startVm(VirtualBoxMachine machine, String snapshotName, String virtualMachineType);
  public long stopVm(VirtualBoxMachine machine, String snapshotName, String virtualMachineStopMode);

  public List<VirtualBoxMachine> getMachines(VirtualBoxCloud host);
  public String[] getSnapshots(String virtualMachineName);
  public String getMacAddress(VirtualBoxMachine machine);
  public void disconnect();

  public boolean isConnected();
}
