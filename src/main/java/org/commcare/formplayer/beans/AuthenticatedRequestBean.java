package org.commcare.formplayer.beans;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import org.commcare.formplayer.util.UserUtils;

/**
 * The AuthenticatedRequestBean should be used for requests that need to be authenticated with HQ.
 * This Bean will ensure the necessary json values are present in the request.
 */
public class AuthenticatedRequestBean {

    protected String domain;
    protected String username;
    protected String restoreAs;
    protected boolean mustRestore;

    private String sessionId;
    private String restoreAsCaseId;

    private int timezoneOffsetMillis = -1;
    private String timezoneFromBrowser = null;
    private String windowWidth;
    private boolean keepAPMTraces = false;
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getRestoreAs() {
        return restoreAs;
    }

    public void setRestoreAs(String restoreAs) {
        this.restoreAs = restoreAs;
    }

    public String getUsernameDetail() {
        if (restoreAsCaseId != null) {
            return UserUtils.getRestoreAsCaseIdUsername(restoreAsCaseId);
        }
        if (restoreAs != null) {
            return username + "_" + restoreAs;
        }
        return username;
    }

    @Override
    public String toString() {
        return "Authenticated request bean wih username=" + username +
                ", domain=" + domain +
                ", restoreAs=" + restoreAs;
    }

    public boolean isMustRestore() {
        return mustRestore;
    }

    public void setMustRestore(boolean mustRestore) {
        this.mustRestore = mustRestore;
    }

    @JsonGetter(value = "tz_offset_millis")
    public int getTzOffset() {
        return this.timezoneOffsetMillis;
    }

    @JsonSetter(value = "tz_offset_millis")
    public void setTzOffset(int offset) {
        this.timezoneOffsetMillis = offset;
    }

    @JsonGetter(value = "tz_from_browser")
    public String getTzFromBrowser() {
        return this.timezoneFromBrowser;
    }

    @JsonSetter(value = "tz_from_browser")
    public void setTzFromBrowser(String offset) {
        this.timezoneFromBrowser = offset;
    }

    @JsonGetter(value = "restoreAsCaseId")
    public String getRestoreAsCaseId() {
        return restoreAsCaseId;
    }

    @JsonSetter(value = "restoreAsCaseId")
    public void setRestoreAsCaseId(String restoreAsCaseId) {
        this.restoreAsCaseId = restoreAsCaseId;
    }

    @JsonGetter(value = "windowWidth")
    public String getWindowWidth() {
        return windowWidth;
    }

    @JsonSetter(value = "windowWidth")
    public void setWindowWidth(String windowWidth) {
        this.windowWidth = windowWidth;
    }

    @JsonGetter(value = "keepAPMTraces")
    public boolean getKeepAPMTraces() {
        return keepAPMTraces;
    }

    @JsonSetter(value = "keepAPMTraces")
    public void setKeepAPMTraces(boolean keepAPMTraces) {
        this.keepAPMTraces = keepAPMTraces;
    }
}
