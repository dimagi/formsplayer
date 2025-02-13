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
import org.javarosa.core.services.locale.Localization;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

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

    public FormSession getFormSession(SerializableFormSession serializableFormSession, String windowWidth) throws Exception {
        CommCareSession commCareSession = commCareSessionFactory.getCommCareSession(serializableFormSession.getMenuSessionId());
        return getFormSession(serializableFormSession, commCareSession, windowWidth);
    }

    @NotNull
    public FormSession getFormSession(SerializableFormSession serializableFormSession,
            @Nullable CommCareSession commCareSession, @Nullable String windowWidth) throws Exception {
        FormplayerRemoteInstanceFetcher formplayerRemoteInstanceFetcher = new FormplayerRemoteInstanceFetcher(
                runnerService.getCaseSearchHelper(),
                virtualDataInstanceService);
        return new FormSession(serializableFormSession,
                restoreFactory,
                formSendCalloutHandler,
                storageFactory,
                commCareSession,
                formplayerRemoteInstanceFetcher,
                formDefinitionService,
                windowWidth
        );
    }

    public FormSession getFormSessionForTest(SerializableFormSession serializableFormSession, String windowWidth, String locale) throws Exception {
        CommCareSession commCareSession = commCareSessionFactory.getCommCareSession(serializableFormSession.getMenuSessionId());
        if (locale != null) {
            Localization.getGlobalLocalizerAdvanced().addAvailableLocale(locale);
            Localization.setLocale(locale);
        }
        return getFormSession(serializableFormSession, commCareSession, windowWidth);
    }
}
