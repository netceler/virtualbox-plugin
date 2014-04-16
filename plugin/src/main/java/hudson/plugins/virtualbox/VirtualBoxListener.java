package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logFatalError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;
import static java.lang.Thread.sleep;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.slaves.OfflineCause;

import java.io.Serializable;

@Extension
public class VirtualBoxListener extends RunListener<Run<?, ?>> implements Serializable {

    private static final long serialVersionUID = 5646627098165475986L;

    @Override
    public void onFinalized(final Run<?, ?> r) {
        super.onFinalized(r);

        if (r.getExecutor() != null && r.getExecutor().getOwner() != null) {
            final Computer c = r.getExecutor().getOwner();
            if (c != null && c instanceof VirtualBoxComputer) {
                final VirtualBoxComputer sc = (VirtualBoxComputer) c;
                final Node n = sc.getNode();
                if (n != null && n instanceof VirtualBoxSlave) {
                    logInfo("Run " + r.getDisplayName() + " completed on computer " + c.getDisplayName()
                            + " which is a VirtualBox slave.");
                    final VirtualBoxSlave slave = (VirtualBoxSlave) n;
                    if (slave.getRevertAfterBuild()) {
                        logInfo("Reverting slave " + slave.getDisplayName()
                                + " to configured snapshot, as requested per configuration of this slave.");
                        try {
                            slave.getComputer().getChannel().syncLocalIO();
                            slave.getComputer().getChannel().close();
                            c.disconnect(new OfflineCause.ByCLI("Reverted by VirtualBox plugin")).get();
                            slave.getComputer().waitUntilOffline();
                            sleep(15000);
                            logInfo("End of onFinalized() for slave " + slave.getDisplayName());
                        } catch (final Exception ex) {
                            logFatalError("Error while reverting slave " + slave.getDisplayName(), ex);
                        }
                    }
                }
            }
        }
    }

}
