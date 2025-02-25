package org.commcare.formplayer.application;

import org.commcare.formplayer.objects.SerializableFormSession;
import org.commcare.formplayer.services.FormDefinitionService;
import org.commcare.formplayer.services.FormplayerRemoteInstanceFetcher;
import org.commcare.formplayer.services.FormplayerStorageFactory;
import org.commcare.formplayer.services.MenuSessionRunnerService;
import org.commcare.formplayer.services.RestoreFactory;
import org.commcare.formplayer.services.VirtualDataInstanceService;
import org.commcare.formplayer.session.FormSession;
import org.commcare.session.CommCareSession;
import org.javarosa.core.model.actions.FormSendCalloutHandler;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Component
public class FormSessionFactory {

    @Autowired
    protected MenuSessionRunnerService runnerService;

    @Autowired
    protected RestoreFactory restoreFactory;

    @Autowired
    private VirtualDataInstanceService virtualDataInstanceService;

    @Autowired
    protected FormSendCalloutHandler formSendCalloutHandler;

    @Autowired
    protected FormplayerStorageFactory storageFactory;

    @Autowired
    private FormDefinitionService formDefinitionService;

    @Autowired
    private CommCareSessionFactory commCareSessionFactory;

    public FormSession getFormSession(SerializableFormSession serializableFormSession, String windowWidth, boolean keepAPMTraces) throws Exception {
        CommCareSession commCareSession = commCareSessionFactory.getCommCareSession(serializableFormSession.getMenuSessionId());
        return getFormSession(serializableFormSession, commCareSession, windowWidth, keepAPMTraces);
    }

    @NotNull
    public FormSession getFormSession(SerializableFormSession serializableFormSession,
            @Nullable CommCareSession commCareSession, @Nullable String windowWidth, @Nullable boolean keepAPMTraces) throws Exception {
        FormplayerRemoteInstanceFetcher formplayerRemoteInstanceFetcher = new FormplayerRemoteInstanceFetcher(
                runnerService.getCaseSearchHelper(),
                virtualDataInstanceService);
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
}
