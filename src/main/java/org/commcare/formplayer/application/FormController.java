package org.commcare.formplayer.application;

import static org.commcare.formplayer.util.Constants.PART_ANSWER;
import static org.commcare.formplayer.util.Constants.PART_FILE;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.commcare.formplayer.annotations.ConfigureStorageFromSession;
import org.commcare.formplayer.annotations.UserLock;
import org.commcare.formplayer.annotations.UserRestore;
import org.commcare.formplayer.api.json.JsonActionUtils;
import org.commcare.formplayer.api.util.ApiConstants;
import org.commcare.formplayer.beans.AnswerQuestionRequestBean;
import org.commcare.formplayer.beans.ChangeLocaleRequestBean;
import org.commcare.formplayer.beans.FormEntryNavigationResponseBean;
import org.commcare.formplayer.beans.FormEntryResponseBean;
import org.commcare.formplayer.beans.GetInstanceResponseBean;
import org.commcare.formplayer.beans.InstanceXmlBean;
import org.commcare.formplayer.beans.NewFormResponse;
import org.commcare.formplayer.beans.NewSessionRequestBean;
import org.commcare.formplayer.beans.RepeatRequestBean;
import org.commcare.formplayer.beans.SessionRequestBean;
import org.commcare.formplayer.beans.menus.ErrorBean;
import org.commcare.formplayer.objects.FormVolatilityRecord;
import org.commcare.formplayer.objects.SerializableFormSession;
import org.commcare.formplayer.services.CategoryTimingHelper;
import org.commcare.formplayer.services.FormplayerStorageFactory;
import org.commcare.formplayer.services.MediaMetaDataService;
import org.commcare.formplayer.services.MenuSessionFactory;
import org.commcare.formplayer.session.FormSession;
import org.commcare.formplayer.util.Constants;
import org.commcare.formplayer.util.FormplayerDatadog;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryModel;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import jakarta.annotation.Resource;

import io.sentry.Sentry;

/**
 * Controller class (API endpoint) containing all form entry logic. This includes
 * opening a new form, question answering, and form submission.
 */
@RestController
@EnableAutoConfiguration
public class FormController extends AbstractBaseController {

    private final Log log = LogFactory.getLog(FormController.class);
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired
    private MediaMetaDataService mediaMetaDataService;

    @Autowired
    private CategoryTimingHelper categoryTimingHelper;
    @Autowired
    private FormplayerStorageFactory storageFactory;

    @Autowired
    private FormSessionFactory formSessionFactory;

    @Autowired
    private FormplayerDatadog datadog;
    @Resource(name = "redisVolatilityDict")
    private ValueOperations<String, FormVolatilityRecord> volatilityCache;
    @Value("${commcarehq.host}")
    private String host;

    // Iterate over all answers and attempt to save them to check for validity.
    public static HashMap<String, ErrorBean> validateAnswers(FormEntryController formEntryController,
            FormEntryModel formEntryModel,
            @Nullable Map<String, Object> answers,
            boolean skipValidation) {
        HashMap<String, ErrorBean> errors = new HashMap<>();
        if (answers != null) {
            for (String key : answers.keySet()) {
                int questionType = JsonActionUtils.getQuestionType(formEntryModel, key, formEntryModel.getForm());
                if (!(questionType == FormEntryController.EVENT_QUESTION)) {
                    continue;
                }
                String answer = answers.get(key) == null ? null : answers.get(key).toString();
                JSONObject answerResult =
                        JsonActionUtils.questionAnswerToJson(formEntryController,
                                formEntryModel,
                                answer,
                                key,
                                false,
                                null,
                                skipValidation,
                                false);
                if (!answerResult.get(ApiConstants.RESPONSE_STATUS_KEY).equals(
                        Constants.ANSWER_RESPONSE_STATUS_POSITIVE)) {
                    ErrorBean error = new ErrorBean();
                    error.setStatus(answerResult.get(ApiConstants.RESPONSE_STATUS_KEY).toString());
                    error.setType(answerResult.getString(ApiConstants.ERROR_TYPE_KEY));
                    errors.put(key, error);
                }
            }
        }
        return errors;
    }

    @RequestMapping(value = Constants.URL_NEW_SESSION, method = RequestMethod.POST)
    @UserLock
    @UserRestore
    public NewFormResponse newFormResponse(@RequestBody NewSessionRequestBean newSessionBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        String postUrl = host + newSessionBean.getPostUrl();
        return newFormResponseFactory.getResponse(newSessionBean, postUrl);
    }

    @RequestMapping(value = Constants.URL_CHANGE_LANGUAGE, method = RequestMethod.POST)
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryResponseBean changeLocale(@RequestBody ChangeLocaleRequestBean changeLocaleBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(
                changeLocaleBean.getSessionId());
        FormSession formEntrySession = formSessionFactory.getFormSession(serializableFormSession,
                changeLocaleBean.getWindowWidth(), changeLocaleBean.getKeepAPMTraces());
        formEntrySession.changeLocale(changeLocaleBean.getLocale());
        FormEntryResponseBean responseBean = formEntrySession.getCurrentJson();
        updateSession(formEntrySession);
        responseBean.setTitle(serializableFormSession.getTitle());
        return responseBean;
    }

    @RequestMapping(value = Constants.URL_ANSWER_QUESTION, method = RequestMethod.POST)
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryResponseBean answerQuestion(@RequestBody AnswerQuestionRequestBean answerQuestionBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        return saveAnswer(answerQuestionBean, null, false);
    }

    @RequestMapping(
            value = Constants.URL_ANSWER_MEDIA_QUESTION,
            method = RequestMethod.POST,
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE}
    )
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryResponseBean answerMediaQuestion(
            @RequestPart(PART_ANSWER) AnswerQuestionRequestBean answerQuestionBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken,
            @RequestPart(PART_FILE) MultipartFile file)
            throws Exception {
        return saveAnswer(answerQuestionBean, file, false);
    }

    @RequestMapping(value = Constants.URL_CLEAR_ANSWER, method = RequestMethod.POST)
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryResponseBean clearAnswer(
            @RequestBody AnswerQuestionRequestBean answerQuestionBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception{
        return saveAnswer(answerQuestionBean, null, true);
    }

    private FormEntryResponseBean saveAnswer(AnswerQuestionRequestBean answerQuestionBean,
            @Nullable MultipartFile file, boolean clear) throws Exception {

        SerializableFormSession serializableFormSession = categoryTimingHelper.timed(
                Constants.TimingCategories.GET_SESSION,
                () -> formSessionService.getSessionById(answerQuestionBean.getSessionId())
        );

        // add tags for future datadog/sentry requests
        datadog.addRequestScopedTag(Constants.FORM_NAME_TAG, serializableFormSession.getTitle());
        Sentry.setTag(Constants.FORM_NAME_TAG, serializableFormSession.getTitle());
        FormSession formEntrySession = categoryTimingHelper.timed(
                Constants.TimingCategories.INITIALIZE_SESSION,
                () -> formSessionFactory.getFormSession(serializableFormSession,
                        answerQuestionBean.getWindowWidth(), answerQuestionBean.getKeepAPMTraces())
        );

        String fileId = null;
        Path mediaDirPath = formEntrySession.getMediaDirectoryPath(restoreFactory.getDomain(),
                restoreFactory.getUsername(), restoreFactory.getAsUsername(), storageFactory.getAppId());
        if (clear) {
            formEntrySession.cleanCurrentMedia(mediaDirPath, answerQuestionBean.getFormIndex(),
                    mediaMetaDataService);
        }
        if (file != null) {
            fileId = categoryTimingHelper.timed(
                    Constants.TimingCategories.PROCESS_MEDIA,
                    () -> formEntrySession.saveMediaAnswer(file, answerQuestionBean.getFormIndex(), mediaDirPath,
                            mediaMetaDataService)
            );
        }

        Object answer = fileId != null ? fileId : answerQuestionBean.getAnswer();
        FormEntryResponseBean responseBean = categoryTimingHelper.timed(
                Constants.TimingCategories.PROCESS_ANSWER,
                () -> formEntrySession.answerQuestionToJson(
                        answer, answerQuestionBean.getFormIndex()
                )
        );

        categoryTimingHelper.timed(
                Constants.TimingCategories.VALIDATE_ANSWERS,
                () -> {
                    HashMap<String, ErrorBean> errors = validateAnswers(formEntrySession.getFormEntryController(),
                            formEntrySession.getFormEntryModel(),
                            answerQuestionBean.getAnswersToValidate(),
                            formEntrySession.getSkipValidation());
                    responseBean.setErrors(errors);
                }
        );

        updateSession(formEntrySession);

        responseBean.populateTranslations();

        categoryTimingHelper.timed(
                Constants.TimingCategories.COMPILE_RESPONSE,
                () -> {
                    responseBean.setTitle(serializableFormSession.getTitle());
                    responseBean.setSequenceId(serializableFormSession.getVersion());
                    responseBean.setInstanceXml(new InstanceXmlBean(serializableFormSession.getInstanceXml()));
                }
        );

        return responseBean;
    }

    @RequestMapping(value = Constants.URL_NEW_REPEAT, method = RequestMethod.POST)
    @ResponseBody
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryResponseBean newRepeat(@RequestBody RepeatRequestBean newRepeatRequestBean,
            @CookieValue(Constants.POSTGRES_DJANGO_SESSION_ID) String authToken) throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(
                newRepeatRequestBean.getSessionId());
        FormSession formEntrySession = formSessionFactory.getFormSession(serializableFormSession,
                newRepeatRequestBean.getWindowWidth(), newRepeatRequestBean.getKeepAPMTraces());
        JSONObject response = JsonActionUtils.descendRepeatToJson(formEntrySession.getFormEntryController(),
                formEntrySession.getFormEntryModel(),
                newRepeatRequestBean.getRepeatIndex());
        updateSession(formEntrySession);
        FormEntryResponseBean responseBean = mapper.readValue(response.toString(), FormEntryResponseBean.class);
        responseBean.setTitle(serializableFormSession.getTitle());
        responseBean.setInstanceXml(new InstanceXmlBean(serializableFormSession.getInstanceXml()));
        log.info("New response: " + responseBean);
        return responseBean;
    }

    @RequestMapping(value = Constants.URL_DELETE_REPEAT, method = RequestMethod.POST)
    @ResponseBody
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryResponseBean deleteRepeat(@RequestBody RepeatRequestBean deleteRepeatRequestBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(
                deleteRepeatRequestBean.getSessionId());
        FormSession formEntrySession = formSessionFactory.getFormSession(serializableFormSession,
                deleteRepeatRequestBean.getWindowWidth(), deleteRepeatRequestBean.getKeepAPMTraces());
        JSONObject response = JsonActionUtils.deleteRepeatToJson(formEntrySession.getFormEntryController(),
                formEntrySession.getFormEntryModel(),
                deleteRepeatRequestBean.getRepeatIndex());
        updateSession(formEntrySession);
        FormEntryResponseBean responseBean = mapper.readValue(response.toString(), FormEntryResponseBean.class);
        responseBean.setTitle(serializableFormSession.getTitle());
        responseBean.setInstanceXml(new InstanceXmlBean(serializableFormSession.getInstanceXml()));
        return responseBean;
    }

    @RequestMapping(value = Constants.URL_NEXT_INDEX, method = RequestMethod.POST)
    @ResponseBody
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryNavigationResponseBean getNext(@RequestBody SessionRequestBean requestBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(
                requestBean.getSessionId());
        FormSession formSession = formSessionFactory.getFormSession(serializableFormSession,
                requestBean.getWindowWidth(), requestBean.getKeepAPMTraces());
        formSession.stepToNextIndex();
        FormEntryNavigationResponseBean responseBean = formSession.getFormNavigation();
        updateSession(formSession);
        return responseBean;
    }

    @RequestMapping(value = Constants.URL_NEXT, method = RequestMethod.POST)
    @ResponseBody
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryNavigationResponseBean getNextSms(@RequestBody SessionRequestBean requestBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(
                requestBean.getSessionId());
        FormSession formSession = formSessionFactory.getFormSession(serializableFormSession,
                requestBean.getWindowWidth(), requestBean.getKeepAPMTraces());
        FormEntryNavigationResponseBean responseBean = formSession.getNextFormNavigation();
        updateSession(formSession);
        return responseBean;
    }

    @RequestMapping(value = Constants.URL_PREV_INDEX, method = RequestMethod.POST)
    @ResponseBody
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryNavigationResponseBean getPrevious(@RequestBody SessionRequestBean requestBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(
                requestBean.getSessionId());
        FormSession formSession = formSessionFactory.getFormSession(serializableFormSession,
                requestBean.getWindowWidth(), requestBean.getKeepAPMTraces());
        formSession.stepToPreviousIndex();
        FormEntryNavigationResponseBean responseBean = formSession.getFormNavigation();
        updateSession(formSession);
        return responseBean;
    }

    /**
     * Returns xml that does not contain irrelevant nodes
     * This xml should only be used for submission
     */
    @RequestMapping(value = Constants.URL_GET_INSTANCE, method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public GetInstanceResponseBean getRawInstance(@RequestBody SessionRequestBean requestBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(
                requestBean.getSessionId());
        FormSession formSession = formSessionFactory.getFormSession(serializableFormSession,
                requestBean.getWindowWidth(), requestBean.getKeepAPMTraces());
        return new GetInstanceResponseBean(formSession, false);
    }


    @RequestMapping(value = Constants.URL_CURRENT, method = RequestMethod.POST)
    @ResponseBody
    @UserLock
    @UserRestore
    @ConfigureStorageFromSession
    public FormEntryNavigationResponseBean getCurrent(@RequestBody SessionRequestBean requestBean,
            @CookieValue(name = Constants.POSTGRES_DJANGO_SESSION_ID, required = false) String authToken)
            throws Exception {
        org.commcare.formplayer.objects.SerializableFormSession serializableFormSession =
                formSessionService.getSessionById(requestBean.getSessionId());
        FormSession formSession = formSessionFactory.getFormSession(serializableFormSession,
                requestBean.getWindowWidth(), requestBean.getKeepAPMTraces());
        FormEntryNavigationResponseBean responseBean = formSession.getFormNavigation();
        updateSession(formSession);
        return responseBean;
    }

    private void updateSession(FormSession formEntrySession) throws Exception {
        categoryTimingHelper.timed(
                Constants.TimingCategories.UPDATE_SESSION,
                () -> formSessionService.saveSession(formEntrySession.serialize())
        );
    }
}
