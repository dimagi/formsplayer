package org.commcare.formplayer.junit.request

import org.commcare.formplayer.beans.EvaluateXPathRequestBean
import org.commcare.formplayer.beans.EvaluateXPathResponseBean
import org.commcare.formplayer.objects.SerializableFormSession
import org.commcare.formplayer.services.FormSessionService
import org.commcare.formplayer.util.Constants
import org.commcare.formplayer.utils.FileUtils
import org.springframework.test.web.servlet.MockMvc

/**
 * Request class for making a mock 'evaluate-xpath' request
 */
class EvaluateXpathRequest(
    mockMvc: MockMvc,
    private val sessionId: String,
    private val xpath: String,
    private val formSessionService: FormSessionService,
    private val windowWidth: String?,
    private val locale: String?
) : MockRequest<EvaluateXPathRequestBean, EvaluateXPathResponseBean>(
    mockMvc, Constants.URL_EVALUATE_XPATH, EvaluateXPathResponseBean::class.java
) {

    fun request(): Response<EvaluateXPathResponseBean> {
        val bean = mapper.readValue(
            FileUtils.getFile(this.javaClass, "requests/evaluate_xpath/evaluate_xpath.json"),
            EvaluateXPathRequestBean::class.java
        )
        bean.sessionId = sessionId
        bean.xpath = xpath
        bean.debugOutputLevel = Constants.BASIC_NO_TRACE
        bean.windowWidth = windowWidth
        bean.locale = locale

        populateFromSession(bean)
        return requestWithBean(bean)
    }

    private fun populateFromSession(bean: EvaluateXPathRequestBean): EvaluateXPathRequestBean {
        val session: SerializableFormSession = formSessionService.getSessionById(sessionId)
        bean.username = session.username
        bean.domain = session.domain
        bean.sessionId = sessionId
        return bean
    }
}
