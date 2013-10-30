package hudson.plugins.virtualbox;

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
import java.util.logging.Logger;

@Extension
public class VirtualBoxListener extends RunListener<Run<?, ?>> implements Serializable {

    private static final long serialVersionUID = 5646627098165475986L;

    private static final Logger LOG = Logger.getLogger(VirtualBoxListener.class.getName());

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
                    LOG.info("Run " + r.getDisplayName() + " completed on computer " + c.getDisplayName()
                            + " which is a VirtualBox slave.");
                    final VirtualBoxSlave slave = (VirtualBoxSlave) n;
                    if (slave.isRevertAfterBuild()) {
                        LOG.info("Reverting slave " + slave.getDisplayName()
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
                    LOG.info("Waiting maximum 30s for the VirtualBoxSlave to be reverted ...");
                    try {
                        future.get(30, SECONDS);
                    } catch (final Exception e) {
                        LOG.warning("Error while waiting for slave to be ready : " + e.getMessage());
                    }
                }
            }
        }

        super.onStarted(r, listener);
    }
}

class SlaveReverterCallable implements Callable<Void> {

    private static final Logger LOG = Logger.getLogger(SlaveReverterCallable.class.getName());

    private final SlaveComputer sc;

    private final VirtualBoxSlave slave;

    public SlaveReverterCallable(final SlaveComputer sc, final VirtualBoxSlave slave) {
        this.sc = sc;
        this.slave = slave;
    }

    public Void call() throws Exception {
        LOG.info("Disconnecting slave " + slave.getDisplayName());
        try {
            sc.disconnect(OfflineCause.create(Messages._VirtualBoxListener_shuttingDownCause())).get(10,
                    SECONDS);
            LOG.info("Slave disconnected, reconnecting ...");
            sc.connect(false);
        } catch (final Exception e) {
            LOG.warning("Reverting slave " + slave.getDisplayName() + " failed : " + e.getMessage());
        }
        return null;
    }

}