package dev.sort.doris

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.PluginRegistrationContract

class DorisPluginRegistrationTest : BasePlatformTestCase() {
    fun testRegistrationContract() {
        PluginRegistrationContract("dev.sort.doris-intellij-plugin", "dev.sort.doris").check(project)
    }
}
