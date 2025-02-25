package org.commcare.formplayer.services;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.commcare.core.interfaces.RemoteInstanceFetcher;
import org.commcare.formplayer.beans.InstallRequestBean;
import org.commcare.formplayer.beans.SessionNavigationBean;
import org.commcare.formplayer.engine.FormplayerConfigEngine;
import org.commcare.formplayer.objects.SerializableMenuSession;
import org.commcare.formplayer.session.MenuSession;
import org.commcare.formplayer.util.FormplayerDatadog;
import org.commcare.session.CommCareSession;
import org.commcare.session.SessionFrame;
import org.commcare.suite.model.EntityDatum;
import org.commcare.suite.model.MenuDisplayable;
import org.commcare.suite.model.RemoteQueryDatum;
import org.commcare.suite.model.SessionDatum;
import org.commcare.suite.model.StackFrameStep;
import org.commcare.util.screen.CommCareSessionException;
import org.commcare.util.screen.EntityScreen;
import org.commcare.util.screen.EntityScreenContext;
import org.commcare.util.screen.MenuScreen;
import org.commcare.util.screen.QueryScreen;
import org.commcare.util.screen.Screen;
import org.javarosa.core.model.instance.ExternalDataInstance;
import org.javarosa.xml.util.InvalidStructureException;
import org.javarosa.xml.util.UnfullfilledRequirementsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.StringJoiner;
import java.util.Vector;
import java.util.stream.Collectors;

import datadog.trace.api.Trace;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;

/**
 * Class containing logic for accepting a NewSessionRequest and services,
 * restoring the user, opening the new form, and returning the question list response.
 */
@Component
public class MenuSessionFactory {

    private static final String NEXT_SCREEN = "NEXT_SCREEN";

    @Autowired
    private RestoreFactory restoreFactory;

    @Autowired
    private InstallService installService;

    @Autowired
    private CaseSearchHelper caseSearchHelper;

    @Autowired
    protected FormplayerStorageFactory storageFactory;

    @Autowired
    private VirtualDataInstanceService virtualDataInstanceService;

    @Value("${commcarehq.host}")
    private String host;

    private static final Log log = LogFactory.getLog(MenuSessionFactory.class);

    public Screen rebuildSessionFromFrame(MenuSession menuSession, CaseSearchHelper caseSearchHelper)
            throws CommCareSessionException, RemoteInstanceFetcher.RemoteInstanceException {
        return rebuildSessionFromFrame(menuSession, caseSearchHelper, true);
    }

    /**
     * Rebuild the MenuSession from its stack frame. This is used after end of form navigation.
     * By re-walking the frame, we establish the set of selections the user 'would' have made to get
     * to this state without doing end of form navigation. Such a path must always exist in a valid app.
     */
    @Trace
    public Screen rebuildSessionFromFrame(MenuSession menuSession, CaseSearchHelper caseSearchHelper,
            boolean respectRelevancy)
            throws CommCareSessionException, RemoteInstanceFetcher.RemoteInstanceException {
        boolean keepAPMTraces = (boolean)menuSession.getMetaSessionContext().get("keepAPMTraces");
        FormplayerDatadog.handleKeepDropAPMTraces(keepAPMTraces);
        Vector<StackFrameStep> steps = menuSession.getSessionWrapper().getFrame().getSteps();
        List<StackFrameStep> processedSteps = new ArrayList<>();
        menuSession.resetSession();
        EntityScreenContext entityScreenContext = new EntityScreenContext(respectRelevancy);
        Screen screen = menuSession.getNextScreen(false, entityScreenContext);
        int processedStepsCount = 0;
        boolean needsFullInit = false;
        while (screen != null) {
            String currentStep = null;
            if (screen instanceof MenuScreen) {
                if (menuSession.autoAdvanceMenu(screen, storageFactory.getPropertyManager().isAutoAdvanceMenu(), respectRelevancy)) {
                    screen = menuSession.getNextScreen(needsFullInit, entityScreenContext);
                    continue;
                }

                MenuDisplayable[] options = respectRelevancy ? ((MenuScreen)screen).getMenuDisplayables()
                        : ((MenuScreen)screen).getAllChoices();
                for (int i = 0; i < options.length; i++) {
                    for (StackFrameStep step : steps) {
                        if (step.getId().equals(options[i].getCommandID())) {
                            currentStep = String.valueOf(i);
                            processedSteps.add(step);
                            // final step, needs to init fully to show to screen
                            needsFullInit = ++processedStepsCount == steps.size();
                        }
                    }
                }
                if (currentStep == null && processedStepsCount != steps.size()) {
                    checkAndLogCommandIDMatchError(steps, processedSteps, options);
                }
            } else if (screen instanceof EntityScreen) {
                EntityScreen entityScreen = (EntityScreen)screen;
                entityScreen.initReferences(menuSession.getSessionWrapper());
                SessionDatum neededDatum = entityScreen.getSession().getNeededDatum();
                for (StackFrameStep step : steps) {
                    if (step.getId().equals(neededDatum.getDataId())) {
                        if (entityScreen.referencesContainStep(step.getValue())) {
                            currentStep = step.getValue();
                            processedSteps.add(step);
                            needsFullInit = ++processedStepsCount == steps.size();
                        } else {
                            logStepNotInEntityScreenError(entityScreen, neededDatum, step);
                        }
                        break;
                    }
                }

                // Only init subscreen if we are not going to auto-launch a different screen
                String nextInput = currentStep == null ? "" : currentStep;
                entityScreen.evaluateAutoLaunch(nextInput);
                if (entityScreen.getAutoLaunchAction() == null) {
                    entityScreen.initListSubScreen();
                }

                if (currentStep != null && currentStep != NEXT_SCREEN && entityScreen.shouldBeSkipped()) {
                    menuSession.handleInput(screen, currentStep, needsFullInit, true, false, entityScreenContext);
                    screen = menuSession.getNextScreen(needsFullInit, entityScreenContext);
                    continue;
                }

                if (currentStep == null && processedStepsCount != steps.size()) {
                    checkAndLogCaseIDMatchError(steps, processedSteps, neededDatum.getDataId());
                }
            } else if (screen instanceof QueryScreen) {
                QueryScreen queryScreen = (QueryScreen)screen;
                RemoteQueryDatum neededDatum = (RemoteQueryDatum) menuSession.getSessionWrapper().getNeededDatum();
                for (StackFrameStep step : steps) {
                    if (step.getId().equals(neededDatum.getDataId())) {
                        URI uri = null;
                        try {
                            uri = new URI(step.getValue());
                            processedSteps.add(step);
                        } catch (URISyntaxException e) {
                            e.printStackTrace();
                            throw new CommCareSessionException("Query URL format error: " + e.getMessage(), e);
                        }
                        ImmutableMultimap.Builder<String, String> dataBuilder = ImmutableMultimap.builder();
                        step.getExtras().forEach((key, value) -> {
                            if (value instanceof Collection) {
                                dataBuilder.putAll(key, ((Collection)value));
                            } else {
                                // only to maintain backward compatibility with old serialised app db state,
                                // can be removed in subsequent deploys
                                dataBuilder.putAll(key, value.toString());
                            }
                        });
                        try {
                            Multimap<String, String> caseSearchMetricTags = caseSearchHelper.getMetricTags(menuSession);
                            dataBuilder.putAll(caseSearchMetricTags);
                            ExternalDataInstance searchDataInstance = caseSearchHelper.getRemoteDataInstance(
                                queryScreen.getQueryDatum().getDataId(),
                                queryScreen.getQueryDatum().useCaseTemplate(),
                                uri.toURL(),
                                dataBuilder.build(),
                                false
                            );
                            queryScreen.updateSession(searchDataInstance);
                            screen = menuSession.getNextScreen(needsFullInit, entityScreenContext);
                            currentStep = NEXT_SCREEN;
                            break;
                        } catch (InvalidStructureException | IOException | XmlPullParserException | UnfullfilledRequirementsException e) {
                            e.printStackTrace();
                            throw new CommCareSessionException("Query response format error: " + e.getMessage(), e);
                        }
                    }
                }
            }
            if (currentStep == null) {
                break;
            } else if (currentStep != NEXT_SCREEN) {
                menuSession.handleInput(screen, currentStep, needsFullInit, true, false, entityScreenContext);
                menuSession.addSelection(currentStep);
                screen = menuSession.getNextScreen(needsFullInit, entityScreenContext);
            }
        }
        return screen;
    }

    @Trace
    public MenuSession buildSession(String username,
                                    String domain,
                                    String appId,
                                    String locale,
                                    boolean oneQuestionPerScreen,
                                    String asUser,
                                    boolean preview,
                                    String windowWidth,
                                    boolean keepAPMTraces) throws Exception {
        HashMap<String, Object> metaSessionContext = new HashMap<String, Object>();
        metaSessionContext.put("windowWidth", windowWidth);
        metaSessionContext.put("keepAPMTraces", keepAPMTraces);
        FormplayerDatadog.handleKeepDropAPMTraces(keepAPMTraces);
        return new MenuSession(username, domain, appId, locale,
                installService, restoreFactory, host, oneQuestionPerScreen, asUser, preview,
                new FormplayerRemoteInstanceFetcher(caseSearchHelper, virtualDataInstanceService), metaSessionContext, storageFactory);
    }

    @Trace
    public MenuSession buildSession(SerializableMenuSession serializableMenuSession, FormplayerConfigEngine engine,
            CommCareSession commCareSession) throws Exception {
        MenuSession menuSession =  new MenuSession(serializableMenuSession, engine, commCareSession, restoreFactory,
                new FormplayerRemoteInstanceFetcher(caseSearchHelper, virtualDataInstanceService),
                storageFactory);
        FormplayerDatadog.handleKeepDropAPMTraces((boolean)menuSession.getMetaSessionContext().get("keepAPMTraces"));
        return menuSession;
    }

    @Trace
    public MenuSession getMenuSessionFromBean(SessionNavigationBean sessionNavigationBean) throws Exception {
        FormplayerDatadog.handleKeepDropAPMTraces(sessionNavigationBean.getKeepAPMTraces());
        MenuSession menuSession = performInstall(sessionNavigationBean);
        menuSession.setCurrentBrowserLocation(sessionNavigationBean.getGeoLocation());
        menuSession.setMetaSessionContext(sessionNavigationBean.getWindowWidth(), sessionNavigationBean.getKeepAPMTraces());
        return menuSession;
    }

    @Trace
    private MenuSession performInstall(InstallRequestBean bean) throws Exception {
        if (bean.getAppId() == null || bean.getAppId().isEmpty()) {
            throw new RuntimeException("App_id must not be null.");
        }
        FormplayerDatadog.handleKeepDropAPMTraces(bean.getKeepAPMTraces());
        return buildSession(
                bean.getUsername(),
                bean.getDomain(),
                bean.getAppId(),
                bean.getLocale(),
                bean.getOneQuestionPerScreen(),
                bean.getRestoreAs(),
                bean.getPreview(),
                bean.getWindowWidth(),
                bean.getKeepAPMTraces()
        );
    }

    private void checkAndLogCommandIDMatchError(Vector<StackFrameStep> steps, List<StackFrameStep> processedSteps,
        MenuDisplayable[] options) throws CommCareSessionException {
        Vector<StackFrameStep> unprocessedSteps = new Vector<>();
        for (StackFrameStep step : steps) {
            if (!processedSteps.contains(step)) {
                unprocessedSteps.add(step);
            }
        }
        for (StackFrameStep unprocessedStep : unprocessedSteps) {
            if (unprocessedStep.getType().equals(SessionFrame.STATE_COMMAND_ID) &&
                !unprocessedStep.getId().startsWith("claim_command")) {
                StringJoiner optionsIDJoiner = new StringJoiner(", ", "[", "]");
                StringJoiner stepIDJoiner = new StringJoiner(", ", "[", "]");
                for (MenuDisplayable option : options) {
                    optionsIDJoiner.add(option.getCommandID());
                }
                for (StackFrameStep step : steps) {
                    stepIDJoiner.add(step.getId());
                }
                log.error(
                    "Match Error: Steps " + stepIDJoiner.toString() +
                    " do not contain a valid option " + optionsIDJoiner.toString()
                );
            }
        }
    }

    private void checkAndLogCaseIDMatchError(Vector<StackFrameStep> steps, List<StackFrameStep> processedSteps,
        String neededDatumID) throws CommCareSessionException {
        Vector<StackFrameStep> unprocessedSteps = new Vector<>();
        for (StackFrameStep step : steps) {
            if (!processedSteps.contains(step)) {
                unprocessedSteps.add(step);
            }
        }
        for (StackFrameStep unprocessedStep : unprocessedSteps) {
            if (unprocessedStep.getType().equals(SessionFrame.STATE_DATUM_VAL)) {
                StringJoiner stepIDJoiner = new StringJoiner(", ", "[", "]");
                for (StackFrameStep step : steps) {
                    stepIDJoiner.add(step.getId());
                }
                log.error(
                    "Match Error: Steps " + stepIDJoiner.toString() +
                    " do not contain a valid datum ID " + neededDatumID
                );
            }
        }
    }

    private void logStepNotInEntityScreenError(EntityScreen entityScreen, SessionDatum neededDatum, StackFrameStep step) throws CommCareSessionException {
        // This block constructs the message to display then throws the exception
        List<String> refsList = entityScreen.getReferences().stream()
        .map(ref -> EntityScreen.getReturnValueFromSelection(ref, (EntityDatum) neededDatum, entityScreen.getEvalContext()))
        .toList();

        String referencesString = String.join(",\n  ", refsList);
        String nodeSetString = ((EntityDatum) neededDatum).getNodeset().toString();

        log.error(String.format("Could not get %s=%s from entity screen.\nNode set: %s\nReferences: \n[%s]",
        neededDatum.getDataId(), step.getValue(), nodeSetString, referencesString));

    }
}
