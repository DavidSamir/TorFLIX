package com.torfilx.tools.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The installed launcher (installDist) starts the class named in the build file. The other tests call
 * [CatalogPublisherCli] directly, so without this one a wrong name would only show up when the
 * maintainer runs the tool.
 */
class LauncherTest {

    @Test
    fun `the application main class exists and has a static main method`() {
        val name = System.getProperty("torfilx.catalogPublisher.mainClass")
        assertWithMessage("the build passes application.mainClass to the tests").that(name).isNotNull()

        val mainClass = Class.forName(name, false, LauncherTest::class.java.classLoader)
        val main = mainClass.getMethod("main", Array<String>::class.java)

        assertThat(Modifier.isStatic(main.modifiers)).isTrue()
        assertThat(Modifier.isPublic(main.modifiers)).isTrue()
        assertThat(main.returnType).isEqualTo(Void.TYPE)
    }
}
