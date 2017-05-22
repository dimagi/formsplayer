package util;

import com.getsentry.raven.Raven;
import com.getsentry.raven.event.Breadcrumb;
import com.getsentry.raven.event.Breadcrumbs;
import com.getsentry.raven.event.EventBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by benrudolph on 4/27/17.
 */
public class SentryUtils {
    /**
     * We require a Raven instance in these Utils to ensure that the caller has instantiated
     * the Raven instance. Raven keeps track of the raven instance internally with
     * Raven.getStoredInstance(). If this call returns null, the Sentry call will fail.
     */

    private static final Log log = LogFactory.getLog(SentryUtils.class);

    public static void recordBreadcrumb(Raven raven, Breadcrumb breadcrumb) {
        if (raven == null) {
            return;
        }
        try {
            raven.getContext().recordBreadcrumb(breadcrumb);
        } catch (Exception e) {
            log.info("Error recording breadcrumb. Ensure that raven is configured. ", e);
        }
    }

    public static void sendRavenException(Raven raven, Exception exception) {
        if (raven == null) {
            return;
        }
        try {
            raven.sendException(exception);
        } catch (Exception e) {
            log.info("Error sending exception to Sentry. Ensure that raven is configured. ", e);
        }
    }

    public static void sendRavenEvent(Raven raven, EventBuilder event) {
        if (raven == null) {
            return;
        }
        try {
            raven.sendEvent(event);
        } catch (Exception e) {
            log.info("Error sending event to Sentry. Ensure that raven is configured. ", e);
        }
    }
}
