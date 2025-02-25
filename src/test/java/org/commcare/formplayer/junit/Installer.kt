package org.commcare.formplayer.junit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.commons.logging.LogFactory
import org.assertj.core.api.Assertions
import org.commcare.formplayer.auth.DjangoAuth
import org.commcare.formplayer.beans.InstallRequestBean
import org.commcare.formplayer.beans.menus.CommandListResponseBean
import org.commcare.formplayer.services.FormplayerStorageFactory
import org.commcare.formplayer.services.MenuSessionFactory
import org.commcare.formplayer.services.MenuSessionRunnerService
import org.commcare.formplayer.services.RestoreFactory
import org.commcare.formplayer.session.MenuSession
import org.commcare.formplayer.util.SessionUtils
import org.commcare.formplayer.utils.CheckedSupplier
import org.commcare.formplayer.utils.FileUtils
import org.commcare.modern.util.Pair
import org.commcare.util.screen.EntityScreenContext
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.io.File
import java.io.IOException
import java.io.StringReader

/**
 * Utility for performing app installs in tests.
 */
class Installer(
    private val restoreFactory: RestoreFactory,
    private val storageFactory: FormplayerStorageFactory,
    private val menuSessionFactory: MenuSessionFactory,
    private val menuSessionRunnerService: MenuSessionRunnerService
) {

    /**
     * This function performs an app install outside of the request cycle. In order to do that
     * successfully it mimics behaviour in the UserRestoreAspect and the AppInstallAspect.
     *
     * @param requestPath path to JSON file with InstallRequestBean content as well as
     * 'installReference'
     */
    @Throws(Exception::class)
    fun doInstall(requestPath: String): CommandListResponseBean? {
        val refAndBean = getInstallReferenceAndBean(
            requestPath,
            InstallRequestBean::class.java
        )
        val bean = refAndBean.second
        storageFactory.configure(bean)
        restoreFactory.configure(bean, DjangoAuth("key"))
        if (bean.isMustRestore) {
            restoreFactory.performTimedSync()
        }
        val install = CheckedSupplier {
            val menuSession: MenuSession = menuSessionFactory.buildSession(
                bean.username,
                bean.domain,
                bean.appId,
                bean.locale,
                bean.oneQuestionPerScreen,
                bean.restoreAs,
                bean.preview,
                bean.windowWidth,
                bean.keepAPMTraces
            )
            menuSessionRunnerService.getNextMenu(null, menuSession, null, EntityScreenContext()) as CommandListResponseBean
        }
        return mockInstallReference(install, refAndBean.first)
    }

    companion object {
        private val mapper = ObjectMapper()
        private val log = LogFactory.getLog(Installer::class.java)

        /**
         * Turn a test name or relative path into an app install reference.
         *
         * Accepts:
         * * an archive name in `src/test/resources/archives`
         * * an exploded archive directly in `src/test/resources/archives`
         * * any path relative to src/test/resources` that points to an exploded archive directory
         * * any path relative to src/test/resources` that points to a CCZ or profile.ccpr file
         */
        @JvmStatic
        fun getInstallReference(nameOrPath: String): String {
            if (checkInstallReference(nameOrPath)) {
                return nameOrPath
            }
            val paths = arrayOf(
                "%s/profile.ccpr",
                "archives/%s.ccz",
                "archives/%s/profile.ccpr"
            )
            for (template in paths) {
                val path = String.format(template, nameOrPath)
                if (checkInstallReference(path)) {
                    log.info("Found install reference at $path")
                    return path
                }
            }
            throw RuntimeException("Unable to find install reference for $nameOrPath")
        }

        /**
         * Utility method to extract the 'installReference' field from test JSON files and map the
         * remaining JSON fields to the given bean type.
         *
         * @param requestPath classpath relative path to JSON file
         * @param clazz       Bean class map JSON to
         * @return Pair of 'installReference' and deserialized JSON bean
         * @throws IOException
         */
        @JvmStatic
        @Throws(IOException::class)
        fun <T> getInstallReferenceAndBean(requestPath: String, clazz: Class<T>): Pair<String, T> {
            val root: JsonNode = mapper.readTree(
                StringReader(FileUtils.getFile(this::class.java, requestPath))
            )
            val installReference = (root as ObjectNode).remove("installReference").asText()
            val beanContent: String = mapper.writeValueAsString(root)
            val bean = mapper.readValue(beanContent, clazz)
            return Pair(installReference, bean)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun <T> mockInstallReference(supplier: CheckedSupplier<T>, installReference: String): T {
            val answer: Answer<*> = Answer { invocation: InvocationOnMock ->
                val method = invocation.method
                // only mock the `resolveInstallReference` method
                if ("resolveInstallReference" == method.name) {
                    return@Answer installReference
                } else {
                    return@Answer invocation.callRealMethod()
                }
            }
            Mockito.mockStatic(SessionUtils::class.java, answer).use { _ ->
                return supplier.get()
            }
        }

        private fun checkInstallReference(path: String): Boolean {
            val resource = this::class.java.classLoader.getResource(path) ?: return false
            val file = File(resource.file)
            if (file.isDirectory) {
                return false
            }
            Assertions.assertThat(arrayOf(".ccz", ".ccpr"))
                .`as`("check file extension: %s", path)
                .anyMatch { s: String? -> path.endsWith(s!!) }
            return true
        }
    }
}
