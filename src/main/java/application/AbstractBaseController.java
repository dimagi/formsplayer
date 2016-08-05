package application;

import beans.NewFormSessionResponse;
import beans.menus.CommandListResponseBean;
import beans.menus.EntityListResponse;
import beans.menus.MenuBean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.commcare.modern.session.SessionWrapper;
import org.commcare.util.cli.EntityScreen;
import org.commcare.util.cli.MenuScreen;
import org.commcare.util.cli.Screen;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.bind.annotation.ExceptionHandler;
import repo.FormSessionRepo;
import repo.MenuSessionRepo;
import repo.SerializableMenuSession;
import services.InstallService;
import services.RestoreService;
import session.FormSession;
import session.MenuSession;

import javax.servlet.http.HttpServletRequest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

/**
 * Base Controller class containing exception handling logic and
 * autowired beans used in both MenuController and FormController
 */
public abstract class AbstractBaseController {

    @Autowired
    protected RestoreService restoreService;

    @Autowired
    protected FormSessionRepo formSessionRepo;

    @Autowired
    protected MenuSessionRepo menuSessionRepo;

    @Autowired
    protected InstallService installService;

    @Autowired
    private JavaMailSenderImpl exceptionSender;

    @Autowired
    private SimpleMailMessage exceptionMessage;

    private final Log log = LogFactory.getLog(AbstractBaseController.class);


    public Object resolveFormGetNext(MenuSession menuSession) throws Exception {
        menuSession.getSessionWrapper().syncState();
        if(menuSession.getSessionWrapper().finishExecuteAndPop(menuSession.getSessionWrapper().getEvaluationContext())){
            Object nextMenu = getNextMenu(menuSession);
            menuSessionRepo.save(new SerializableMenuSession(menuSession));
            return nextMenu;
        }
        return null;
    }

    public Object getNextMenu(MenuSession menuSession) throws Exception {
        return getNextMenu(menuSession, 0);
    }

    private Object getNextMenu(MenuSession menuSession, int offset) throws Exception {
        return getNextMenu(menuSession, offset, "", null);
    }

    protected Object getNextMenu(MenuSession menuSession, int offset, String searchText, String[] breadcrumbs) throws Exception {

        Screen nextScreen;

        // If we were redrawing, remain on the current screen. Otherwise, advance to the next.
        nextScreen = menuSession.getNextScreen();
        // No next menu screen? Start form entry!
        if (nextScreen == null) {
            return generateFormEntryScreen(menuSession);
        } else {
            MenuBean menuResponseBean;

            // We're looking at a module or form menu
            if (nextScreen instanceof MenuScreen) {
                menuResponseBean = generateMenuScreen((MenuScreen) nextScreen, menuSession.getSessionWrapper(),
                        menuSession.getId());
            }
            // We're looking at a case list or detail screen (probably)
            else if (nextScreen instanceof EntityScreen) {
                menuResponseBean = generateEntityScreen((EntityScreen) nextScreen, offset, searchText,
                        menuSession.getId());
            } else {
                throw new Exception("Unable to recognize next screen: " + nextScreen);
            }
            menuResponseBean.setBreadcrumbs(breadcrumbs);
            return menuResponseBean;
        }
    }

    private CommandListResponseBean generateMenuScreen(MenuScreen nextScreen, SessionWrapper session,
                                                       String menuSessionId) {
        return new CommandListResponseBean(nextScreen, session, menuSessionId);
    }

    private EntityListResponse generateEntityScreen(EntityScreen nextScreen, int offset, String searchText,
                                                    String menuSessionId) {
        return new EntityListResponse(nextScreen, offset, searchText, menuSessionId);
    }

    private NewFormSessionResponse generateFormEntryScreen(MenuSession menuSession) throws Exception {
        FormSession formEntrySession = menuSession.getFormEntrySession();
        formSessionRepo.save(formEntrySession.serialize());
        menuSessionRepo.save(new SerializableMenuSession(menuSession));
        return new NewFormSessionResponse(formEntrySession);
    }

    @ExceptionHandler(Exception.class)
    public String handleError(HttpServletRequest req, Exception exception) {
        log.error("Request: " + req.getRequestURL() + " raised " + exception);
        exception.printStackTrace();
        sendExceptionEmail(exception);
        JSONObject errorReturn = new JSONObject();
        errorReturn.put("exception", exception);
        errorReturn.put("url", req.getRequestURL());
        errorReturn.put("status", "error");
        return errorReturn.toString();
    }

    private void sendExceptionEmail(Exception exception) {
        exceptionMessage.setText(getExceptionEmailBody(exception));
        exceptionMessage.setSubject("Formplayer Menu Exception: " + exception.getMessage());
        try {
            exceptionSender.send(exceptionMessage);
        } catch(MailSendException e){
            // I think we should fail quietly on this
            log.error("Couldn't send exception email: " + e);
        }
    }


    private String getExceptionEmailBody(Exception exception){
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        String formattedTime = dateFormat.format(new Date());
        return "Message: " + exception.getMessage() + " \n " +
                "Time : " + formattedTime + " \n " +
                "Trace: " + Arrays.toString(exception.getStackTrace());
    }
}
