package com.foremen.service.dashboard.query;

/** The widget query exceeded the statement timeout (3 s). Mapped to an HTTP error by the data service. */
public class DashboardQueryTimeoutException extends RuntimeException {

    public DashboardQueryTimeoutException(Throwable cause) {
        super("Dashboard query timed out", cause);
    }
}
