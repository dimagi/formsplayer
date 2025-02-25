package org.commcare.formplayer.application;

import static org.commcare.formplayer.objects.SerializableFormSession.SubmitStatus.PROCESSED_STACK;
import static org.commcare.formplayer.objects.SerializableFormSession.SubmitStatus.PROCESSED_XML;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.commcare.cases.util.InvalidCaseGraphException;
import org.commcare.formplayer.api.process.FormRecordProcessorHelper;
import org.commcare.formplayer.beans.NotificationMessage;
import org.commcare.formplayer.beans.OpenRosaResponse;
import org.commcare.formplayer.beans.SubmitResponseBean;
import org.commcare.formplayer.beans.menus.ErrorBean;
import org.commcare.formplayer.engine.FormplayerConfigEngine;
import org.commcare.formplayer.engine.FormplayerTransactionParserFactory;
import org.commcare.formplayer.exceptions.SyncRestoreException;
import org.commcare.formplayer.objects.FormVolatilityRecord;
import org.commcare.formplayer.objects.SerializableFormSession;
import org.commcare.formplayer.objects.SerializableMenuSession;
import org.commcare.formplayer.services.CategoryTimingHelper;
import org.commcare.formplayer.services.FormSessionService;
import org.commcare.formplayer.services.FormplayerStorageFactory;
import org.commcare.formplayer.services.InstallService;
import org.commcare.formplayer.services.MediaValidator;
import org.commcare.formplayer.services.MenuSessionFactory;
import org.commcare.formplayer.services.MenuSessionRunnerService;
import org.commcare.formplayer.services.MenuSessionService;
import org.commcare.formplayer.services.RestoreFactory;
import org.commcare.formplayer.services.SubmitService;
import org.commcare.formplayer.session.FormSession;
import org.commcare.formplayer.session.MenuSession;
import org.commcare.formplayer.util.Constants;
import org.commcare.formplayer.util.FormSubmissionContext;
import org.commcare.formplayer.util.FormplayerDatadog;
import org.commcare.formplayer.util.NotificationLogger;
import org.commcare.formplayer.util.ProcessingStep;
import org.commcare.formplayer.util.serializer.SessionSerializer;
import org.commcare.session.CommCareSession;
import org.commcare.util.FileUtils;
import org.commcare.util.screen.EntityScreenContext;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.annotation.Resource;

import datadog.trace.api.Trace;
import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;

@Component
public class FormSubmissionHelper {

    @Autowired
    protected FormSessionService formSessionService;

    @Autowired
    protected MenuSessionService menuSessionService;

    @Autowired
    protected RestoreFactory restoreFactory;

    @Autowired
    private NotificationLogger notificationLogger;

    @Autowired
    protected MenuSessionFactory menuSessionFactory;

    @Autowired
    protected MenuSessionRunnerService runnerService;

    @Autowired
    protected InstallService installService;

    @Autowired
    private SubmitService submitService;

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

    private final Log log = LogFactory.getLog(FormSubmissionController.class);

    @Value("${formplayer.form.submit.max_attachments}")
    private Integer maxAttachmentsPerForm;

    public SubmitResponseBean processAndSubmitForm(HttpServletRequest request, String sessionID,
            String domain, boolean isPrevalidated, Map<String, Object> answers,
            String windowWidth, boolean keepAPMTraces) throws Exception {
        FormSubmissionContext context = getFormProcessingContext(request, sessionID, domain, isPrevalidated,
                answers, windowWidth, keepAPMTraces);

        ProcessingStep.StepFactory stepFactory = new ProcessingStep.StepFactory(context, formSessionService);
        Stream<ProcessingStep> processingSteps = Stream.of(
                stepFactory.makeStep("validateAnswers", this::validateAnswers),
                stepFactory.makeStep("processFormXml", this::processFormXml, PROCESSED_XML),
                stepFactory.makeStep("updateVolatility", this::updateVolatility),
                stepFactory.makeStep("performSync", this::performSync),
                stepFactory.makeStep("doEndOfFormNav", this::doEndOfFormNav, PROCESSED_STACK)
        );

        // execute steps one at a time, only proceeding to the next step if the previous step was successful
        Optional<SubmitResponseBean> error = processingSteps
                .map((step) -> executeStep(request, step))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

        if (error.isPresent()) {
            return error.get();
        }

        // Only delete session immediately after successful submit
        formSessionService.deleteSessionById(sessionID);

        return context.getResponse();
    }

    private FormSubmissionContext getFormProcessingContext(HttpServletRequest request, String sessionID, String domain,
            boolean isPrevalidated, Map<String, Object> answers, String windowWidth, boolean keepAPMTraces) throws Exception {
        SerializableFormSession serializableFormSession = formSessionService.getSessionById(sessionID);

        String menuSessionId = serializableFormSession.getMenuSessionId();
        SerializableMenuSession serializableMenuSession = null;
        FormplayerConfigEngine engine = null;
        CommCareSession commCareSession = null;
        if (menuSessionId != null && !menuSessionId.trim().equals("")) {
            serializableMenuSession = menuSessionService.getSessionById(menuSessionId);

            engine = installService.configureApplication(
                    serializableMenuSession.getInstallReference(),
                    serializableMenuSession.isPreview()).first;

            commCareSession = SessionSerializer.deserialize(engine.getPlatform(),
                    serializableMenuSession.getCommcareSession());
        }

        // add tags for future datadog/sentry requests
        datadog.addRequestScopedTag(Constants.FORM_NAME_TAG, serializableFormSession.getTitle());
        Sentry.setTag(Constants.FORM_NAME_TAG, serializableFormSession.getTitle());

        // package additional args to pass to category timing helper
        Map<String, String> extras = new HashMap();
        extras.put(Constants.DOMAIN_TAG, domain);

        FormSession formEntrySession = formSessionFactory.getFormSession(serializableFormSession, commCareSession, windowWidth, keepAPMTraces);
        return new FormSubmissionContext(
                request,
                isPrevalidated,
                answers,
                formEntrySession,
                serializableMenuSession,
                engine,
                commCareSession,
                extras);
    }

    /**
     * Execute a step in the process and return an error response to halt processing or
     * and empty response to continue.
     *
     * @param request The HTTP Request object
     * @param step    A supplier object that performs one unit of form processing and returns
     *                a SubmitResponseBean.
     * @return Empty Optional if the processing should continue otherwise an Optional containing the
     * error response.
     */
    private Optional<SubmitResponseBean> executeStep(HttpServletRequest request, ProcessingStep step) {
        SubmitResponseBean response = null;
        try {
            response = step.execute();
        } catch (Exception e) {
            response = getErrorResponse(
                    request, Constants.SUBMIT_RESPONSE_ERROR,
                    e.getMessage(), e);
        }
        if (response.getStatus().equals(Constants.SUBMIT_RESPONSE_STATUS_POSITIVE)) {
            step.recordCheckpoint();
            return Optional.empty();  // continue processing
        }
        log.debug(String.format("Aborting execution of processing steps after error in step: %s", step));
        return Optional.of(response);
    }

    private SubmitResponseBean validateAnswers(FormSubmissionContext context) throws Exception {
        Map<String, ErrorBean> errors = categoryTimingHelper.timed(
                Constants.TimingCategories.VALIDATE_SUBMISSION,
                () -> validateSubmitAnswers(context),
                context.getMetricsTags()
        );
        if (errors.size() > 0 || !context.isPrevalidated()) {
            return context.error(Constants.ANSWER_RESPONSE_STATUS_NEGATIVE, errors);
        }
        return context.success();
    }


    private SubmitResponseBean getErrorResponse(
            HttpServletRequest request,
            String status,
            String message,
            Throwable exception) {
        SubmitResponseBean responseBean = new SubmitResponseBean(status);
        NotificationMessage notification = new NotificationMessage(
                message,
                true,
                NotificationMessage.Tag.submit);
        responseBean.setNotification(notification);
        notificationLogger.logNotification(notification, request);
        log.error(message, exception);
        return responseBean;
    }

    private SubmitResponseBean processFormXml(FormSubmissionContext context) throws Exception {
        try {
            restoreFactory.setAutoCommit(false);
            processXmlInner(context);

            FormSession formSession = context.getFormEntrySession();
            MultiValueMap<String, HttpEntity<Object>> body = getMultiPartFormBody(formSession);

            String response = submitService.submitForm(
                    body,
                    context.getFormEntrySession().getPostUrl()
            );
            parseSubmitResponseMessage(response, context.getResponse());

            restoreFactory.commit();
        } catch (InvalidCaseGraphException e) {
            return getErrorResponse(
                    context.getHttpRequest(), Constants.SUBMIT_RESPONSE_CASE_CYCLE_ERROR,
                    "Form submission failed due to a cyclic case relationship. " +
                            "Please contact the support desk to help resolve this issue.", e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            return context.error(Constants.SUBMIT_RESPONSE_TOO_MANY_REQUESTS);
        } catch (HttpClientErrorException e) {
            return getErrorResponse(
                    context.getHttpRequest(), Constants.SUBMIT_RESPONSE_ERROR,
                    String.format("Form submission failed with error response: %s", e.getMessage()),
                    e);
        } finally {
            // If autoCommit hasn't been reset to `true` by the commit() call then an error occurred
            if (!restoreFactory.getAutoCommit()) {
                // rollback sets autoCommit back to `true`
                restoreFactory.rollback();
            }
        }
        return context.success();
    }


    private MultiValueMap<String, HttpEntity<Object>> getMultiPartFormBody(FormSession formSession)
            throws IOException {
        MultiValueMap<String, HttpEntity<Object>> body = new LinkedMultiValueMap<>();

        // Add any media files associated with the form
        Path mediaDirPath = formSession.getMediaDirectoryPath(restoreFactory.getDomain(),
                restoreFactory.getUsername(), restoreFactory.getAsUsername(), storageFactory.getAppId());
        File mediaFile = mediaDirPath.toFile();
        if (mediaFile.exists()) {
            File[] files = Objects.requireNonNull(mediaDirPath.toFile().listFiles());
            if (files.length > maxAttachmentsPerForm) {
                MediaValidator.throwAttachmentError("form.upload.attachments.limit.exceeded",
                        maxAttachmentsPerForm.toString());
            }
            for (File file : files) {
                MediaValidator.validateFile(new FileInputStream(file), file.getName(), file.length());

                String contentType = FileUtils.getContentType(file);
                if (!StringUtils.hasText(contentType)) {
                    contentType = "application/octet-stream";
                }

                HttpEntity<Object> filePart = createFilePart(file.getName(), file.getName(),
                        Files.readAllBytes(file.toPath()), contentType);
                body.add(file.getName(), filePart);
            }
        }

        // Add the form xml
        HttpEntity<Object> xmlPart = createFilePart("xml_submission_file", "xml_submission_file.xml",
                formSession.getInstanceXml(false), "text/xml");
        body.add("xml_submission_file", xmlPart);
        return body;
    }


    private static HttpEntity<Object> createFilePart(String partName, String fileName, Object content,
            String contentType) {
        MultiValueMap<String, String> fileMap = new LinkedMultiValueMap<>();
        ContentDisposition contentDisposition = ContentDisposition
                .builder("form-data")
                .name(partName)
                .filename(fileName)
                .build();
        fileMap.add(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());
        fileMap.add(HttpHeaders.CONTENT_TYPE, contentType);
        return new HttpEntity<>(content, fileMap);
    }

    private void processXmlInner(FormSubmissionContext context) throws Exception {
        FormplayerTransactionParserFactory factory = new FormplayerTransactionParserFactory(
                restoreFactory.getSqlSandbox(),
                storageFactory.getPropertyManager().isBulkPerformanceEnabled()
        );
        FormRecordProcessorHelper.processXML(factory, context.getFormEntrySession().submitGetXml());
        categoryTimingHelper.timed(
                Constants.TimingCategories.PURGE_CASES,
                () -> {
                    if (factory.wereCaseIndexesDisrupted()
                            && storageFactory.getPropertyManager().isAutoPurgeEnabled()) {
                        FormRecordProcessorHelper.purgeCases(factory.getSqlSandbox());
                    }
                },
                context.getMetricsTags()
        );
    }

    @Trace
    private SubmitResponseBean doEndOfFormNav(FormSubmissionContext context) throws Exception{
        Object nextScreen = categoryTimingHelper.timed(
                Constants.TimingCategories.END_OF_FORM_NAV,
                () -> {
                    if (context.getSerializableMenuSession() == null) {
                        return null;
                    }
                    return doEndOfFormNav(
                            context.getSerializableMenuSession(),
                            context.getEngine(),
                            context.getCommCareSession()
                    );
                },
                context.getMetricsTags()
        );
        context.getResponse().setNextScreen(nextScreen);
        return context.success();
    }


    private Object doEndOfFormNav(SerializableMenuSession serializedSession, FormplayerConfigEngine engine,
            CommCareSession commCareSession) throws Exception {
        log.info("End of form navigation with serialized menu session: " + serializedSession);
        MenuSession menuSession = menuSessionFactory.buildSession(serializedSession, engine, commCareSession);
        return runnerService.resolveFormGetNext(menuSession, new EntityScreenContext());
    }

    private SubmitResponseBean performSync(FormSubmissionContext context) throws SyncRestoreException {
        boolean suppressAutosync = context.getFormEntrySession().getSuppressAutosync();

        if (storageFactory.getPropertyManager().isSyncAfterFormEnabled() && !suppressAutosync) {
            //If configured to do so, do a sync with server now to ensure dats is up to date.
            //Need to do before end of form nav triggers, since the new data might change the
            //validity of the form

            restoreFactory.performTimedSync(true, false);
        }
        return context.success();
    }

    private SubmitResponseBean updateVolatility(FormSubmissionContext context) {
        FormVolatilityRecord volatilityRecord = context.getFormEntrySession().getSessionVolatilityRecord();
        if (volatilityCache != null && volatilityRecord != null) {
            FormVolatilityRecord existingRecord = volatilityCache.get(volatilityRecord.getKey());
            if (existingRecord != null && existingRecord.matchesUser(context.getFormEntrySession())) {
                volatilityRecord = existingRecord;
            }
            volatilityRecord.updateFormSubmitted(context.getFormEntrySession());
            volatilityRecord.write(volatilityCache);
        }
        return context.success();
    }

    private void parseSubmitResponseMessage(String responseBody, SubmitResponseBean submitResponseBean) {
        if (responseBody != null) {
            try {
                Serializer serializer = new Persister();
                OpenRosaResponse openRosaResponse = serializer.read(OpenRosaResponse.class, responseBody);
                if (openRosaResponse != null && openRosaResponse.getMessage() != null) {
                    submitResponseBean.setSubmitResponseMessage(openRosaResponse.getMessage());
                }
            } catch (Exception e) {
                log.error("Exception parsing submission response body", e);
            }
        }
    }


    /**
     * Iterate over all answers and attempt to save them to check for validity.
     * Submit the complete XML instance to HQ if valid.
     */
    @Trace
    private Map<String, ErrorBean> validateSubmitAnswers(FormSubmissionContext context) {
        return FormController.validateAnswers(
                context.getFormEntrySession().getFormEntryController(),
                context.getFormEntrySession().getFormEntryModel(),
                context.getAnswers(),
                context.getFormEntrySession().getSkipValidation());
    }

}
