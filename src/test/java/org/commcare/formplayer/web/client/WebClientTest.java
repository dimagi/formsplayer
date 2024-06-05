package org.commcare.formplayer.web.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.google.common.collect.ImmutableListMultimap;

import org.commcare.formplayer.services.RestoreFactory;
import org.commcare.formplayer.utils.MockRestTemplateBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URISyntaxException;

@ExtendWith(MockitoExtension.class)
public class WebClientTest {

    private MockRestServiceServer mockServer;

    private WebClient webClient;

    @Mock
    private RestoreFactory restoreFactory;

    @BeforeEach
    public void init() throws URISyntaxException {
        RestTemplate restTemplate = new MockRestTemplateBuilder().getRestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        webClient = new WebClient();
        webClient.setRestoreFactory(restoreFactory);
        webClient.setRestTemplate(restTemplate);

        when(restoreFactory.getRequestHeaders(any())).thenReturn(new HttpHeaders());
    }

    @Test
    public void testPostFormData() {
        String url = "http://localhost:8000/a/demo/receiver/1234";
        ImmutableListMultimap<String, String> postData = ImmutableListMultimap.of(
                "a", "1",
                "b", "2",
                "b", "not 2"
        );

        MultiValueMap<String, String> expectedBody = new LinkedMultiValueMap<>();
        postData.forEach(expectedBody::add);

        mockServer.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedBody))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.TEXT_HTML)
                        .body("response123")
                );

        // call method under test
        String response = webClient.postFormData(url, postData);
        Assertions.assertEquals("response123", response);

        mockServer.verify();
    }

}
