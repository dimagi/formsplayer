package application;

import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import services.FormplayerLockRegistry;
import services.HqUserDetailsService;
import util.Constants;
import util.FormplayerHttpRequest;
import util.RequestUtils;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Filter that determines whether a request needs to be authorized,
 * then attempts authorization by checking the auth token against
 * Django's sessionid table, returning an Unauthorized response if
 * appropriate
 *
 * @author wspride
 */
@Component
public class FormplayerAuthFilter extends OncePerRequestFilter {

    @Autowired
    HqUserDetailsService userDetailsService;

    @Autowired
    FormplayerLockRegistry userLockRegistry;

    @Value("${touchforms.authkey}")
    private String authKey;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        FormplayerHttpRequest request = new FormplayerHttpRequest((HttpServletRequest) req);
        if (isAuthorizationRequired(request)) {
            // These are order dependent
            if (getSessionId(request) == null) {
                setResponseUnauthorized(response, "Invalid session id");
                return;
            }
            if (authKey != null && authKey.equals(getSessionId(request))) {
                logger.info("Logging in as touchforms_user");
            }
            else {
                setDomain(request);
                setUserDetails(request);
                JSONObject data = RequestUtils.getPostData(request);
                if (!authorizeRequest(request, data.getString("domain"), getUsername(data))) {
                    setResponseUnauthorized(response, "Invalid user");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private String getUsername(JSONObject data) {
        try {
            return data.getString("username");
        } catch (JSONException e) {
            // TODO: Delete when no longer using HQ to proxy requests for Edit Forms
            return data.getJSONObject("session-data").getString("username");
        }
    }

    private void setUserDetails(FormplayerHttpRequest request) {
        request.setUserDetails(userDetailsService.getUserDetails(request.getDomain(), getSessionId(request)));

    }

    /**
     * Searches through the request cookie's to get the session id of the request.
     * @param request
     * @return The sessionid or null if not found
     */
    private String getSessionId(FormplayerHttpRequest request) {
        if(request.getCookies() !=  null) {
            for (Cookie cookie : request.getCookies()) {
                if(Constants.POSTGRES_DJANGO_SESSION_ID.equals(cookie.getName())){
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void setDomain(FormplayerHttpRequest request) {
        JSONObject data = RequestUtils.getPostData(request);
        if (data.getString("domain") == null) {
            throw new RuntimeException("No domain specified for the request: " + request.getRequestURI());
        }
        request.setDomain(data.getString("domain"));
    }

    /**
     * Currently, we want to auth every POST and GET request. In particular, we want to let OPTIONS
     * requests through since these don' have auth and we need them for CORS preflight
     * @param request the request to be authorized
     * @return request needs to be authorized
     */
    private boolean isAuthorizationRequired(HttpServletRequest request){
        return true;
    }

    /**
     * This function ensures that the request session's user and domain matches the user and domain
     * sent in the body of the POST request. Note, superusers are able to authenticate
     * as other users.
     * @param request
     * @param domain
     * @param username
     * @return true if authorized, false otherwise
     */
    private boolean authorizeRequest(FormplayerHttpRequest request, String domain, String username) {
        if (request.getUserDetails() == null) {
            return false;
        }
        return request.getUserDetails().isAuthorized(domain, username);
    }

    @Override
    public void destroy() {

    }

    public void setResponseUnauthorized(HttpServletResponse response, String message) {
        response.reset();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");

        PrintWriter writer = null;
        JSONObject responseJSON = new JSONObject();
        responseJSON.put("error", message);
        try {
            writer = response.getWriter();
        } catch (IOException e) {
            throw new RuntimeException("Unable to write response", e);
        }
        writer.write(responseJSON.toString());
        writer.flush();
        writer.close();
    }

}
