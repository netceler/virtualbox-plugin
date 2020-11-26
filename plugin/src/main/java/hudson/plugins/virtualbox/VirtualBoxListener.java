package hudson.plugins.virtualbox;

import static hudson.plugins.virtualbox.VirtualBoxLogger.logFatalError;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logInfo;
import static hudson.plugins.virtualbox.VirtualBoxLogger.logWarning;
import static java.lang.Thread.sleep;

import java.io.Serializable;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.remoting.Channel;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

@Extension
public class VirtualBoxListener extends RunListener<Run<?, ?>> implements Serializable {

    private static final long serialVersionUID = 5646627098165475986L;

    @Override
    public void onFinalized(final Run<?, ?> r) {
        super.onFinalized(r);

        final Executor e = r.getExecutor();
        if (e != null) {
            final Computer c = e.getOwner();
            if (c != null && c instanceof VirtualBoxComputer) {
                final VirtualBoxComputer sc = (VirtualBoxComputer) c;
                final VirtualBoxSlave slave = sc.getNode();
                if (slave != null) {
                    logInfo("Run " + r.getDisplayName() + " completed on computer " + c.getDisplayName()
                            + " which is a VirtualBox slave.");
                    if (slave.getRevertAfterBuild()) {
                        logInfo("Reverting slave " + slave.getDisplayName()
                                + " to configured snapshot, as requested per configuration of this slave.");
                        try {
                            final SlaveComputer computer = slave.getComputer();
                            if (computer != null) {
                                final Channel channel = computer.getChannel();
                                if (channel != null) {
                                    computer.getChannel().syncLocalIO();
                                    computer.getChannel().close();
                                } else {
                                    logWarning("Channel of computer " + c.getDisplayName() + " is null");
                                }
                                c.disconnect(new OfflineCause.ByCLI("Reverted by VirtualBox plugin")).get();
                                computer.waitUntilOffline();
                                sleep(15000);
                                logInfo("End of onFinalized() for slave " + slave.getDisplayName());
                            } else {
                                logWarning("Computer " + c.getDisplayName() + " is null");
                            }
                        } catch (final Exception ex) {
                            logFatalError("Error while reverting slave " + slave.getDisplayName(), ex);
                        }
                    }
                }
            }
        }
    }

}
