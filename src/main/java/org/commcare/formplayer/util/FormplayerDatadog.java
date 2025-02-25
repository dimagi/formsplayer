package org.commcare.formplayer.util;

import static org.commcare.formplayer.util.Constants.TOGGLE_DETAILED_TAGGING;

import com.timgroup.statsd.StatsDClient;

import org.commcare.formplayer.beans.auth.FeatureFlagChecker;
import org.commcare.formplayer.beans.auth.HqUserDetailsBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import datadog.trace.api.DDTags;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Wrapper for the Datadog Java client Provides the ability to set tags throughout the request that
 * will be appended to every datadog request for the remainder of the object's life (scoped at a
 * formplayer request level)
 */
public class FormplayerDatadog {

    /**
     * Internal class to represent tag and easily format for sending to datadog
     */
    public static class Tag {
        private String name;
        private String value;

        public Tag(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public String getName() {
            return name;
        }

        public String formatted() {
            return name + ":" + value;
        }
    }

    /**
     * Datadog's java client that communicates with agent
     */
    private StatsDClient datadogClient;
    /**
     * List of domains that we want detailed tags enabled for. Set in application.properties
     */
    private Set<String> domainsWithDetailedTagging;
    /**
     * Tags only eligible for domains with detailed tagging enabled. Set in application.properties
     */
    private Set<String> detailedTagNames;
    /**
     * Tags appended to every datadog request
     */
    private Map<String, Tag> requestScopedTags;
    /**
     * Domain that formplayer request originates from
     */
    private String domain;

    // Constructor, Getters, Setters
    public FormplayerDatadog(StatsDClient datadogClient,
            List<String> detailedTagNames) {
        this.datadogClient = datadogClient;
        this.detailedTagNames = new HashSet<>(detailedTagNames);
        this.requestScopedTags = new HashMap<>();
    }

    public List<Tag> getRequestScopedTags() {
        return new ArrayList<>(this.requestScopedTags.values());
    }

    public Map<String, String> getRequestScopedTagNameAndValue() {
        Map<String, String> tagNameAndValueMap = new HashMap<>();
        for (Map.Entry<String, Tag> entry : requestScopedTags.entrySet()) {
            Tag tag = entry.getValue();
            tagNameAndValueMap.put(tag.getName(), tag.getValue());
        }
        return tagNameAndValueMap;
    }

    public Set<String> getDetailedTagNames() {
        return this.detailedTagNames;
    }

    // Tag Management Methods

    /**
     * Adds a tag that will be used for the remainder of the request If the tag is not eligible for
     * this domain, it will not be added
     *
     * @param name  - name of the tag (e.g., domain)
     * @param value - value of the tag (e.g., test_domain)
     */
    public void addRequestScopedTag(String name, String value) {
        // get correct value to send (only send unique tag value if domain is eligible)
        String valueToSend = getTagValueToSend(name, value);
        Tag tag = new Tag(name, valueToSend);
        requestScopedTags.put(name, tag);
    }

    /**
     * Removes all request level tags
     */
    public void clearRequestScopedTags() {
        requestScopedTags.clear();
    }

    // Datadog API Wrappers

    /**
     * Wrapper for StatsDClient's recordExecutionTime method
     *
     * @param aspect        - name of the metric
     * @param value         - value of the metric
     * @param transientTags - "one time" tags in addition to requestScopedTags NOTE: these tags have
     *                      higher priority than request scoped tags (in the case of duplicate tag
     *                      names)
     */
    public void recordExecutionTime(String aspect, final long value, List<Tag> transientTags) {
        List<String> tagsToSend = getTagsToSend(transientTags);
        String[] datadogTags = tagsToSend.toArray(new String[tagsToSend.size()]);
        datadogClient.recordExecutionTime(aspect, value, datadogTags);
    }

    /**
     * Wrapper for StatsDClient's increment method
     *
     * @param aspect        - name of the metric
     * @param transientTags - "one time" tags in addition to requestScopedTags NOTE: these tags have
     *                      higher priority than request scoped tags (in the case of duplicate tag
     *                      names)
     */
    public void increment(String aspect, List<Tag> transientTags) {
        List<String> tagsToSend = getTagsToSend(transientTags);
        String[] datadogTags = tagsToSend.toArray(new String[tagsToSend.size()]);
        datadogClient.increment(aspect, datadogTags);
    }

    public void incrementErrorCounter(String metric, HttpServletRequest req) {
        incrementErrorCounter(metric, req, null);
    }

    public void incrementErrorCounter(String metric, HttpServletRequest req, String tag) {
        String user = "unknown";
        String domain = "unknown";

        Optional<HqUserDetailsBean> userDetails = RequestUtils.getUserDetails();
        if (userDetails.isPresent()) {
            user = userDetails.get().getDomain();
            domain = userDetails.get().getUsername();
        }

        ArrayList<String> tags = new ArrayList<>();
        tags.add("domain:" + domain);
        tags.add("user:" + user);
        tags.add("request:" + req.getRequestURI());
        if (tag != null) {
            tags.add("tag:" + tag);
        }

        datadogClient.increment(
                metric,
                tags.toArray(new String[tags.size()])
        );
    }

    // Private Helpers

    /**
     * Filters out tags that are not eligible for the current domain and formats
     *
     * @param transientTags - "one time" tags in addition to requestScopedTags NOTE: these tags have
     *                      higher priority than request scoped tags (in the case of duplicate tag
     *                      names)
     * @return - list of formatted tags to send
     */
    private List<String> getTagsToSend(List<Tag> transientTags) {
        List<String> formattedTags = new ArrayList<>();
        // transient keys take precedence over existing keys
        HashSet<String> transientKeys = new HashSet<String>();

        for (Tag tag : transientTags) {
            String tagValueToSend = getTagValueToSend(tag.name, tag.value);
            Tag tempTag = new Tag(tag.name, tagValueToSend);
            formattedTags.add(tempTag.formatted());
            // keep track of transient tag names
            transientKeys.add(tempTag.name);
        }

        // append request scoped tags
        for (Tag tag : getRequestScopedTags()) {
            if (!transientKeys.contains(tag.name)) {
                formattedTags.add(tag.formatted());
            }
        }

        return formattedTags;
    }

    /**
     * Returns the appropriate tag value to send to datadog Necessary because only ceratin domains
     * are eligible for detailed tags
     *
     * @param tagName  - tag identifier
     * @param tagValue - tag value
     * @return String representing tag to send
     */
    private String getTagValueToSend(String tagName, String tagValue) {
        // if a domain does not have the detailed tagging feature flag enabled, instead of
        // sending an empty tag value, send "_other"
        // this differentiates between intentionally and unintentionally empty tag values
        // ("_other" vs "N/A", respectively)
        if (getDetailedTagNames().contains(tagName)) {
            if (FeatureFlagChecker.isToggleEnabled(TOGGLE_DETAILED_TAGGING)) {
                return tagValue;
            } else {
                return "_other";
            }
        } else {
            return tagValue;
        }
    }

    public static void handleKeepDropAPMTraces(boolean keepAPMTrace) {
        Span span = GlobalTracer.get().activeSpan();
        String keepDropTag;
        keepDropTag = keepAPMTrace ? DDTags.MANUAL_KEEP : DDTags.MANUAL_DROP;
        span.setTag(keepDropTag, true);
    }

}
