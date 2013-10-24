package hudson.plugins.virtualbox;

import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

import java.io.Serializable;

public class VirtualBoxListener extends RunListener<Run<?, ?>> implements Serializable {

    private static final long serialVersionUID = 5646627098165475986L;

    @Override
    public void onCompleted(final Run<?, ?> r, final TaskListener listener) {
        final Computer c = r.getExecutor().getOwner();
        if (c != null && c instanceof SlaveComputer) {
            final SlaveComputer sc = (SlaveComputer) c;
            final Node n = sc.getNode();
            if (n != null && n instanceof VirtualBoxSlave) {
                final VirtualBoxSlave slave = (VirtualBoxSlave) n;
                if (slave.isRevertAfterBuild()) {
                    sc.disconnect(OfflineCause.create(Messages._VirtualBoxListener_shuttingDownCause()));
                    sc.connect(false);
                }
            }
        }
        super.onCompleted(r, listener);
    }
}
