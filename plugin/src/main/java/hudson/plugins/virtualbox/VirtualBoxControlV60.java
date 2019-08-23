package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logFatalError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;

import java.util.ArrayList;
import java.util.List;

import hudson.util.Secret;

import org.virtualbox_6_0.*;

/**
 * @author Mihai Serban
 */
public final class VirtualBoxControlV60 implements VirtualBoxControl {

    private static final int WAIT_FOR_COMPLETION_TIMEOUT = 60000;

    private final VirtualBoxManager manager;

    private final IVirtualBox vbox;

    public VirtualBoxControlV60(final String hostUrl, final String userName, final Secret password) {
        logInfo("New instance of VirtualBoxControlV52, connecting to manager ...");
        manager = VirtualBoxManager.createInstance(null);
        manager.connect(hostUrl, userName, password.getPlainText());
        vbox = manager.getVBox();
    }

    @Override
    public void disconnect() {
        try {
            manager.disconnect();
            manager.cleanup();
        } catch (final VBoxException e) {
            logFatalError("Error while disconnecting from manager", e);
        }
    }

    @Override
    public boolean isConnected() {
        try {
            vbox.getVersion();
            return true;
        } catch (final VBoxException e) {
            logFatalError("Error while retrieving VirtualBox version", e);
            return false;
        }
    }

    /**
     * Get virtual machines installed on specified host.
     * @param host VirtualBox host
     * @return list of virtual machines installed on specified host
     */
    @Override
    public List<VirtualBoxMachine> getMachines(final VirtualBoxCloud host) {
        final List<VirtualBoxMachine> result = new ArrayList<VirtualBoxMachine>();
        for (final IMachine machine : vbox.getMachines()) {
            result.add(new VirtualBoxMachine(host, machine.getName()));
        }
        return result;
    }

    @Override
    public String[] getSnapshots(final String virtualMachineName) {
        final List<SnapshotData> snapshots = new ArrayList<SnapshotData>();
        for (final IMachine machine : vbox.getMachines()) {
            if (virtualMachineName.equals(machine.getName()) && machine.getSnapshotCount() > 0) {
                final ISnapshot root = findRootSnapshot(machine);
                fillSnapshot(snapshots, root);
            }
        }
        final String[] snapshotNames = new String[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            snapshotNames[i] = snapshots.get(i).name;
        }
        return snapshotNames;
    }

    private static ISnapshot findRootSnapshot(final IMachine machine) {
        ISnapshot root = machine.getCurrentSnapshot();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root;
    }

    private List<SnapshotData> fillSnapshot(List<SnapshotData> snapshotList, final ISnapshot snapshot) {
        if (snapshot != null) {
            final SnapshotData snapshotData = new SnapshotData();
            snapshotData.name = snapshot.getName();
            snapshotData.id = snapshot.getId();
            snapshotList.add(snapshotData);
            if (snapshot.getChildren() != null) {
                for (final ISnapshot child : snapshot.getChildren()) {
                    // call fillSnapshot recursive
                    snapshotList = fillSnapshot(snapshotList, child);
                }
            }
        }
        return snapshotList;
    }

    /**
     * Starts specified VirtualBox virtual machine.
     * @param vbMachine virtual machine to start
     * @param type session type (can be headless, vrdp, gui, sdl)
     * @return result code
     */
    @Override
    public synchronized long startVm(final VirtualBoxMachine vbMachine, final String snapshotName,
            final String type) {
        logInfo("Starting machine " + vbMachine.getName() + " ...");
        final IMachine machine = vbox.findMachine(vbMachine.getName());
        if (null == machine) {
            logError("Cannot find node: " + vbMachine.getName());
            return -1;
        }

        ISnapshot snapshot = null;
        if (snapshotName != null) {
            logInfo("Looking for snapshot " + snapshotName + " ...");
            try {
                snapshot = machine.findSnapshot(snapshotName);
            } catch (Exception e) {}
        }

        // states diagram:
        // https://www.virtualbox.org/sdkref/_virtual_box_8idl.html#80b08f71210afe16038e904a656ed9eb
        logInfo("Checking state of virtual machine " + vbMachine.getName() + " ...");
        MachineState state = machine.getState();
        ISession session;
        IProgress progress;

        // wait for transient states to finish
        while (state.value() >= MachineState.FirstTransient.value()
                && state.value() <= MachineState.LastTransient.value()) {
            logInfo("node " + vbMachine.getName() + " in state " + state.toString() + "(" + state.value()
                    + ")");
            try {
                wait(1000);
            } catch (final InterruptedException e) {
            }
            state = machine.getState();
        }

        if (MachineState.Running == state) {
            logInfo("node " + vbMachine.getName() + " in state " + state.toString());
            logInfo("node " + vbMachine.getName() + " started");
            return 0;
        }

        if (MachineState.Stuck == state || MachineState.Paused == state || MachineState.Aborted == state
                || MachineState.PoweredOff == state) {
            logInfo("starting node " + vbMachine.getName() + " from state " + state.toString());
            try {
                session = getSession(machine, LockType.Shared);
            } catch (final Exception e) {
                logFatalError("node " + vbMachine.getName() + " openMachineSession: " + e.getMessage(), e);
                return -1;
            }

            long result = 0; // success
            try {
                progress = null;
                if (MachineState.Paused == state) {
                    // from Paused call resume
                    logInfo("Resuming machine " + vbMachine.getName() + " ...");
                    session.getConsole().resume();
                } else if (MachineState.Stuck == state) {
                    // for Stuck state call powerDown and go to PoweredOff state
                    logInfo("Powering down machine " + vbMachine.getName() + " ....");
                    progress = session.getConsole().powerDown();
                    logInfo("Waiting for machine " + vbMachine.getName() + " to be powered down ...");
                    if (snapshot != null) {
                        progress.waitForCompletion(WAIT_FOR_COMPLETION_TIMEOUT);
                        releaseSession(session, machine);
                        session = getSession(machine, LockType.Write);
                        logInfo("Reverting node " + vbMachine.getName() + " to snapshot " + snapshotName);
                        progress = session.getMachine().restoreSnapshot(snapshot);
                        logInfo("Waiting for machine " + vbMachine.getName() + " to be reverted ...");
                    }
                } else if ((MachineState.Aborted == state || MachineState.PoweredOff == state)
                        && snapshot != null) {
                    releaseSession(session, machine);
                    session = getSession(machine, LockType.Write);
                    logInfo("Reverting node " + vbMachine.getName() + " to snapshot " + snapshotName);
                    progress = session.getMachine().restoreSnapshot(snapshot);
                    logInfo("Waiting for machine " + vbMachine.getName() + " to be reverted ...");
                }

                if (null != progress) {
                    progress.waitForCompletion(WAIT_FOR_COMPLETION_TIMEOUT);
                    result = progress.getResultCode();
                }
            } finally {
                releaseSession(session, machine);
            }
            if (0 != result) {
                logError("node " + vbMachine.getName() + " error: " + getVBProcessError(progress));
                return -1;
            }

            // continue from PoweredOff state
            state = machine.getState(); // update state
        }

        logInfo("starting node " + vbMachine.getName() + " from state " + state.toString());

        // powerUp from Saved, Aborted or PoweredOff states
        long result = 0;
        session = getSession(null, LockType.VM);
        try {
            logInfo("Launching machine " + vbMachine.getName());
            progress = machine.launchVMProcess(session, type, "");
            logInfo("Wiating for machine " + vbMachine.getName() + " to be launched");
            progress.waitForCompletion(WAIT_FOR_COMPLETION_TIMEOUT);
            result = progress.getResultCode();
        } finally {
            releaseSession(session, machine);
        }

        if (0 != result) {
            logError("node " + vbMachine.getName() + " error: " + getVBProcessError(progress));
        } else {
            logInfo("node " + vbMachine.getName() + " started");
        }

        return result;
    }

    /**
     * Stops specified VirtualBox virtual machine.
     * @param vbMachine virtual machine to stop
     * @return result code
     */
    @Override
    public synchronized long stopVm(final VirtualBoxMachine vbMachine, final String snapshotName,
            final String stopMode) {
        logInfo("Stopping machine " + vbMachine.getName() + " ...");
        final IMachine machine = vbox.findMachine(vbMachine.getName());
        if (null == machine) {
            logError("Cannot find node: " + vbMachine.getName());
            return -1;
        }

        ISnapshot snapshot = null;
        if (snapshotName != null) {
            logInfo("Looking for snapshot " + snapshotName + " ...");
            snapshot = machine.findSnapshot(snapshotName);
        }

        // states diagram:
        // https://www.virtualbox.org/sdkref/_virtual_box_8idl.html#80b08f71210afe16038e904a656ed9eb
        logInfo("Checking state of virtual machine " + vbMachine.getName() + " ...");
        MachineState state = machine.getState();
        ISession session;
        IProgress progress;

        // wait for transient states to finish
        while (state.value() >= MachineState.FirstTransient.value()
                && state.value() <= MachineState.LastTransient.value()) {
            logInfo("node " + vbMachine.getName() + " in state " + state.toString() + "(" + state.value()
                    + ")");
            try {
                wait(1000);
            } catch (final InterruptedException e) {
            }
            state = machine.getState();
        }

        logInfo("stopping node " + vbMachine.getName() + " from state " + state.toString());

        if (MachineState.Aborted == state || MachineState.PoweredOff == state
                || MachineState.Saved == state) {
            logInfo("node " + vbMachine.getName() + " stopped");
            return 0;
        }

        try {
            session = getSession(machine, LockType.Shared);
        } catch (final Exception e) {
            logFatalError("node " + vbMachine.getName() + " openMachineSession: " + e.getMessage(), e);
            return -1;
        }

        long result = 0;
        try {
            if (MachineState.Stuck == state || "powerdown".equals(stopMode)) {
                // for Stuck state call powerDown and go to PoweredOff state
                logInfo("Powering down machine " + vbMachine.getName() + " ....");
                progress = session.getConsole().powerDown();
                logInfo("Waiting for machine " + vbMachine.getName() + " to be powered down ...");
                if (snapshot != null) {
                    progress.waitForCompletion(WAIT_FOR_COMPLETION_TIMEOUT);

                    releaseSession(session, machine);
                    session = getSession(machine, LockType.Write);
                    logInfo("Reverting node " + vbMachine.getName() + " to snapshot " + snapshotName);
                    progress = session.getMachine().restoreSnapshot(snapshot);
                    logInfo("Waiting for machine " + vbMachine.getName() + " to be reverted ...");
                }
            } else {
                // Running or Paused
                logInfo("Saving state of machine " + vbMachine.getName());
                progress = session.getMachine().saveState();
                logInfo("Waiting for machine " + vbMachine.getName() + " to be saved");
            }

            progress.waitForCompletion(WAIT_FOR_COMPLETION_TIMEOUT);
            result = progress.getResultCode();
        } finally {
            releaseSession(session, machine);
        }

        if (0 != result) {
            logError("node " + vbMachine.getName() + " error: " + getVBProcessError(progress));
        } else {
            logInfo("node " + vbMachine.getName() + " stopped");
        }

        return result;
    }

    /**
     * MAC Address of specified virtual machine.
     * @param vbMachine virtual machine
     * @return MAC Address of specified virtual machine
     */
    @Override
    public String getMacAddress(final VirtualBoxMachine vbMachine) {
        final IMachine machine = vbox.findMachine(vbMachine.getName());
        final String macAddress = machine.getNetworkAdapter(0L).getMACAddress();
        return macAddress;
    }

    private String getVBProcessError(final IProgress progress) {
        if (0 == progress.getResultCode()) {
            return "";
        }

        final StringBuilder sb = new StringBuilder("");
        IVirtualBoxErrorInfo errInfo = progress.getErrorInfo();
        while (null != errInfo) {
            sb.append(errInfo.getText());
            sb.append("\n");
            errInfo = errInfo.getNext();
        }
        return sb.toString();
    }

    private boolean isTransientState(final SessionState state) {
        return SessionState.Spawning == state || SessionState.Unlocking == state;
    }

    private ISession getSession(final IMachine machine, final LockType lockType) {
        logInfo("Getting session" + (machine != null ? " for machine " + machine.getName() : ""));
        final ISession s = manager.getSessionObject();
        try {
            if (null != machine) {
                int nbTry = 0;
                do {
                    wait(500);
                    logInfo("Locking machine (session in state: " + s.getState() + ")");
                    try {
                        machine.lockMachine(s, lockType);
                    } catch (final Exception ex) {
                        if (!ex.getMessage().contains("is already locked for a session")) {
                            throw ex;
                        }
                    }
                } while (nbTry++ < 3 && !s.getState().equals(SessionState.Locked));
                logInfo("Waiting for machine transient states ...");
                while (isTransientState(machine.getSessionState())) {
                    wait(500);
                }
            }

            logInfo("Waiting for session transient states ...");
            while (isTransientState(s.getState())) {
                wait(500);
            }

            logInfo("Session OK" + (machine != null ? " for machine " + machine.getName() : ""));
        } catch (final Exception e) {
            logFatalError("Exception while getting session"
                    + (machine != null ? " for machine " + machine.getName() : ""), e);
            throw new RuntimeException("Unable to retrieve session", e);
        }
        return s;
    }

    private void releaseSession(final ISession s, final IMachine machine) {
        try {
            logInfo("Waiting for machine and session transient states ...");
            while (isTransientState(machine.getSessionState()) || isTransientState(s.getState())) {
                wait(500);
            }

            try {
                int nbTry = 0;
                do {
                    wait(500);
                    logInfo("Unlocking machine " + machine.getName());
                    s.unlockMachine();
                } while (nbTry++ < 3 && !s.getState().equals(SessionState.Unlocked));
            } catch (final VBoxException e) {
                logFatalError("Exception while unlocking machine " + machine.getName(), e);
            }

            logInfo("Waiting for machine and session transient states ...");
            while (isTransientState(machine.getSessionState()) || isTransientState(s.getState())) {
                wait(500);
            }

            logInfo("Session released OK for machine " + machine.getName());
        } catch (final Exception e) {
            logFatalError("Exception while releasing session for machine " + machine.getName(), e);
        }
    }
}