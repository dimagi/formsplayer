package org.commcare.formplayer.application;

import org.commcare.formplayer.annotations.UserLock;
import org.commcare.formplayer.annotations.UserRestore;
import org.commcare.formplayer.beans.FormsSessionsRequestBean;
import org.commcare.formplayer.beans.GetSessionsResponse;
import org.commcare.formplayer.beans.NewFormResponse;
import org.commcare.formplayer.beans.NotificationMessage;
import org.commcare.formplayer.beans.SessionRequestBean;
import org.commcare.formplayer.objects.FormSessionListView;
import org.commcare.formplayer.objects.SerializableFormSession;
import org.commcare.formplayer.util.Constants;
import org.commcare.modern.database.TableBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Controller class (API endpoint) containing all incomplete session management commands
 */
@RestController
@EnableAutoConfiguration
public class IncompleteSessionController extends AbstractBaseController {

    @Autowired
    private CommCareSessionFactory commCareSessionFactory;

    @RequestMapping(value = Constants.URL_INCOMPLETE_SESSION, method = RequestMethod.POST)
    @UserLock
    @UserRestore
    public NewFormResponse openIncompleteForm(@RequestBody SessionRequestBean incompleteSessionRequestBean,
                                              @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken) throws Exception {
        SerializableFormSession session = formSessionService.getSessionById(incompleteSessionRequestBean.getSessionId());
        storageFactory.configure(session);
        return newFormResponseFactory.getResponse(session, commCareSessionFactory.getCommCareSession(session.getMenuSessionId()),
                incompleteSessionRequestBean.getWindowWidth(), incompleteSessionRequestBean.getKeepAPMTraces());
    }

    @RequestMapping(value = Constants.URL_GET_SESSIONS, method = RequestMethod.POST)
    @UserRestore
    public GetSessionsResponse getSessions(@RequestBody FormsSessionsRequestBean getSessionRequest,
                                           @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken) throws Exception {
        String scrubbedUsername = TableBuilder.scrubName(getSessionRequest.getUsername());

        List<FormSessionListView> formplayerSessions = formSessionService.getSessionsForUser(scrubbedUsername, getSessionRequest);

        ArrayList<FormSessionListView> sessions = new ArrayList<>();
        Set<String> formplayerSessionIds = new HashSet<>();

        for (FormSessionListView serializableFormSession : formplayerSessions) {
            sessions.add(serializableFormSession);
            formplayerSessionIds.add(serializableFormSession.getId());
        }

        return new GetSessionsResponse(restoreFactory.getSqlSandbox().getCaseStorage(),
                sessions,
                formSessionService.getNumberOfSessionsForUser(scrubbedUsername, getSessionRequest));
    }

    @RequestMapping(value = Constants.URL_DELETE_INCOMPLETE_SESSION, method = RequestMethod.POST)
    public NotificationMessage deleteIncompleteForm(
            @RequestBody SessionRequestBean incompleteSessionRequestBean,
            @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken) throws Exception {
        deleteSession(incompleteSessionRequestBean.getSessionId());
        return new NotificationMessage("Successfully deleted incomplete form.", false, NotificationMessage.Tag.incomplete_form);
    }

    protected void deleteSession(String id) {
        formSessionService.deleteSessionById(id);
    }

}
