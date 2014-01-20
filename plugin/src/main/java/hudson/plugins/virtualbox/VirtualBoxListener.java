package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logFatalError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.slaves.SlaveComputer;

import java.io.Serializable;

@Extension
public class VirtualBoxListener extends RunListener<Run<?, ?>> implements Serializable {

    private static final long serialVersionUID = 5646627098165475986L;

    private static final int MAX_TRIES = 5;

    @Override
    public void onFinalized(final Run<?, ?> r) {
        super.onFinalized(r);

        if (r.getExecutor() != null && r.getExecutor().getOwner() != null) {
            final Computer c = r.getExecutor().getOwner();
            if (c != null && c instanceof SlaveComputer) {
                final SlaveComputer sc = (SlaveComputer) c;
                final Node n = sc.getNode();
                if (n != null && n instanceof VirtualBoxSlave) {
                    logInfo("Run " + r.getDisplayName() + " completed on computer " + c.getDisplayName()
                            + " which is a VirtualBox slave.");
                    final VirtualBoxSlave slave = (VirtualBoxSlave) n;
                    if (slave.getRevertAfterBuild()) {
                        logInfo("Reverting slave " + slave.getDisplayName()
                                + " to configured snapshot, as requested per configuration of this slave.");
                        VirtualBoxUtils.stopVm(
                                VirtualBoxPlugin.getVirtualBoxMachine(slave.getHostName(),
                                        slave.getVirtualMachineName()), slave.getSnapshotName(),
                                slave.getVirtualMachineStopMode());
                    }
                }
            }
        }
    }

    @Override
    public void onStarted(final Run<?, ?> r, final TaskListener listener) {
        if (r.getExecutor() != null && r.getExecutor().getOwner() != null) {
            final Computer c = r.getExecutor().getOwner();
            if (c != null && c instanceof VirtualBoxComputer) {
                final VirtualBoxComputer vbc = (VirtualBoxComputer) c;
                if (vbc.getNode() == null || vbc.getNode().getRevertAfterBuild()) {
                    logInfo("Run " + r.getDisplayName() + " starting on computer " + c.getDisplayName()
                            + ". Trying to start VirtualBoxSlave ...");
                    int nbTry = 0;
                    while (!vbc.isOnline() && nbTry < MAX_TRIES) {
                        try {
                            vbc.getLauncher().launch(vbc, listener);
                        } catch (final Exception e) {
                            logFatalError("Error while waiting for slave to be ready", e);
                        }
                        nbTry++;
                    }

                    if (vbc.isOnline()) {
                        logInfo("VirtualBoxSlave should be started");
                    } else {
                        throw new IllegalStateException("Unable to start VirtualBoxSlave for "
                                + c.getDisplayName());
                    }
                }
            }
        }

        super.onStarted(r, listener);
    }
}
