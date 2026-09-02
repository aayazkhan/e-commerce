import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.math.BigDecimal
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

allprojects {
    group = "com.ecommerce.platform"
    version = "0.1.0-SNAPSHOT"
}

val phase10IntegrationTest = tasks.register("integrationTest") {
    group = "verification"
    description = "Runs all JUnit tests tagged integration across backend modules."
    onlyIf {
        providers.gradleProperty("runIntegration").map { it.equals("true", ignoreCase = true) }.orElse(false).get()
    }
}

val phase10E2eTest = tasks.register("e2eTest") {
    group = "verification"
    description = "Runs opt-in JUnit tests tagged e2e across backend modules."
    onlyIf {
        providers.gradleProperty("runE2e").map { it.equals("true", ignoreCase = true) }.orElse(false).get()
    }
}

val coverageProjects = mutableListOf<org.gradle.api.Project>()

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")
        coverageProjects += project

        extensions.configure<JacocoPluginExtension> {
            toolVersion = "0.8.13"
        }

        val testSourceSet = extensions.getByType<SourceSetContainer>().getByName("test")

        tasks.named<Test>("test") {
            useJUnitPlatform {
                if (!project.hasProperty("includeIntegration")) excludeTags("integration")
                if (!project.hasProperty("includeE2e")) excludeTags("e2e")
            }
            reports.junitXml.required.set(true)
            reports.html.required.set(true)
        }

        val integrationTask = tasks.register<Test>("integrationTest") {
            group = "verification"
            description = "Runs integration tests for ${project.path}."
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform { includeTags("integration") }
            onlyIf {
                providers.gradleProperty("runIntegration").map { it.equals("true", ignoreCase = true) }.orElse(false).get()
            }
            shouldRunAfter(tasks.named("test"))
            reports.junitXml.required.set(true)
            reports.html.required.set(true)
        }
        phase10IntegrationTest.configure { dependsOn(integrationTask) }

        val e2eTask = tasks.register<Test>("e2eTest") {
            group = "verification"
            description = "Runs e2e tests for ${project.path}."
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform { includeTags("e2e") }
            onlyIf {
                providers.gradleProperty("runE2e").map { it.equals("true", ignoreCase = true) }.orElse(false).get()
            }
            shouldRunAfter(integrationTask)
            reports.junitXml.required.set(true)
            reports.html.required.set(true)
        }
        phase10E2eTest.configure { dependsOn(e2eTask) }

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports {
                html.required.set(true)
                xml.required.set(true)
                csv.required.set(true)
            }
        }

        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(tasks.named("test"))
            violationRules {
                rule {
                    element = "BUNDLE"
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = BigDecimal("1.0")
                    }
                    limit {
                        counter = "BRANCH"
                        value = "COVEREDRATIO"
                        minimum = BigDecimal("1.0")
                    }
                }
            }
        }
    }
}

fun JacocoReport.configurePhase10Inputs() {
    setJacocoClasspath(coverageProjects.first().configurations.getByName("jacocoAnt"))
    val sourceSets = coverageProjects.map {
        it.extensions.getByType<SourceSetContainer>().getByName("main")
    }
    sourceDirectories.from(sourceSets.map { it.allSource.srcDirs })
    classDirectories.from(sourceSets.map { it.output.classesDirs })
    executionData.from(coverageProjects.map { it.layout.buildDirectory.file("jacoco/test.exec").get().asFile })
}

fun JacocoCoverageVerification.configurePhase10Inputs() {
    setJacocoClasspath(coverageProjects.first().configurations.getByName("jacocoAnt"))
    val sourceSets = coverageProjects.map {
        it.extensions.getByType<SourceSetContainer>().getByName("main")
    }
    sourceDirectories.from(sourceSets.map { it.allSource.srcDirs })
    classDirectories.from(sourceSets.map { it.output.classesDirs })
    executionData.from(coverageProjects.map { it.layout.buildDirectory.file("jacoco/test.exec").get().asFile })
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates aggregate HTML, XML, and CSV JaCoCo reports for all production modules."
    dependsOn(coverageProjects.map { it.tasks.named("test") })
    configurePhase10Inputs()
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacoco.xml"))
        csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacoco.csv"))
    }
}

tasks.register("jacocoTestCoverageVerification") {
    group = "verification"
    description = "Fails when aggregate production line or branch coverage is below 100%."
    dependsOn(tasks.named("jacocoTestReport"))
    doLast {
        val report = layout.buildDirectory.file("reports/jacoco/aggregate/jacoco.xml").get().asFile
        check(report.isFile) { "Aggregate JaCoCo XML report is missing: ${report.path}" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://xml.org/sax/features/validation", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val document = builder.parse(report)
        val counters = document.documentElement.childNodes
        val totals = buildMap {
            for (index in 0 until counters.length) {
                val node = counters.item(index)
                if (node.nodeName == "counter") {
                    val element = node as org.w3c.dom.Element
                    put(element.getAttribute("type"), element.getAttribute("missed").toLong() to element.getAttribute("covered").toLong())
                }
            }
        }
        val failures = listOf("LINE", "BRANCH").mapNotNull { type ->
            val (missed, covered) = totals[type] ?: error("JaCoCo XML has no $type counter")
            if (missed > 0) "$type coverage is ${covered}/${missed + covered}; required 100%" else null
        }
        check(failures.isEmpty()) { failures.joinToString("; ") }
    }
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Alias for the aggregate Phase 10 JaCoCo report."
    dependsOn(tasks.named("jacocoTestReport"))
}
