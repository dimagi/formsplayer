package org.commcare.formplayer.tests;

import static com.fasterxml.jackson.core.JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION;

import static org.commcare.formplayer.junit.HasXpath.hasXpath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.commcare.core.interfaces.RemoteInstanceFetcher;
import org.commcare.data.xml.VirtualInstances;
import org.commcare.formplayer.application.CommCareSessionFactory;
import org.commcare.formplayer.application.DebuggerController;
import org.commcare.formplayer.application.FormController;
import org.commcare.formplayer.application.FormSessionFactory;
import org.commcare.formplayer.application.FormSubmissionController;
import org.commcare.formplayer.application.FormSubmissionHelper;
import org.commcare.formplayer.application.MenuController;
import org.commcare.formplayer.application.SQLiteProperties;
import org.commcare.formplayer.application.UtilController;
import org.commcare.formplayer.auth.DjangoAuth;
import org.commcare.formplayer.beans.AnswerQuestionRequestBean;
import org.commcare.formplayer.beans.AuthenticatedRequestBean;
import org.commcare.formplayer.beans.ChangeLocaleRequestBean;
import org.commcare.formplayer.beans.DeleteApplicationDbsRequestBean;
import org.commcare.formplayer.beans.EvaluateXPathMenuRequestBean;
import org.commcare.formplayer.beans.EvaluateXPathResponseBean;
import org.commcare.formplayer.beans.FormEntryNavigationResponseBean;
import org.commcare.formplayer.beans.FormEntryResponseBean;
import org.commcare.formplayer.beans.GetInstanceResponseBean;
import org.commcare.formplayer.beans.InstallRequestBean;
import org.commcare.formplayer.beans.NewFormResponse;
import org.commcare.formplayer.beans.NewSessionRequestBean;
import org.commcare.formplayer.beans.NotificationMessage;
import org.commcare.formplayer.beans.RepeatRequestBean;
import org.commcare.formplayer.beans.SessionNavigationBean;
import org.commcare.formplayer.beans.SessionRequestBean;
import org.commcare.formplayer.beans.SubmitRequestBean;
import org.commcare.formplayer.beans.SubmitResponseBean;
import org.commcare.formplayer.beans.SyncDbRequestBean;
import org.commcare.formplayer.beans.SyncDbResponseBean;
import org.commcare.formplayer.beans.debugger.XPathQueryItem;
import org.commcare.formplayer.beans.menus.CommandListResponseBean;
import org.commcare.formplayer.configuration.CacheConfiguration;
import org.commcare.formplayer.engine.FormplayerConfigEngine;
import org.commcare.formplayer.exceptions.MenuNotFoundException;
import org.commcare.formplayer.installers.FormplayerInstallerFactory;
import org.commcare.formplayer.junit.FormSessionTest;
import org.commcare.formplayer.junit.Installer;
import org.commcare.formplayer.junit.RestoreFactoryExtension;
import org.commcare.formplayer.junit.request.EvaluateXpathRequest;
import org.commcare.formplayer.junit.request.NewFormRequest;
import org.commcare.formplayer.junit.request.SubmitFormRequest;
import org.commcare.formplayer.junit.request.SyncDbRequest;
import org.commcare.formplayer.objects.QueryData;
import org.commcare.formplayer.objects.SerializableDataInstance;
import org.commcare.formplayer.objects.SerializableFormSession;
import org.commcare.formplayer.objects.SerializableMenuSession;
import org.commcare.formplayer.sandbox.SqlSandboxUtils;
import org.commcare.formplayer.sandbox.UserSqlSandbox;
import org.commcare.formplayer.services.CategoryTimingHelper;
import org.commcare.formplayer.services.FormDefinitionService;
import org.commcare.formplayer.services.FormSessionService;
import org.commcare.formplayer.services.FormplayerRemoteInstanceFetcher;
import org.commcare.formplayer.services.FormplayerStorageFactory;
import org.commcare.formplayer.services.InstallService;
import org.commcare.formplayer.services.MenuSessionFactory;
import org.commcare.formplayer.services.MenuSessionRunnerService;
import org.commcare.formplayer.services.MenuSessionService;
import org.commcare.formplayer.services.NewFormResponseFactory;
import org.commcare.formplayer.services.ResponseMetaDataTracker;
import org.commcare.formplayer.services.RestoreFactory;
import org.commcare.formplayer.services.SubmitService;
import org.commcare.formplayer.services.VirtualDataInstanceService;
import org.commcare.formplayer.session.FormSession;
import org.commcare.formplayer.sqlitedb.UserDB;
import org.commcare.formplayer.util.Constants;
import org.commcare.formplayer.util.FormplayerDatadog;
import org.commcare.formplayer.util.NotificationLogger;
import org.commcare.formplayer.util.serializer.SessionSerializer;
import org.commcare.formplayer.utils.FileUtils;
import org.commcare.formplayer.utils.TestContext;
import org.commcare.formplayer.web.client.WebClient;
import org.commcare.modern.util.Pair;
import org.commcare.session.CommCareSession;
import org.javarosa.core.model.actions.FormSendCalloutHandler;
import org.javarosa.core.model.instance.ExternalDataInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.reference.ReferenceHandler;
import org.javarosa.core.services.locale.LocalizerManager;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.integration.support.locks.LockRegistry;
import org.springframework.lang.Nullable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import lombok.extern.apachecommons.CommonsLog;

/**
 * Created by willpride on 2/3/16.
 */
@CommonsLog
@ContextConfiguration(classes = {TestContext.class, CacheConfiguration.class})
@FormSessionTest
public class BaseTestClass {

    private MockMvc mockFormController;

    private MockMvc mockFormSubmissionController;

    protected MockMvc mockUtilController;

    private MockMvc mockMenuController;

    protected MockMvc mockDebuggerController;

    @Spy
    private StringRedisTemplate redisTemplate;

    @Autowired
    protected FormSessionService formSessionService;

    @Autowired
    protected FormDefinitionService formDefinitionService;

    @Autowired
    protected CacheManager cacheManager;

    @Autowired
    private MenuSessionService menuSessionService;

    @Autowired
    private ResponseMetaDataTracker responseMetaDataTracker;

    @Autowired
    protected WebClient webClientMock;

    @Autowired
    RestoreFactory restoreFactoryMock;

    @Autowired
    FormplayerStorageFactory storageFactoryMock;

    @Autowired
    SubmitService submitServiceMock;

    @Autowired
    CategoryTimingHelper categoryTimingHelper;

    @Autowired
    private InstallService installService;

    @Autowired
    private NewFormResponseFactory newFormResponseFactoryMock;

    @Autowired
    protected FormplayerDatadog datadogMock;

    @Autowired
    protected LockRegistry userLockRegistry;

    @Autowired
    protected FormplayerInstallerFactory formplayerInstallerFactory;

    @Autowired
    protected MenuSessionFactory menuSessionFactory;

    @Autowired
    protected MenuSessionRunnerService menuSessionRunnerService;

    @Autowired
    protected FormSubmissionHelper formSubmissionHelper;

    @Autowired
    protected FormSessionFactory formSessionFactory;

    @Autowired
    protected CommCareSessionFactory ccSessionFactory;

    @Autowired
    private FormSendCalloutHandler formSendCalloutHandlerMock;

    @Autowired
    private NotificationLogger notificationLogger;

    @Autowired
    protected VirtualDataInstanceService virtualDataInstanceService;

    @InjectMocks
    protected FormController formController;

    @InjectMocks
    protected FormSubmissionController formSubmissionController;

    @InjectMocks
    protected UtilController utilController;

    @InjectMocks
    protected MenuController menuController;

    @InjectMocks
    protected DebuggerController debuggerController;

    @Mock
    private ListOperations<String, XPathQueryItem> listOperations;

    @Mock
    private ValueOperations<String, Long> valueOperations;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private SetOperations<String, String> redisSessionCache;

    @Mock
    protected RemoteInstanceFetcher remoteInstanceFetcherMock;

    protected ObjectMapper mapper;

    final Map<String, SerializableMenuSession> menuSessionMap = new HashMap<>();
    final Map<String, SerializableDataInstance> serializableDataInstanceMap = new HashMap();

    RestoreFactoryExtension restoreFactoryExtension = new RestoreFactoryExtension.builder().build();

    @BeforeEach
    public void setUp() throws Exception {
        Mockito.reset(webClientMock);
        Mockito.reset(restoreFactoryMock);
        Mockito.reset(submitServiceMock);
        Mockito.reset(categoryTimingHelper);
        Mockito.reset(installService);
        Mockito.reset(userLockRegistry);
        Mockito.reset(newFormResponseFactoryMock);
        Mockito.reset(storageFactoryMock);
        Mockito.reset(formplayerInstallerFactory);
        Mockito.reset(datadogMock);
        Mockito.reset(menuSessionFactory);
        Mockito.reset(menuSessionRunnerService);
        Mockito.reset(remoteInstanceFetcherMock);
        Mockito.reset(notificationLogger);
        MockitoAnnotations.openMocks(this);
        mockFormController = MockMvcBuilders.standaloneSetup(formController).build();
        mockFormSubmissionController = MockMvcBuilders.standaloneSetup(
                formSubmissionController).build();
        mockUtilController = MockMvcBuilders.standaloneSetup(utilController).build();
        mockMenuController = MockMvcBuilders.standaloneSetup(menuController).build();
        mockDebuggerController = MockMvcBuilders.standaloneSetup(debuggerController).build();
        setupRestoreFactoryMock();
        setupSubmitServiceMock();
        mapper = new ObjectMapper();
        mapper.enable(INCLUDE_SOURCE_IN_LOCATION);
        storageFactoryMock.getSQLiteDB().closeConnection();
        restoreFactoryMock.getSQLiteDB().closeConnection();
        mockMenuSessionService();
        mockVirtualDataInstanceService();
        // this shouldn't be needed here (see TestContext) but tests fail without it
        new SQLiteProperties().setDataDir("testdbs/");
    }

    private void setupRestoreFactoryMock() {
        restoreFactoryExtension.setRestoreFactory(restoreFactoryMock);
        restoreFactoryExtension.setRestorePath(getMockRestoreFileName());
        restoreFactoryExtension.reset();
        restoreFactoryExtension.configureMock();
    }

    private void mockVirtualDataInstanceService() {
        serializableDataInstanceMap.clear();
        when(virtualDataInstanceService.write(any(ExternalDataInstance.class)))
                .thenAnswer(getVirtualInstanceMockWrite());

        when(virtualDataInstanceService.write(any(String.class), any(ExternalDataInstance.class)))
                .thenAnswer(getVirtualInstanceMockWrite());

        when(virtualDataInstanceService.read(any(String.class), any(String.class), any(String.class))).thenAnswer(
                invocation -> {
                    String key = (String)invocation.getArguments()[0];
                    String instanceId = (String)invocation.getArguments()[1];
                    String refId = (String)invocation.getArguments()[2];
                    if (serializableDataInstanceMap.containsKey(key)) {
                        SerializableDataInstance savedInstance = serializableDataInstanceMap.get(key);
                        return savedInstance.toInstance(instanceId, key, refId);
                    }
                    throw new VirtualInstances.InstanceNotFoundException(key, "test-namespace");
                });

        when(virtualDataInstanceService.contains(any(String.class))).thenAnswer(invocation -> {
            String key = (String)invocation.getArguments()[0];
            return serializableDataInstanceMap.containsKey(key);
        });
    }

    @NotNull
    private Answer<Object> getVirtualInstanceMockWrite() {
        return invocation -> {
            String key;
            ExternalDataInstance dataInstance;
            if (invocation.getArguments().length == 2) {
                key = (String)invocation.getArguments()[0];
                dataInstance = (ExternalDataInstance)invocation.getArguments()[1];
            } else {
                key = UUID.randomUUID().toString();
                dataInstance = (ExternalDataInstance)invocation.getArguments()[0];
            }

            SerializableDataInstance serializableDataInstance = new SerializableDataInstance(
                    dataInstance.getInstanceId(), dataInstance.getReference(),
                    "username", "domain", "appid", "asuser",
                    (TreeElement)dataInstance.getRoot(), dataInstance.useCaseTemplate(),
                    "test-namespace:" + key);
            serializableDataInstanceMap.put(key, serializableDataInstance);
            return key;
        };
    }

    private void mockMenuSessionService() {
        menuSessionMap.clear();
        doAnswer(new Answer<SerializableMenuSession>() {
            @Override
            public SerializableMenuSession answer(InvocationOnMock invocation) throws Throwable {
                SerializableMenuSession session =
                        (SerializableMenuSession)invocation.getArguments()[0];
                if (session.getId() == null) {
                    // this is normally taken care of by Hibernate
                    ReflectionTestUtils.setField(session, "id", UUID.randomUUID().toString());
                }
                menuSessionMap.put(session.getId(), session);
                return session;
            }
        }).when(menuSessionService).saveSession(any(SerializableMenuSession.class));

        when(menuSessionService.getSessionById(anyString())).thenAnswer(
                new Answer<SerializableMenuSession>() {
                    @Override
                    public SerializableMenuSession answer(InvocationOnMock invocation)
                            throws Throwable {
                        String key = (String)invocation.getArguments()[0];
                        if (menuSessionMap.containsKey(key)) {
                            return menuSessionMap.get(key);
                        }
                        throw new MenuNotFoundException(key);
                    }
                });
    }

    protected String getDatabaseFolderRoot() {
        return "testdbs/";
    }

    protected boolean removeDatabaseFoldersAfterTests() {
        return true;
    }

    private void setupSubmitServiceMock() {
        Mockito.doReturn(
                "<OpenRosaResponse>" +
                        "<message nature='status'>" +
                        "OK" +
                        "</message>" +
                        "</OpenRosaResponse>")
                .when(submitServiceMock).submitForm(any(), anyString());
    }

    @AfterEach
    public void tearDown() throws SQLException {
        if (customConnector != null) {
            customConnector.closeConnection();
        }

        if (sandbox != null) {
            sandbox.getConnection().close();
        }
        restoreFactoryMock.getSQLiteDB().closeConnection();
        storageFactoryMock.getSQLiteDB().closeConnection();
        if (removeDatabaseFoldersAfterTests()) {
            SqlSandboxUtils.deleteDatabaseFolder(SQLiteProperties.getDataDir());
        }
        ReferenceHandler.clearInstance();
        LocalizerManager.clearInstance();
    }

    private UserDB customConnector;

    protected UserDB getUserDbConnector(String domain, String username, String restoreAs) {
        customConnector = new UserDB(domain, username, restoreAs);
        return customConnector;
    }

    UserSqlSandbox sandbox;

    protected UserSqlSandbox getRestoreSandbox() {
        if (sandbox != null) {
            try {
                sandbox.getConnection().close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            sandbox = null;
        }
        sandbox = restoreFactoryMock.getSqlSandbox();
        return sandbox;
    }

    private String urlPrepend(String string) {
        return "/" + string;
    }

    protected String getMockRestoreFileName() {
        return "test_restore.xml";
    }

    protected void configureRestoreFactory(String domain, String username) {
        restoreFactoryMock.setDomain(domain);
        restoreFactoryMock.setUsername(username);
    }

    private <T extends SessionRequestBean> T populateFromSession(T bean, String sessionId) {
        SerializableFormSession session = formSessionService.getSessionById(sessionId);
        bean.setUsername(session.getUsername());
        bean.setDomain(session.getDomain());
        bean.setSessionId(sessionId);
        return bean;
    }

    FormEntryNavigationResponseBean nextScreen(String sessionId) throws Exception {
        return nextScreen(sessionId, false);
    }

    FormEntryNavigationResponseBean nextScreen(String sessionId, boolean promptMode)
            throws Exception {
        SessionRequestBean questionsBean = populateFromSession(new SessionRequestBean(), sessionId);
        questionsBean.setSessionId(sessionId);


        if (promptMode) {
            return generateMockQuery(ControllerType.FORM,
                    RequestType.POST,
                    Constants.URL_NEXT,
                    questionsBean,
                    FormEntryNavigationResponseBean.class);
        }

        return generateMockQuery(ControllerType.FORM,
                RequestType.POST,
                Constants.URL_NEXT_INDEX,
                questionsBean,
                FormEntryNavigationResponseBean.class);
    }

    FormEntryNavigationResponseBean previousScreen(String sessionId) throws Exception {
        SessionRequestBean questionsBean = populateFromSession(new SessionRequestBean(), sessionId);
        return generateMockQuery(ControllerType.FORM,
                RequestType.POST,
                Constants.URL_PREV_INDEX,
                questionsBean,
                FormEntryNavigationResponseBean.class);
    }

    FormEntryResponseBean changeLanguage(String locale, String sessionId) throws Exception {
        ChangeLocaleRequestBean changeLocaleBean = populateFromSession(
                new ChangeLocaleRequestBean(), sessionId);
        changeLocaleBean.setLocale(locale);
        return generateMockQuery(ControllerType.FORM,
                RequestType.POST,
                Constants.URL_CHANGE_LANGUAGE,
                changeLocaleBean,
                FormEntryResponseBean.class);
    }

    FormEntryResponseBean answerQuestionGetResult(String requestPath, String sessionId)
            throws Exception {
        String requestPayload = FileUtils.getFile(this.getClass(), requestPath);
        AnswerQuestionRequestBean answerQuestionBean = mapper.readValue(requestPayload,
                AnswerQuestionRequestBean.class);
        answerQuestionBean.setSessionId(sessionId);
        return generateMockQuery(ControllerType.FORM,
                RequestType.POST,
                Constants.URL_ANSWER_QUESTION,
                answerQuestionBean,
                FormEntryResponseBean.class);
    }

    FormEntryResponseBean answerQuestionGetResult(String index, String answer, String sessionId)
            throws Exception {
        AnswerQuestionRequestBean answerQuestionBean = new AnswerQuestionRequestBean(index, answer,
                sessionId);
        populateFromSession(answerQuestionBean, sessionId);
        return generateMockQuery(ControllerType.FORM,
                RequestType.POST,
                Constants.URL_ANSWER_QUESTION,
                answerQuestionBean,
                FormEntryResponseBean.class);
    }

    GetInstanceResponseBean getInstance(String sessionId) throws Exception {
        SessionRequestBean sessionRequestBean = populateFromSession(new SessionRequestBean(),
                sessionId);
        return generateMockQuery(ControllerType.FORM,
                RequestType.GET,
                Constants.URL_GET_INSTANCE,
                sessionRequestBean,
                GetInstanceResponseBean.class);
    }

    NewFormResponse startNewForm(String requestPath, String formPath) throws Exception {
        when(webClientMock.get(anyString()))
                .thenReturn(FileUtils.getFile(this.getClass(), formPath));
        String requestPayload = FileUtils.getFile(this.getClass(), requestPath);
        NewSessionRequestBean newSessionRequestBean = mapper.readValue(requestPayload,
                NewSessionRequestBean.class);
        restoreFactoryMock.configure(newSessionRequestBean, new DjangoAuth("derp"));
        return new NewFormRequest(mockFormController, webClientMock, formPath)
                .requestWithBean(newSessionRequestBean)
                .bean();
    }

    SubmitResponseBean submitForm(String sessionId) throws Exception {
        return submitForm(new HashMap<>(), sessionId);
    }

    SubmitResponseBean submitForm(String requestPath, String sessionId) throws Exception {
        SubmitRequestBean submitRequestBean = mapper.readValue
                (FileUtils.getFile(this.getClass(), requestPath), SubmitRequestBean.class);
        submitRequestBean.setSessionId(sessionId);
        restoreFactoryMock.configure(submitRequestBean, new DjangoAuth("123"));
        return new SubmitFormRequest(mockFormSubmissionController)
                .requestWithBean(submitRequestBean).bean();
    }


    SubmitResponseBean submitForm(Map<String, Object> answers, String sessionId) throws Exception {
        return submitForm(answers, sessionId, true);
    }

    SubmitResponseBean submitForm(Map<String, Object> answers, String sessionId,
            boolean prevalidated) throws Exception {
        SubmitRequestBean submitRequestBean = populateFromSession(new SubmitRequestBean(),
                sessionId);
        submitRequestBean.setAnswers(answers);
        submitRequestBean.setPrevalidated(prevalidated);
        restoreFactoryMock.configure(submitRequestBean, new DjangoAuth("123"));
        return new SubmitFormRequest(mockFormSubmissionController)
                .requestWithBean(submitRequestBean).bean();
    }

    protected SyncDbResponseBean syncDb() {
        SyncDbRequestBean syncDbRequestBean = new SyncDbRequestBean();
        syncDbRequestBean.setDomain(restoreFactoryMock.getDomain());
        syncDbRequestBean.setUsername(restoreFactoryMock.getUsername());
        syncDbRequestBean.setRestoreAs(restoreFactoryMock.getAsUsername());
        restoreFactoryMock.configure(syncDbRequestBean, new DjangoAuth("derp"));
        return new SyncDbRequest(mockUtilController, restoreFactoryMock).requestWithBean(syncDbRequestBean).bean();
    }

    NotificationMessage deleteApplicationDbs() throws Exception {
        String payload = FileUtils.getFile(this.getClass(), "requests/delete_db/delete_db.json");
        DeleteApplicationDbsRequestBean request = mapper.readValue(
                payload,
                DeleteApplicationDbsRequestBean.class
        );

        return generateMockQuery(
                ControllerType.UTIL,
                RequestType.POST,
                Constants.URL_DELETE_APPLICATION_DBS,
                request,
                NotificationMessage.class
        );
    }

    FormEntryResponseBean newRepeatRequest(String sessionId, String repeatIndex) throws Exception {
        RepeatRequestBean newRepeatRequestBean = new RepeatRequestBean();
        newRepeatRequestBean.setRepeatIndex(repeatIndex);
        newRepeatRequestBean.setSessionId(sessionId);
        populateFromSession(newRepeatRequestBean, sessionId);

        return generateMockQuery(
                ControllerType.FORM,
                RequestType.POST,
                Constants.URL_NEW_REPEAT,
                newRepeatRequestBean,
                FormEntryResponseBean.class
        );
    }

    FormEntryResponseBean deleteRepeatRequest(String sessionId, String repeatIndex)
            throws Exception {
        RepeatRequestBean deleteRepeatRequest = new RepeatRequestBean();
        deleteRepeatRequest.setRepeatIndex(repeatIndex);
        deleteRepeatRequest.setSessionId(sessionId);
        populateFromSession(deleteRepeatRequest, sessionId);
        return generateMockQuery(
                ControllerType.FORM,
                RequestType.POST,
                Constants.URL_DELETE_REPEAT,
                deleteRepeatRequest,
                FormEntryResponseBean.class
        );
    }

    EvaluateXPathResponseBean evaluateXPath(String sessionId, String xPath) throws Exception {
        return new EvaluateXpathRequest(mockDebuggerController, sessionId, xPath, formSessionService, null, null)
                .request()
                .bean();
    }

    EvaluateXPathResponseBean evaluateMenuXpath(String requestPath) throws Exception {
        Pair<String, EvaluateXPathMenuRequestBean> refAndBean = Installer.getInstallReferenceAndBean(
                requestPath, EvaluateXPathMenuRequestBean.class);
        return generateMockQueryWithInstallReference(refAndBean.first,
                ControllerType.DEBUGGER,
                RequestType.POST,
                Constants.URL_EVALUATE_MENU_XPATH,
                refAndBean.second,
                EvaluateXPathResponseBean.class
        );
    }

    EvaluateXPathResponseBean evaluateMenuXpath(String menuSessionId, String xpath)
            throws Exception {
        SerializableMenuSession menuSession = menuSessionService.getSessionById(menuSessionId);

        EvaluateXPathMenuRequestBean evaluateXPathRequestBean = new EvaluateXPathMenuRequestBean();
        evaluateXPathRequestBean.setUsername(menuSession.getUsername());
        evaluateXPathRequestBean.setDomain(menuSession.getDomain());
        evaluateXPathRequestBean.setRestoreAs(menuSession.getAsUser());
        evaluateXPathRequestBean.setXpath(xpath);
        return generateMockQuery(
                ControllerType.DEBUGGER,
                RequestType.POST,
                Constants.URL_EVALUATE_MENU_XPATH,
                evaluateXPathRequestBean,
                EvaluateXPathResponseBean.class
        );
    }

    /**
     * Evaluate a XPath expression and check the result.
     */
    protected void checkXpath(String sessionId, String xpath, String expectedValue)
            throws Exception {
        new EvaluateXpathRequest(mockDebuggerController, sessionId, xpath, formSessionService, null, null)
                .request()
                .andExpectAll(
                        jsonPath("status", equalTo(Constants.ANSWER_RESPONSE_STATUS_POSITIVE)),
                        jsonPath("output", hasXpath("/result", equalTo(expectedValue)))
                );
    }

    <T> T getDetails(String requestPath, Class<T> clazz) throws Exception {
        Pair<String, SessionNavigationBean> refAndBean = Installer.getInstallReferenceAndBean(requestPath,
                SessionNavigationBean.class);
        return generateMockQueryWithInstallReference(refAndBean.first,
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_GET_DETAILS,
                refAndBean.second,
                clazz);
    }

    <T> T getDetails(String[] selections, String testName, Class<T> clazz) throws Exception {
        return getDetails(selections, testName, null, null, clazz, false, false, false);
    }

    <T> T getDetails(String[] selections, String testName, QueryData queryData, Class<T> clazz) throws Exception {
        return getDetails(selections, testName, null, queryData, clazz, false, false, false);
    }

    <T> T getDetails(String[] selections, String testName, String locale, QueryData queryData, Class<T> clazz,
            boolean inline, boolean isShortDetail, boolean isRefreshCaseSearch) throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        sessionNavigationBean.setSelections(selections);
        sessionNavigationBean.setIsPersistent(inline);
        sessionNavigationBean.setQueryData(queryData);
        sessionNavigationBean.setIsShortDetail(isShortDetail);
        sessionNavigationBean.setIsRefreshCaseSearch(isRefreshCaseSearch);
        if (locale != null && !"".equals(locale.trim())) {
            sessionNavigationBean.setLocale(locale);
        }
        return generateMockQueryWithInstallReference(Installer.getInstallReference(testName),
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_GET_DETAILS,
                sessionNavigationBean,
                clazz);
    }

    <T> T getDetailsInline(String[] selections, String testName, Class<T> clazz) throws Exception {
        return getDetails(selections, testName, null, null, clazz, true, false, false);
    }

    <T> T sessionNavigate(String requestPath, Class<T> clazz) throws Exception {
        Pair<String, SessionNavigationBean> refAndBean = Installer.getInstallReferenceAndBean(requestPath,
                SessionNavigationBean.class);
        String installReference = Installer.getInstallReference(refAndBean.first);
        return generateMockQueryWithInstallReference(installReference,
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_MENU_NAVIGATION,
                refAndBean.second,
                clazz);
    }

    <T> T sessionNavigate(String[] selections, String testName, Class<T> clazz) throws Exception {
        return sessionNavigate(selections, testName, null, clazz);
    }

    <T> T sessionNavigate(String[] selections, String testName, String locale, Class<T> clazz)
            throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        sessionNavigationBean.setSelections(selections);
        if (locale != null && !"".equals(locale.trim())) {
            sessionNavigationBean.setLocale(locale);
        }
        return generateMockQueryWithInstallReference(Installer.getInstallReference(testName),
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_MENU_NAVIGATION,
                sessionNavigationBean,
                clazz);
    }

    <T> T sessionNavigate(String[] selections, String testName, int sortIndex, Class<T> clazz)
            throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        sessionNavigationBean.setSelections(selections);
        sessionNavigationBean.setSortIndex(sortIndex);
        return generateMockQueryWithInstallReference(Installer.getInstallReference(testName),
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_MENU_NAVIGATION,
                sessionNavigationBean,
                clazz);
    }

    <T> T sessionNavigate(String[] selections, String testName, String locale, Class<T> clazz,
            String restoreAs) throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        sessionNavigationBean.setSelections(selections);
        sessionNavigationBean.setRestoreAs(restoreAs);
        if (locale != null && !"".equals(locale.trim())) {
            sessionNavigationBean.setLocale(locale);
        }
        return generateMockQueryWithInstallReference(Installer.getInstallReference(testName),
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_MENU_NAVIGATION,
                sessionNavigationBean,
                clazz);
    }

    SyncDbResponseBean intervalSyncDB(String testName, String restoreAs) throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        sessionNavigationBean.setRestoreAs(restoreAs);
        return generateMockQuery(
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_INTERVAL_SYNC_DB,
                sessionNavigationBean,
                SyncDbResponseBean.class
        );
    }

    <T> T sessionNavigateWithSelectedValues(String[] selections, String testName, String[] selectedValues,
            Class<T> clazz) throws Exception {
       return sessionNavigateWithSelectedValues(selections, testName, selectedValues, null, clazz);
    }

    <T> T sessionNavigateWithSelectedValues(String[] selections, String testName, String[] selectedValues,  String windowWidth,
            Class<T> clazz)
            throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        sessionNavigationBean.setSelections(selections);
        sessionNavigationBean.setSelectedValues(selectedValues);
        sessionNavigationBean.setWindowWidth(windowWidth);
        return generateMockQueryWithInstallReference(Installer.getInstallReference(testName),
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_MENU_NAVIGATION,
                sessionNavigationBean,
                clazz);
    }

    <T> T sessionNavigateWithEndpoint(String testName,
            String endpointId,
            HashMap<String, String> endpointArgs,
            Class<T> clazz) throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setEndpointId(endpointId);
        if (endpointArgs != null) {
            sessionNavigationBean.setEndpointArgs(endpointArgs);
        }
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        return generateMockQueryWithInstallReference(Installer.getInstallReference(testName),
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_GET_ENDPOINT,
                sessionNavigationBean,
                clazz);
    }

    <T> T sessionNavigateWithQuery(ArrayList<String> selections,
            String testName,
            QueryData queryData,
            Class<T> clazz) throws Exception {
        return sessionNavigateWithQuery(selections.toArray(new String[selections.size()]),
                testName, queryData, clazz);
    }

    <T> T sessionNavigateWithQuery(String[] selections,
            String testName,
            QueryData queryData,
            Class<T> clazz) throws Exception {
        return sessionNavigateWithQuery(selections, testName, queryData, null, clazz);
    }

    <T> T sessionNavigateWithQuery(String[] selections,
            String testName,
            QueryData queryData,
            String[] selectedValues,
            Class<T> clazz) throws Exception {
        return sessionNavigateWithQuery(selections, testName, queryData, selectedValues, 0, 10, clazz);
    }

    <T> T sessionNavigateWithQuery(String[] selections,
            String testName,
            QueryData queryData,
            String[] selectedValues,
            int offset,
            int casesPerPage,
            Class<T> clazz) throws Exception {
        SessionNavigationBean sessionNavigationBean = new SessionNavigationBean();
        sessionNavigationBean.setSelections(selections);
        sessionNavigationBean.setDomain(testName + "domain");
        sessionNavigationBean.setAppId(testName + "appid");
        sessionNavigationBean.setUsername(testName + "username");
        sessionNavigationBean.setQueryData(queryData);
        sessionNavigationBean.setOffset(offset);
        sessionNavigationBean.setCasesPerPage(casesPerPage);
        sessionNavigationBean.setSelectedValues(selectedValues);
        return generateMockQueryWithInstallReference(Installer.getInstallReference(testName),
                ControllerType.MENU,
                RequestType.POST,
                Constants.URL_MENU_NAVIGATION,
                sessionNavigationBean,
                clazz);
    }

    /**
     * This function performs an app install outside of the request cycle. In order to do that
     * successfully it mimics behaviour in the UserRestoreAspect and the AppInstallAspect.
     *
     * @param requestPath path to JSON file with InstallRequestBean content as well as
     *                    'installReference'
     */
    protected CommandListResponseBean doInstall(String requestPath) throws Exception {
        Installer installer = new Installer(restoreFactoryMock, storageFactoryMock, menuSessionFactory,
                menuSessionRunnerService);
        return installer.doInstall(requestPath);
    }

    CommandListResponseBean doUpdate(String requestPath) throws Exception {
        InstallRequestBean installRequestBean = mapper.readValue
                (FileUtils.getFile(this.getClass(), requestPath), InstallRequestBean.class);
        return generateMockQuery(ControllerType.MENU,
                RequestType.POST,
                Constants.URL_UPDATE,
                installRequestBean,
                CommandListResponseBean.class);
    }

    public enum RequestType {
        POST, GET, MULTIPART
    }

    public enum ControllerType {
        FORM, FORM_SUBMISSION, MENU, UTIL, DEBUGGER,
    }

    private <T> T generateMockQueryWithInstallReference(String installReference,
            ControllerType controllerType,
            RequestType requestType,
            String urlPath,
            Object bean,
            Class<T> clazz) throws Exception {
        return Installer.mockInstallReference(
                () -> generateMockQuery(controllerType, requestType, urlPath, bean, clazz),
                installReference
        );
    }

    private <T> T generateMockQuery(ControllerType controllerType,
            RequestType requestType,
            String urlPath,
            Object bean,
            Class<T> clazz) throws Exception {
        return generateMockQuery(controllerType, requestType, urlPath, bean, null, clazz);
    }

    private <T> T generateMockQuery(ControllerType controllerType,
            RequestType requestType,
            String urlPath,
            Object bean,
            MockMultipartFile file,
            Class<T> clazz) throws Exception {
        MockMvc controller = null;
        ResultActions result = null;

        if (bean instanceof AuthenticatedRequestBean) {
            restoreFactoryMock.getSQLiteDB().closeConnection();
            restoreFactoryMock.configure((AuthenticatedRequestBean)bean, new DjangoAuth("derp"));
        }

        if (bean instanceof InstallRequestBean) {
            storageFactoryMock.getSQLiteDB().closeConnection();
            storageFactoryMock.configure((InstallRequestBean)bean);
        }

        if (bean instanceof SessionRequestBean) {
            storageFactoryMock.getSQLiteDB().closeConnection();
            storageFactoryMock.configure(((SessionRequestBean)bean).getSessionId());
        }

        if (!(bean instanceof String)) {
            bean = mapper.writeValueAsString(bean);
        }
        switch (controllerType) {
            case FORM:
                controller = mockFormController;
                break;
            case FORM_SUBMISSION:
                controller = mockFormSubmissionController;
                break;
            case MENU:
                controller = mockMenuController;
                break;
            case UTIL:
                controller = mockUtilController;
                break;
            case DEBUGGER:
                controller = mockDebuggerController;
                break;
        }
        switch (requestType) {
            case POST:
                result = controller.perform(
                        post(urlPrepend(urlPath))
                                .contentType(MediaType.APPLICATION_JSON)
                                .cookie(new Cookie(Constants.POSTGRES_DJANGO_SESSION_ID, "derp"))
                                .content((String)bean));
                break;

            case GET:
                result = controller.perform(
                        get(urlPrepend(urlPath))
                                .contentType(MediaType.APPLICATION_JSON)
                                .cookie(new Cookie(Constants.POSTGRES_DJANGO_SESSION_ID, "derp"))
                                .content((String)bean));
                break;
        }
        restoreFactoryMock.getSQLiteDB().closeConnection();
        storageFactoryMock.getSQLiteDB().closeConnection();
        return mapper.readValue(
                result.andReturn().getResponse().getContentAsString(),
                clazz
        );
    }

    <T> T getNextScreenForEofNavigation(SubmitResponseBean submitResponse, Class<T> clazz)
            throws IOException {
        LinkedHashMap commandsRaw = (LinkedHashMap)submitResponse.getNextScreen();
        String jsonString = new JSONObject(commandsRaw).toString();
        return mapper.readValue(jsonString, clazz);
    }

    protected FormSession getFormSession(SerializableFormSession serializableFormSession, String windowWidth)
            throws Exception {
        FormplayerRemoteInstanceFetcher remoteInstanceFetcher = new FormplayerRemoteInstanceFetcher(
                menuSessionRunnerService.getCaseSearchHelper(),
                virtualDataInstanceService);
        return new FormSession(serializableFormSession,
                restoreFactoryMock,
                formSendCalloutHandlerMock,
                storageFactoryMock,
                getCommCareSession(serializableFormSession.getMenuSessionId()),
                remoteInstanceFetcher,
                formDefinitionService,
                windowWidth
        );
    }

    @Nullable
    protected CommCareSession getCommCareSession(String menuSessionId) throws Exception {
        if (menuSessionId == null) {
            return null;
        }

        SerializableMenuSession serializableMenuSession = menuSessionService.getSessionById(
                menuSessionId);
        FormplayerConfigEngine engine = installService.configureApplication(
                serializableMenuSession.getInstallReference(),
                serializableMenuSession.isPreview()).first;
        return SessionSerializer.deserialize(engine.getPlatform(),
                serializableMenuSession.getCommcareSession());
    }

    // Ensure that 'selected_cases' instance is populated correctly
    protected void checkForSelectedEntitiesInstance(String sessionId, String[] expectedCases) throws Exception {
        EvaluateXPathResponseBean evaluateXpathResponseBean = evaluateXPath(sessionId,
                "instance('selected_cases')/results");
        assertEquals(evaluateXpathResponseBean.getStatus(), Constants.ANSWER_RESPONSE_STATUS_POSITIVE);
        for (int i = 0; i < expectedCases.length; i++) {
            String xpathRef = "/result/results/value[" + (i + 1) + "]";
            assertThat(evaluateXpathResponseBean.getOutput(), hasXpath(xpathRef, equalTo(expectedCases[i])));
        }

    }

    // Ensure that the instance datum is set correctly to the guid
    protected void checkForSelectedEntitiesDatum(String sessionId, String guid) throws Exception {
        EvaluateXPathResponseBean evaluateXpathResponseBean = evaluateXPath(sessionId,
                "instance('commcaresession')/session/data/selected_cases");
        assertEquals(evaluateXpathResponseBean.getStatus(), Constants.ANSWER_RESPONSE_STATUS_POSITIVE);
        String result = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<result>" + guid + "</result>\n";
        assertEquals(evaluateXpathResponseBean.getOutput(), result);
    }
}
