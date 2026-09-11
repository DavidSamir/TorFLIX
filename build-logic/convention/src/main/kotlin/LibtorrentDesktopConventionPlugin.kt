import com.torfilx.buildlogic.libs
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import javax.inject.Inject

/**
 * Lets a pure-JVM module load libtorrent's desktop native library, for its tests and for the
 * publisher tool.
 *
 * libtorrent4j loads its native code with `System.load(path)` when the `libtorrent4j.jni.path` system
 * property is set. This plugin extracts the library for the build machine's operating system from the
 * published jars and hands that path to every Test and JavaExec task in the module.
 *
 * The Android app never uses this. Its native libraries come from the Android artifacts declared in
 * :core:torrent, and nothing here is ever packaged into an APK.
 */
class LibtorrentDesktopConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val natives = configurations.create("libtorrentDesktopNatives") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
        }
        dependencies {
            add(natives.name, libs.findLibrary("libtorrent4j-windows").get())
            add(natives.name, libs.findLibrary("libtorrent4j-linux").get())
            add(natives.name, libs.findLibrary("libtorrent4j-macosx").get())
        }

        val version = libs.findVersion("libtorrent4j").get().requiredVersion
        val extract = tasks.register<ExtractLibtorrentNatives>("extractLibtorrentNatives") {
            jars.from(natives)
            outputDir.set(layout.buildDirectory.dir("libtorrent-natives"))
        }
        val library = extract.flatMap { it.outputDir.file(nativeFileName(version)) }

        tasks.withType(Test::class.java).configureEach {
            dependsOn(extract)
            jvmArgumentProviders.add(
                objects.newInstance(LibtorrentNativePath::class.java).apply { this.library.set(library) },
            )
        }
        tasks.withType(JavaExec::class.java).configureEach {
            dependsOn(extract)
            jvmArgumentProviders.add(
                objects.newInstance(LibtorrentNativePath::class.java).apply { this.library.set(library) },
            )
        }
    }

    /** The file name libtorrent4j publishes for this operating system; only 64-bit x86 builds exist. */
    private fun nativeFileName(version: String): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "win" in os -> "libtorrent4j-$version.dll"
            "mac" in os || "darwin" in os -> "libtorrent4j-$version.dylib"
            else -> "libtorrent4j-$version.so"
        }
    }
}

/** Copies the 64-bit native libraries out of the libtorrent4j desktop jars into one flat directory. */
abstract class ExtractLibtorrentNatives : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun extract() {
        fileSystem.sync {
            jars.files.forEach { jar ->
                from(archives.zipTree(jar)) {
                    include("lib/x86_64/*")
                    eachFile { path = name }
                }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
    }
}

/** Passes the extracted library's path to a forked JVM, as an input so a change re-runs the task. */
abstract class LibtorrentNativePath : CommandLineArgumentProvider {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val library: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Dlibtorrent4j.jni.path=${library.get().asFile.absolutePath}")
}
