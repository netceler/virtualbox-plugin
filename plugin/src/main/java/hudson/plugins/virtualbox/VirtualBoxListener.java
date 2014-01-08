package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logFatalError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;
import static java.util.concurrent.TimeUnit.SECONDS;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

@Extension
public class VirtualBoxListener extends RunListener<Run<?, ?>> implements Serializable {

    private static final long serialVersionUID = 5646627098165475986L;

    private final Map<SlaveComputer, Future<?>> pendings = new HashMap<SlaveComputer, Future<?>>();

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
                        pendings.put(sc,
                                Computer.threadPoolForRemoting.submit(new SlaveReverterCallable(sc, slave)));
                    }
                }
            }
        }
    }

    @Override
    public void onStarted(final Run<?, ?> r, final TaskListener listener) {
        if (r.getExecutor() != null && r.getExecutor().getOwner() != null) {
            final Computer c = r.getExecutor().getOwner();
            if (c != null && c instanceof SlaveComputer) {
                final SlaveComputer sc = (SlaveComputer) c;
                final Future<?> future = pendings.remove(sc);
                if (future != null) {
                    logInfo("Run " + r.getDisplayName() + " starting on computer " + c.getDisplayName()
                            + ". Waiting maximum 30s for the VirtualBoxSlave to be reverted ...");
                    try {
                        future.get(30, SECONDS);
                        logInfo("VirtualBoxSlave should be reverted");
                    } catch (final Exception e) {
                        logFatalError("Error while waiting for slave to be ready", e);
                    }
                }
            }
        }

        super.onStarted(r, listener);
    }
}

class SlaveReverterCallable implements Callable<Void> {

    private final SlaveComputer sc;

    private final VirtualBoxSlave slave;

    public SlaveReverterCallable(final SlaveComputer sc, final VirtualBoxSlave slave) {
        this.sc = sc;
        this.slave = slave;
    }

    public Void call() throws Exception {
        logInfo("Disconnecting slave " + slave.getDisplayName());
        try {
            sc.disconnect(OfflineCause.create(Messages._VirtualBoxListener_shuttingDownCause())).get(10,
                    SECONDS);
            logInfo("Slave disconnected, reconnecting ...");
            sc.connect(false);
            logInfo("Slave reconnected.");
        } catch (final Exception e) {
            logFatalError("Reverting slave " + slave.getDisplayName() + " failed", e);
        }
        return null;
    }

}