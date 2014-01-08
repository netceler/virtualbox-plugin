package hudson.plugins.virtualbox;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Mihai Serban
 */
public class VirtualBoxLogger {

    private static final Logger LOG = Logger.getLogger(VirtualBoxLogger.class.getName());

    private static final String PREFIX = "[VirtualBox]";

    public static void logInfo(final String message) {
        LOG.log(Level.INFO, "{0}{1}", new Object[] { PREFIX, message });
    }

    public static void logWarning(final String message) {
        LOG.log(Level.WARNING, "{0}{1}", new Object[] { PREFIX, message });
    }

    public static void logError(final String message) {
        LOG.log(Level.SEVERE, "{0}{1}", new Object[] { PREFIX, message });
    }

    public static void logFatalError(final String message, final Throwable thrown) {
        LOG.log(Level.SEVERE, PREFIX + message, thrown);
    }
}
