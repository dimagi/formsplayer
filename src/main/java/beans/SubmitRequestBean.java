package beans;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.Map;

/**
 * Created by willpride on 1/20/16.
 *
 * TODO: Validate answers
 * TODO: Error handling
 * TODO: Process in SQLite DB
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubmitRequestBean extends SessionBean{
    private Map<String, String> formContext;
    private boolean prevalidated;
    private Map<String, Object> answers;

    public SubmitRequestBean(){

    }

    @JsonGetter(value = "form_context")
    public Map<String, String> getFormContext() {
        return formContext;
    }
    @JsonSetter(value = "form_context")
    public void setFormContext(Map<String, String> formContext) {
        this.formContext = formContext;
    }

    public boolean isPrevalidated() {
        return prevalidated;
    }

    public void setPrevalidated(boolean prevalidated) {
        this.prevalidated = prevalidated;
    }

    public Map<String, Object> getAnswers() {
        return answers;
    }

    public void setAnswers(Map<String, Object> answers) {
        this.answers = answers;
    }

    @Override
    public String toString(){
        return "Submit Request Bean [formContent: " + formContext + ", sessionId: " + sessionId +
                ", prevalidated=" + prevalidated + ", answers=" + answers + "]";
    }
}
