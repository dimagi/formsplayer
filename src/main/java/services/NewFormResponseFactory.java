package services;

import auth.HqAuth;
import beans.NewFormResponse;
import beans.NewSessionRequestBean;
import hq.CaseAPIs;
import objects.SerializableFormSession;
import org.commcare.api.persistence.UserSqlSandbox;
import org.javarosa.core.model.FormDef;
import org.javarosa.xform.parse.XFormParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import repo.FormSessionRepo;
import session.FormSession;

import java.io.IOException;
import java.io.StringReader;

/**
 * Class containing logic for accepting a NewSessionRequest and services,
 * restoring the user, opening the new form, and returning the question list response.
 */
@Component
public class NewFormResponseFactory {

    @Autowired
    private XFormService xFormService;

    @Autowired
    private RestoreFactory restoreFactory;

    @Autowired
    private FormSessionRepo formSessionRepo;

    public NewFormResponse getResponse(NewSessionRequestBean bean, String postUrl, HqAuth auth) throws Exception {

        String formXml = getFormXml(bean.getFormUrl(), auth);
        UserSqlSandbox sandbox = CaseAPIs.restoreIfNotExists(restoreFactory);

        FormSession formSession = new FormSession(sandbox, parseFormDef(formXml), bean.getUsername(),
                bean.getDomain(), bean.getSessionData().getData(), postUrl, bean.getLang(), null,
                bean.getInstanceContent(), bean.getOneQuestionPerScreen(), bean.getRestoreAs(), bean.getSessionData().getAppId());

        formSessionRepo.save(formSession.serialize());
        return new NewFormResponse(formSession);
    }

    public NewFormResponse getResponse(SerializableFormSession session) throws Exception {
        FormSession formSession = getFormSession(session);
        return new NewFormResponse(formSession);
    }

    public FormSession getFormSession(SerializableFormSession serializableFormSession) throws Exception {
        if (serializableFormSession.getRestoreXml() == null) {
            serializableFormSession.setRestoreXml(restoreFactory.getRestoreXml());
        }
        return new FormSession(serializableFormSession);
    }

    private String getFormXml(String formUrl, HqAuth auth) {
        return xFormService.getFormXml(formUrl, auth);
    }

    private static FormDef parseFormDef(String formXml) throws IOException {
        XFormParser mParser = new XFormParser(new StringReader(formXml));
        return mParser.parse();
    }
}
