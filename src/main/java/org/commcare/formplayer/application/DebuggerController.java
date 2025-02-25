package org.commcare.formplayer.application;

import org.commcare.formplayer.annotations.AppInstall;
import org.commcare.formplayer.annotations.ConfigureStorageFromSession;
import org.commcare.formplayer.annotations.UserLock;
import org.commcare.formplayer.annotations.UserRestore;
import org.commcare.formplayer.beans.EvaluateXPathMenuRequestBean;
import org.commcare.formplayer.beans.EvaluateXPathRequestBean;
import org.commcare.formplayer.beans.EvaluateXPathResponseBean;
import org.commcare.formplayer.beans.SessionNavigationBean;
import org.commcare.formplayer.beans.SessionRequestBean;
import org.commcare.formplayer.beans.debugger.DebuggerFormattedQuestionsResponseBean;
import org.commcare.formplayer.beans.debugger.MenuDebuggerContentResponseBean;
import org.commcare.formplayer.beans.debugger.XPathQueryItem;
import org.commcare.formplayer.beans.menus.BaseResponseBean;
import org.commcare.formplayer.objects.SerializableFormSession;
import org.commcare.formplayer.objects.SerializableMenuSession;
import org.commcare.formplayer.services.FormattedQuestionsService;
import org.commcare.formplayer.services.MenuSessionFactory;
import org.commcare.formplayer.session.FormSession;
import org.commcare.formplayer.session.MenuSession;
import org.commcare.formplayer.util.Constants;
import org.commcare.formplayer.util.NotificationLogger;
import org.javarosa.xpath.expr.FunctionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import jakarta.annotation.Resource;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Controller class for all routes pertaining to the CloudCare Debugger
 */
@RestController
public class DebuggerController extends AbstractBaseController {

    private int MAX_RECENT = 5;

    @Autowired
    private FormattedQuestionsService formattedQuestionsService;

    @Autowired
    private FormSessionFactory formSessionFactory;

    @Autowired
    private NotificationLogger notificationLogger;

    @Autowired
    private MenuSessionFactory menuSessionFactory;

    @Resource(name="redisTemplate")
    private ListOperations<String, XPathQueryItem> listOperations;

    @RequestMapping(value = Constants.URL_DEBUGGER_FORMATTED_QUESTIONS, method = RequestMethod.POST)
    @UserRestore
    @ConfigureStorageFromSession
    public DebuggerFormattedQuestionsResponseBean getFormattedQuesitons(
            @RequestBody SessionRequestBean debuggerRequest,
            @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken) throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(debuggerRequest.getSessionId());
        SerializableMenuSession serializableMenuSession = menuSessionService.getSessionById(serializableFormSession.getMenuSessionId());
        FormSession formSession = formSessionFactory.getFormSession(serializableFormSession,
                debuggerRequest.getWindowWidth(), debuggerRequest.getKeepAPMTraces());
        String instanceXml = formSession.getInstanceXml(false);
        FormattedQuestionsService.QuestionResponse response = formattedQuestionsService.getFormattedQuestions(
                debuggerRequest.getDomain(),
                serializableMenuSession.getAppId(),
                formSession.getXmlns(),
                instanceXml
        );
        return new DebuggerFormattedQuestionsResponseBean(
                serializableMenuSession.getAppId(),
                formSession.getXmlns(),
                instanceXml,
                response.getFormattedQuestions(),
                response.getQuestionList(),
                FunctionUtils.xPathFuncList(),
                formSession.getFormEntryModel().getForm().getEvaluationContext().getInstanceIds(),
                fetchRecentFormXPathQueries(debuggerRequest.getDomain(), debuggerRequest.getUsername())
        );
    }

    @RequestMapping(value = Constants.URL_DEBUGGER_MENU_CONTENT, method = RequestMethod.POST)
    @UserRestore
    @AppInstall
    public MenuDebuggerContentResponseBean menuDebuggerContent(
            @RequestBody SessionNavigationBean debuggerMenuRequest,
            @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken,
            HttpServletRequest request) throws Exception {

        MenuSession menuSession = menuSessionFactory.getMenuSessionFromBean(debuggerMenuRequest);
        BaseResponseBean responseBean = runnerService.advanceSessionWithSelections(
                menuSession, debuggerMenuRequest.getSelections(), debuggerMenuRequest.getQueryData());
        notificationLogger.logNotification(responseBean.getNotification(), request);

        return new MenuDebuggerContentResponseBean(
                menuSession.getAppId(),
                FunctionUtils.xPathFuncList(),
                menuSession.getSessionWrapper().getEvaluationContext().getInstanceIds(),
                fetchRecentMenuXPathQueries(debuggerMenuRequest.getDomain(), debuggerMenuRequest.getUsername())
        );
    }

    @RequestMapping(value = Constants.URL_EVALUATE_MENU_XPATH, method = RequestMethod.POST)
    @ResponseBody
    @UserLock
    @UserRestore
    @AppInstall
    public EvaluateXPathResponseBean menuEvaluateXpath(@RequestBody EvaluateXPathMenuRequestBean evaluateXPathRequestBean,
                                                       @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken,
                                                       HttpServletRequest request) throws Exception {
        MenuSession menuSession = menuSessionFactory.getMenuSessionFromBean(evaluateXPathRequestBean);
        BaseResponseBean responseBean = runnerService.advanceSessionWithSelections(
                menuSession, evaluateXPathRequestBean.getSelections(),
                evaluateXPathRequestBean.getQueryData());
        notificationLogger.logNotification(responseBean.getNotification(), request);

        EvaluateXPathResponseBean evaluateXPathResponseBean = new EvaluateXPathResponseBean(
                menuSession.getSessionWrapper().getEvaluationContext(),
                evaluateXPathRequestBean.getXpath(),
                evaluateXPathRequestBean.getDebugOutputLevel()
        );

        cacheMenuXPathQuery(
                evaluateXPathRequestBean.getDomain(),
                evaluateXPathRequestBean.getUsername(),
                evaluateXPathRequestBean.getXpath(),
                evaluateXPathResponseBean.getOutput(),
                evaluateXPathResponseBean.getStatus()
        );

        return evaluateXPathResponseBean;
    }

    @RequestMapping(value = Constants.URL_EVALUATE_XPATH, method = RequestMethod.POST)
    @ResponseBody
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public EvaluateXPathResponseBean evaluateXpath(@RequestBody EvaluateXPathRequestBean evaluateXPathRequestBean,
                                                   @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken) throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(evaluateXPathRequestBean.getSessionId());
        FormSession formEntrySession = formSessionFactory.getFormSession(serializableFormSession,
                evaluateXPathRequestBean.getWindowWidth(), evaluateXPathRequestBean.getKeepAPMTraces());
        EvaluateXPathResponseBean evaluateXPathResponseBean = new EvaluateXPathResponseBean(
                formEntrySession.getFormEntryModel().getForm().getEvaluationContext(),
                evaluateXPathRequestBean.getXpath(),
                evaluateXPathRequestBean.getDebugOutputLevel()
        );

        cacheFormXPathQuery(
                evaluateXPathRequestBean.getDomain(),
                evaluateXPathRequestBean.getUsername(),
                evaluateXPathRequestBean.getXpath(),
                evaluateXPathResponseBean.getOutput(),
                evaluateXPathResponseBean.getStatus()
        );

        return evaluateXPathResponseBean;
    }

    private List<XPathQueryItem> fetchRecentMenuXPathQueries(String domain, String username) {
        return fetchRecentXPathQueries("menu", domain, username);
    }

    private List<XPathQueryItem> fetchRecentFormXPathQueries(String domain, String username) {
        return fetchRecentXPathQueries("form", domain, username);
    }

    private void cacheMenuXPathQuery(String domain, String username, String xpath, String output, String status) {
        cacheXPathQuery("menu", domain, username, xpath, output, status);
    }

    private void cacheFormXPathQuery(String domain, String username, String xpath, String output, String status) {
        cacheXPathQuery("form", domain, username, xpath, output, status);
    }

    private void cacheXPathQuery(String prefix, String domain, String username, String xpath, String output, String status) {
        XPathQueryItem queryItem = new XPathQueryItem(xpath, output, status);

        listOperations.leftPush(
                redisXPathKey(prefix, domain, username),
                queryItem
        );
    }

    private List<XPathQueryItem> fetchRecentXPathQueries(String prefix, String domain, String username) {
        listOperations.trim(redisXPathKey(prefix, domain, username), 0, MAX_RECENT);
        return listOperations.range(redisXPathKey(prefix, domain, username), 0, MAX_RECENT);
    }

    private String redisXPathKey(String prefix, String domain, String username) {
        return "debugger:xpath:v2:" + prefix + ":" + domain + ":" + username;
    }
}
