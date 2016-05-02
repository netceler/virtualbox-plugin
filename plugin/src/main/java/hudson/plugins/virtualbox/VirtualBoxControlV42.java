package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logFatalError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logWarning;

import java.util.ArrayList;
import java.util.List;

import hudson.util.Secret;

import org.virtualbox_4_2.*;

/**
 * @author Mihai Serban
 */
public final class VirtualBoxControlV42 implements VirtualBoxControl {

    private final VirtualBoxManager manager;
    private final IVirtualBox vbox;

    public VirtualBoxControlV42(String hostUrl, String userName, Secret password) {
        manager = VirtualBoxManager.createInstance(null);
        manager.connect(hostUrl, userName, password.getPlainText());
        vbox = manager.getVBox();
    }

    public synchronized void disconnect() {
        try {
            manager.disconnect();
        } catch (VBoxException e) {}
    }

    public synchronized boolean isConnected() {
        try {
            vbox.getVersion();
            return true;
        } catch (VBoxException e) {
            return false;
        }
    }

    /**
     * Get virtual machines installed on specified host.
     *
     * @param host VirtualBox host
     * @return list of virtual machines installed on specified host
     */
    public synchronized List<VirtualBoxMachine> getMachines(VirtualBoxCloud host) {
        List<VirtualBoxMachine> result = new ArrayList<VirtualBoxMachine>();
        for (IMachine machine : vbox.getMachines()) {
            result.add(new VirtualBoxMachine(host, machine.getName()));
        }
        return result;
    }

    public String[] getSnapshots(String virtualMachineName) {
        List<SnapshotData> snapshots = new ArrayList<SnapshotData>();
        for (IMachine machine : vbox.getMachines()) {
            if (virtualMachineName.equals(machine.getName()) && machine.getSnapshotCount() > 0) {
                ISnapshot root = findRootSnapshot(machine);
                fillSnapshot(snapshots, root);
            }
        }
        String[] snapshotNames = new String[snapshots.size()];
        for (int i = 0 ; i < snapshots.size() ; i++) {
            snapshotNames[i] = snapshots.get(i).name;
        }
        return snapshotNames;
    }

    private static ISnapshot findRootSnapshot(IMachine machine) {
        ISnapshot root = machine.getCurrentSnapshot();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root;
    }

    private List<SnapshotData> fillSnapshot(List<SnapshotData> snapshotList, ISnapshot snapshot) {
        if (snapshot != null) {
            SnapshotData snapshotData = new SnapshotData();
            snapshotData.name = snapshot.getName();
            snapshotData.id = snapshot.getId();
            snapshotList.add(snapshotData);
            if (snapshot.getChildren() != null) {
                for (ISnapshot child : snapshot.getChildren()) {
                    // call fillSnapshot recursive
                    snapshotList = fillSnapshot(snapshotList, child);
                }
            }
        }
        return snapshotList;
    }

    /**
     * Starts specified VirtualBox virtual machine.
     *
     * @param vbMachine virtual machine to start
     * @param type      session type (can be headless, vrdp, gui, sdl)
     * @return result code
     */
    public synchronized long startVm(VirtualBoxMachine vbMachine, String snapshotName, String type) {
        IMachine machine = vbox.findMachine(vbMachine.getName());
        if (null == machine) {
            logError("Cannot find node: " + vbMachine.getName());
            return -1;
        }

        ISnapshot snapshot = null;
        if (snapshotName != null) {
            snapshot = machine.findSnapshot(snapshotName);
        }

        // states diagram: https://www.virtualbox.org/sdkref/_virtual_box_8idl.html#80b08f71210afe16038e904a656ed9eb
        MachineState state = machine.getState();
        ISession session;
        IProgress progress;

        // wait for transient states to finish
        while (state.value() >= MachineState.FirstTransient.value() && state.value() <= MachineState.LastTransient.value()) {
            logInfo("node " + vbMachine.getName() + " in state " + state.toString());
            try {
                wait(1000);
            } catch (InterruptedException e) {}
            state = machine.getState();
        }

        if (MachineState.Running == state) {
            logInfo("node " + vbMachine.getName() + " in state " + state.toString());
            logInfo("node " + vbMachine.getName() + " started");
            return 0;
        }

        if (MachineState.Stuck == state || MachineState.Paused == state) {
            logInfo("starting node " + vbMachine.getName() + " from state " + state.toString());
            try {
                session = getSession(machine);
            } catch (Exception e) {
                logFatalError("node " + vbMachine.getName() + " openMachineSession: " + e.getMessage(), e);
                return -1;
            }

            progress = null;
            if (MachineState.Stuck == state) {
                // for Stuck state call powerDown and go to PoweredOff state
                progress = session.getConsole().powerDown();
            } else if (MachineState.Paused == state) {
                // from Paused call resume
                session.getConsole().resume();
            }

            long result = 0; // success
            if (null != progress) {
                progress.waitForCompletion(-1);
                result = progress.getResultCode();
            }

            releaseSession(session, machine);
            if (0 != result) {
                logError("node " + vbMachine.getName() + " error: " + getVBProcessError(progress));
                return -1;
            }

            if (MachineState.Stuck != state) {
                logInfo("node " + vbMachine.getName() + " started");
                return 0;
            }
            // continue from PoweredOff state
            state = machine.getState(); // update state
        }

        logInfo("starting node " + vbMachine.getName() + " from state " + state.toString());

        // powerUp from Saved, Aborted or PoweredOff states
        if (snapshot != null) {
            logInfo("Reverting node " + vbMachine.getName() + " to snapshot " + snapshotName);
            session = getSession(machine);
            progress = session.getConsole().restoreSnapshot(snapshot);
            progress.waitForCompletion(-1);
            session.unlockMachine();
        }
        session = getSession(null);
        String env = "";
        progress = machine.launchVMProcess(session, type, env);
        progress.waitForCompletion(-1);
        long result = progress.getResultCode();
        releaseSession(session, machine);

        if (0 != result) {
            logError("node " + vbMachine.getName() + " error: " + getVBProcessError(progress));
        } else {
            logInfo("node " + vbMachine.getName() + " started");
        }

        return result;
    }

    /**
     * Stops specified VirtualBox virtual machine.
     *
     * @param vbMachine virtual machine to stop
     * @return result code
     */
    public synchronized long stopVm(VirtualBoxMachine vbMachine, String snapshotName, String stopMode) {
        IMachine machine = vbox.findMachine(vbMachine.getName());
        if (null == machine) {
            logError("Cannot find node: " + vbMachine.getName());
            return -1;
        }

        ISnapshot snapshot = null;
        if (snapshotName != null) {
            snapshot = machine.findSnapshot(snapshotName);
        }

        // states diagram: https://www.virtualbox.org/sdkref/_virtual_box_8idl.html#80b08f71210afe16038e904a656ed9eb
        MachineState state = machine.getState();
        ISession session;
        IProgress progress;

        // wait for transient states to finish
        while (state.value() >= MachineState.FirstTransient.value() && state.value() <= MachineState.LastTransient.value()) {
            logInfo("node " + vbMachine.getName() + " in state " + state.toString());
            try {
                wait(1000);
            } catch (InterruptedException e) {}
            state = machine.getState();
        }

        logInfo("stopping node " + vbMachine.getName() + " from state " + state.toString());

        if (MachineState.Aborted == state || MachineState.PoweredOff == state
                || MachineState.Saved == state) {
            logInfo("node " + vbMachine.getName() + " stopped");
            return 0;
        }

        try {
            session = getSession(machine);
        } catch (Exception e) {
            logFatalError("node " + vbMachine.getName() + " openMachineSession: " + e.getMessage(), e);
            return -1;
        }

        if (MachineState.Stuck == state || "powerdown".equals(stopMode)) {
            // for Stuck state call powerDown and go to PoweredOff state
            progress = session.getConsole().powerDown();
        } else if (snapshot != null) {
            logInfo("Reverting node " + vbMachine.getName() + " to snapshot " + snapshotName);
            progress = session.getConsole().powerDown();
            progress.waitForCompletion(-1);

            session = getSession(machine);
            progress = session.getConsole().restoreSnapshot(snapshot);
            progress.waitForCompletion(-1);
            session.unlockMachine();
        } else {
            // Running or Paused
            progress = session.getConsole().saveState();
        }

        progress.waitForCompletion(-1);
        long result = progress.getResultCode();

        releaseSession(session, machine);

        if (0 != result) {
            logError("node " + vbMachine.getName() + " error: " + getVBProcessError(progress));
        } else {
            logInfo("node " + vbMachine.getName() + " stopped");
        }

        return result;
    }

    /**
     * MAC Address of specified virtual machine.
     *
     * @param vbMachine virtual machine
     * @return MAC Address of specified virtual machine
     */
    public synchronized String getMacAddress(VirtualBoxMachine vbMachine) {
        IMachine machine = vbox.findMachine(vbMachine.getName());
        String macAddress = machine.getNetworkAdapter(0L).getMACAddress();
        return macAddress;
    }

    private String getVBProcessError(IProgress progress) {
        if (0 == progress.getResultCode()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("");
        IVirtualBoxErrorInfo errInfo = progress.getErrorInfo();
        while (null != errInfo) {
            sb.append(errInfo.getText());
            sb.append("\n");
            errInfo = errInfo.getNext();
        }
        return sb.toString();
    }

    private boolean isTransientState(SessionState state) {
        return SessionState.Spawning == state || SessionState.Unlocking == state;
    }

    private ISession getSession(IMachine machine) {
        ISession s = manager.getSessionObject();
        if (null != machine) {
            machine.lockMachine(s, LockType.Shared);
            while (isTransientState(machine.getSessionState())) {
                try {
                    wait(500);
                } catch (InterruptedException e) {}
            }
        }

        while (isTransientState(s.getState())) {
            try {
                wait(500);
            } catch (InterruptedException e) {}
        }

        return s;
    }

    private void releaseSession(ISession s, IMachine machine) {
        while (isTransientState(machine.getSessionState()) || isTransientState(s.getState())) {
            try {
                wait(500);
            } catch (InterruptedException e) {}
        }

        try {
            s.unlockMachine();
        } catch (VBoxException e) {}

        while (isTransientState(machine.getSessionState()) || isTransientState(s.getState())) {
            try {
                wait(500);
            } catch (InterruptedException e) {}
        }
    }
}
