@file:Suppress("LocalVariableName")

package com.jetbrains.rd.gradle.plugins

import com.jetbrains.rd.gradle.tasks.CrossTestKtRdTask
import com.jetbrains.rd.gradle.tasks.DotnetRunTask
import com.jetbrains.rd.gradle.tasks.InteropTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*

fun Project.applyCrossTest() = apply<CrossTestPlugin>()

@Suppress("UNUSED_VARIABLE")
class CrossTestPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = target.run {
        applyKotlinJVM()

        val `rd-net` = rootProject.project(":rd-net")
//        val `rd-cpp` = rootProject.project(":rd-cpp")
        val `rd-framework` = project(":rd-framework")

        evaluationDependsOn(`rd-net`.path)
//        evaluationDependsOn(`rd-cpp`.path)

        dependencies {
            `compile`(`rd-framework`)
            `implementation`(gradleApi())
        }

        /*fun getCppTaskByName(name: String): RunScriptTask {
            return `rd-cpp`.tasks.getByName(name)
        }*/

        fun getCsTaskByName(name: String): DotnetRunTask {
            return `rd-net`.tasks.getByName<DotnetRunTask>(name)
        }

        tasks {
            //region Kt

            fun CrossTestKtRdTask.initialize() {
                dependsOn("classes")
                classpath += files(target.buildDir
                        .resolve("classes")
                        .resolve("kotlin")
                        .resolve("main"))
                classpath += sourceSets.main.get().output
                classpath += configurations["compileClasspath"]
                classpath += configurations["runtimeClasspath"]
            }

            fun creatingKtServerTask() =
                creating(CrossTestKtRdTask::class) {
                    initialize()
                }

            val CrossTestKtServerAllEntities by creatingKtServerTask()

            val CrossTestKtServerBigBuffer by creatingKtServerTask()

            val CrossTestKtServerRdCall by creatingKtServerTask()
//endregion

//region KtCpp
            /*val CrossTestKtCppAllEntities by creating(InteropTask::class) {
                taskServer = CrossTestKtServerAllEntities
                taskClient = getCppTaskByName("CrossTestCppClientAllEntities")

                addDependencies()
            }

            val CrossTestKtCppBigBuffer by creating(InteropTask::class) {
                taskServer = CrossTestKtServerBigBuffer
                taskClient = getCppTaskByName("CrossTestCppClientBigBuffer")

                addDependencies()
            }

            val CrossTestKtCppRdCall by creating(InteropTask::class) {
                taskServer = CrossTestKtServerRdCall
                taskClient = getCppTaskByName("CrossTestCppClientRdCall")

                addDependencies()
            }*/
//endregion

//region KtCs
            val CrossTestKtCsAllEntities by creating(InteropTask::class) {
                taskServer = CrossTestKtServerAllEntities
                taskClient = getCsTaskByName("CrossTestCsClientAllEntities")

                lateInit()
            }

            val CrossTestKtCsBigBuffer by creating(InteropTask::class) {
                taskServer = CrossTestKtServerBigBuffer
                taskClient = getCsTaskByName("CrossTestCsClientBigBuffer")

                lateInit()
            }

            val CrossTestKtCsRdCall by creating(InteropTask::class) {
                taskServer = CrossTestKtServerRdCall
                taskClient = getCsTaskByName("CrossTestCsClientRdCall")

                lateInit()
            }
//endregion

            withType(Test::class) {
                dependsOn(withType(InteropTask::class))
            }
        }
    }
}