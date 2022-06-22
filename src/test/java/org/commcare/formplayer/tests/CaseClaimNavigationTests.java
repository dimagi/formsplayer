package org.commcare.formplayer.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Multimap;

import org.commcare.formplayer.beans.NewFormResponse;
import org.commcare.formplayer.beans.menus.CommandListResponseBean;
import org.commcare.formplayer.beans.menus.EntityListResponse;
import org.commcare.formplayer.objects.QueryData;
import org.commcare.formplayer.utils.FileUtils;
import org.commcare.formplayer.utils.MockRequestUtils;
import org.commcare.formplayer.utils.TestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;

@WebMvcTest
@ContextConfiguration(classes = TestContext.class)
public class CaseClaimNavigationTests extends BaseTestClass {

    private static final String APP_PATH = "archives/caseclaim";

    @Autowired
    CacheManager cacheManager;

    MockRequestUtils mockRequest;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        configureRestoreFactory("caseclaimdomain", "caseclaimusername");
        cacheManager.getCache("case_search").clear();
        mockRequest = new MockRequestUtils(webClientMock, restoreFactoryMock);
    }

    @Override
    protected String getMockRestoreFileName() {
        return "restores/caseclaim.xml";
    }


    @Test
    public void testNormalClaim() throws Exception {
        ArrayList<String> selections = new ArrayList<>();
        selections.add("1");  // select menu
        selections.add("action 1");  // load case search

        QueryData queryData = new QueryData();
        queryData.setExecute("case_search.m1", true);

        EntityListResponse entityListResponse;
        try (MockRequestUtils.VerifiedMock ignore = mockRequest.mockQuery("query_responses/case_claim_response.xml")) {
            entityListResponse = sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    EntityListResponse.class);
        }
        assertEquals(1, entityListResponse.getEntities().length);
        assertEquals("0156fa3e-093e-4136-b95c-01b13dae66c6",
                entityListResponse.getEntities()[0].getId());

        selections.add("0156fa3e-093e-4136-b95c-01b13dae66c6");

        CommandListResponseBean commandListResponseBean;
        try (
                MockRequestUtils.VerifiedMock ignoredPostMock = mockRequest.mockPost(true);
                MockRequestUtils.VerifiedMock ignoredRestoreMock = mockRequest.mockRestore("restores/caseclaim3.xml");
        ) {
            commandListResponseBean = sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    CommandListResponseBean.class);
        }
        assertEquals(2, commandListResponseBean.getCommands().length);
    }

    @Test
    public void testPostInEntry() throws Exception {
        ArrayList<String> selections = new ArrayList<>();
        selections.add("2");  // m2
        selections.add("0");  // 1st form

        QueryData queryData = new QueryData();

        EntityListResponse entityListResponse = sessionNavigateWithQuery(selections,
                APP_PATH,
                queryData,
                EntityListResponse.class);

        assertThat(entityListResponse.getEntities()).anyMatch(e -> {
            return e.getId().equals("56306779-26a2-4aa5-a952-70c9d8b21e39");
        });

        verifyNoInteractions(webClientMock);  // post should only be executed once form is selected

        selections.add("56306779-26a2-4aa5-a952-70c9d8b21e39");
        try(MockRequestUtils.VerifiedMock ignored = mockRequest.mockPost(false)) {
            sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    NewFormResponse.class);
        }
    }

    @Test
    public void testPostInEntryWithQuery_RelevantFalse() throws Exception {
        ArrayList<String> selections = new ArrayList<>();
        selections.add("2");  // m2
        selections.add("1");  // 2nd form

        QueryData queryData = new QueryData();

        EntityListResponse entityListResponse;
        try (MockRequestUtils.VerifiedMock ignored = mockRequest.mockQuery("query_responses/case_claim_response_owned.xml")) {
            entityListResponse = sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    EntityListResponse.class);
        }

        assertThat(entityListResponse.getEntities()).anyMatch(e -> {
            return e.getId().equals("3512eb7c-7a58-4a95-beda-205eb0d7f163");
        });

        Mockito.reset(webClientMock);
        selections.add("3512eb7c-7a58-4a95-beda-205eb0d7f163");
        sessionNavigateWithQuery(selections,
                APP_PATH,
                queryData,
                NewFormResponse.class);
        // post should not be fired due to relevant condition evaluating to false
        verifyNoInteractions(webClientMock);
    }

    @Test
    public void testPostInEntryWithQuery_RelevantTrue() throws Exception {
        ArrayList<String> selections = new ArrayList<>();
        selections.add("2");  // m2
        selections.add("1");  // 2nd form

        QueryData queryData = new QueryData();

        EntityListResponse entityListResponse;
        try (MockRequestUtils.VerifiedMock ignored = mockRequest.mockQuery("query_responses/case_claim_response.xml")) {
            entityListResponse = sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    EntityListResponse.class);
        }
        assertThat(entityListResponse.getEntities()).anyMatch(e -> {
            return e.getId().equals("0156fa3e-093e-4136-b95c-01b13dae66c6");
        });

        selections.add("0156fa3e-093e-4136-b95c-01b13dae66c6");
        NewFormResponse formResponse;
        try (
                MockRequestUtils.VerifiedMock ignoredPostMock = mockRequest.mockPost(true);
                MockRequestUtils.VerifiedMock ignoredRestoreMock = mockRequest.mockRestore("restores/caseclaim3.xml");
        ) {
            formResponse = sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    NewFormResponse.class);
        }

        // case was included in restore and is now in the case DB
        checkXpath(
                formResponse.getSessionId(),
                "count(instance('casedb')/casedb/case[@case_id='0156fa3e-093e-4136-b95c-01b13dae66c6'])",
                "1"
        );
    }

    /**
     * This tests that the session volatiles are cleared after the sync. The 'post'
     * and the 'assertion' share the same XPath case lookup expression so without clearing volatiles
     * the result is cached from the 'post' and not re-evaluated after the sync which causes
     * the assertion to fail.
     */
    @Test
    public void testPostInEntryWithQuery_clearVolatiles() throws Exception {
        ArrayList<String> selections = new ArrayList<>();
        selections.add("2");  // m2
        selections.add("2");  // 3rd form

        QueryData queryData = new QueryData();

        EntityListResponse entityListResponse;
        try (MockRequestUtils.VerifiedMock ignored = mockRequest.mockQuery("query_responses/case_claim_response.xml")) {
            entityListResponse = sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    EntityListResponse.class);
        }
        assertThat(entityListResponse.getEntities()).anyMatch(e -> {
            return e.getId().equals("0156fa3e-093e-4136-b95c-01b13dae66c6");
        });

        selections.add("0156fa3e-093e-4136-b95c-01b13dae66c6");
        NewFormResponse formResponse;
        try (
                MockRequestUtils.VerifiedMock ignoredPostMock = mockRequest.mockPost(true);
                MockRequestUtils.VerifiedMock ignoredRestoreMock = mockRequest.mockRestore("restores/caseclaim3.xml");
        ) {
            formResponse = sessionNavigateWithQuery(selections,
                    APP_PATH,
                    queryData,
                    NewFormResponse.class);
        }
        if (formResponse.getNotification() != null && formResponse.getNotification().isError()) {
            fail(formResponse.getNotification().getMessage());
        }
    }
}
