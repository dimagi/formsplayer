package org.commcare.formplayer.services;

import org.apache.commons.io.IOUtils;
import org.commcare.formplayer.beans.NewFormResponse;
import org.commcare.formplayer.beans.NewSessionRequestBean;
import org.commcare.formplayer.objects.SerializableFormDefinition;
import org.commcare.formplayer.objects.SerializableFormSession;
import org.commcare.formplayer.sandbox.UserSqlSandbox;
import org.commcare.formplayer.session.FormSession;
import org.commcare.formplayer.util.Constants;
import org.commcare.formplayer.web.client.WebClient;
import org.commcare.session.CommCareSession;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.actions.FormSendCalloutHandler;
import org.javarosa.xform.util.XFormUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * Class containing logic for accepting a NewSessionRequest and services,
 * restoring the user, opening the new form, and returning the question list response.
 */
@Component
public class NewFormResponseFactory {

    @Autowired
    private WebClient webClient;

    @Autowired
    private CaseSearchHelper caseSearchHelper;

    @Autowired
    private RestoreFactory restoreFactory;

    @Autowired
    private FormSessionService formSessionService;

    @Autowired
    private FormDefinitionService formDefinitionService;

    @Autowired
    private FormSendCalloutHandler formSendCalloutHandler;

    @Autowired
    private CategoryTimingHelper categoryTimingHelper;

    @Autowired
    private FormplayerStorageFactory storageFactory;

    @Autowired
    private VirtualDataInstanceService virtualDataInstanceService;

    public NewFormResponse getResponse(NewSessionRequestBean bean, String postUrl) throws Exception {

        String formXml = null;

        if (bean.getFormUrl() != null) {
            formXml = getFormXml(bean.getFormUrl());
        } else if (bean.getFormContent() != null) {
            formXml = bean.getFormContent();
        } else {
            throw new RuntimeException("No FormURL or FormContent");
        }
        // Don't purge when restoring as a case
        boolean shouldPurge = bean.getRestoreAsCaseId() == null;
        UserSqlSandbox sandbox = restoreFactory.performTimedSync(shouldPurge, false);

        storageFactory.configure(bean.getUsername(),
                bean.getDomain(),
                bean.getSessionData().getAppId(),
                bean.getRestoreAs(),
                bean.getRestoreAsCaseId());
        storageFactory.registerFormDefStorage();

        FormDef formDef = parseFormDef(formXml);
        formDefinitionService.writeToLocalStorage(formDef);
        SerializableFormDefinition serializableFormDefinition = formDefinitionService
                .getOrCreateFormDefinition(
                        bean.getSessionData().getAppId(),
                        formDef.getMainInstance().schema,
                        bean.getSessionData().getAppVersion(),
                        formDef
                );
        FormplayerRemoteInstanceFetcher formplayerRemoteInstanceFetcher = new FormplayerRemoteInstanceFetcher(
                caseSearchHelper,
                virtualDataInstanceService);
        HashMap<String, Object> metaSessionContext = new HashMap<String, Object>();
        metaSessionContext.put("windowWidth", bean.getWindowWidth());
        metaSessionContext.put("keepAPMTraces", bean.getKeepAPMTraces());
        FormSession formSession = new FormSession(
                sandbox,
                serializableFormDefinition,
                formDef,
                bean.getUsername(),
                bean.getDomain(),
                bean.getSessionData().getData(),
                postUrl,
                bean.getLang(),
                null,
                bean.getInstanceContent(),
                bean.getOneQuestionPerScreen(),
                bean.getRestoreAs(),
                bean.getSessionData().getAppId(),
                bean.getSessionData().getFunctionContext(),
                formSendCalloutHandler,
                storageFactory,
                Constants.NAV_MODE_PROMPT.equals(bean.getNavMode()),
                bean.getRestoreAsCaseId(),
                null,
                formplayerRemoteInstanceFetcher,
                metaSessionContext,
                null
        );

        NewFormResponse response = getResponse(formSession);
        if (bean.getNavMode() != null && bean.getNavMode().equals(Constants.NAV_MODE_PROMPT)) {
            response.setEvent(response.getTree()[0]);
            response.setTree(null);
        }
        return response;
    }

    public NewFormResponse getResponse(FormSession formEntrySession) throws Exception {
        // Calling getFormTree has side effects and must be done before the instanceXML is serialized
        String formTreeJson = formEntrySession.getFormTree().toString();

        SerializableFormSession serializedSession = formEntrySession.serialize();
        formSessionService.saveSession(serializedSession);
        // cannot cache until session is saved
        formDefinitionService.cacheFormDef(formEntrySession);
        NewFormResponse response = new NewFormResponse(
                formTreeJson, formEntrySession.getLanguages(), serializedSession.getTitle(),
                serializedSession.getId(), serializedSession.getVersion(),
                serializedSession.getInstanceXml()
        );

        response.populateTranslations();

        return response;
    }

    public NewFormResponse getResponse(SerializableFormSession session, CommCareSession commCareSession, String windowWidth, boolean keepAPMTraces) throws Exception {
        FormSession formSession = getFormSession(session, commCareSession, windowWidth, keepAPMTraces);
        String formTreeJson = formSession.getFormTree().toString();
        return new NewFormResponse(
                formTreeJson, formSession.getLanguages(), session.getTitle(),
                session.getId(), session.getVersion(),
                session.getInstanceXml()
        );
    }

    public FormSession getFormSession(SerializableFormSession serializableFormSession,
            CommCareSession commCareSession, String windowWidth, boolean keepAPMTraces) throws Exception {
        FormplayerRemoteInstanceFetcher formplayerRemoteInstanceFetcher =
                new FormplayerRemoteInstanceFetcher(caseSearchHelper, virtualDataInstanceService);
        HashMap<String, Object> metaSessionContext = new HashMap<String, Object>();
        metaSessionContext.put("windowWidth", windowWidth);
        metaSessionContext.put("keepAPMTraces", keepAPMTraces);
        return new FormSession(serializableFormSession,
                restoreFactory,
                formSendCalloutHandler,
                storageFactory,
                commCareSession,
                formplayerRemoteInstanceFetcher,
                formDefinitionService,
                metaSessionContext
        );
    }

    private String getFormXml(String formUrl) {
        return webClient.get(formUrl);
    }

    private static FormDef parseFormDef(String formXml) throws IOException {
        FormDef formDef = XFormUtils.getFormRaw(new InputStreamReader(IOUtils.toInputStream(formXml, "UTF-8")));
        return formDef;
    }
}
