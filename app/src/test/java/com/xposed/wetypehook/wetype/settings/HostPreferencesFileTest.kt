package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostPreferencesFileTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun readsExistingAppearanceValuesAndIgnoresUnrelatedEntries() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("""
            <map>
                <int name="light_color" value="-1110125356" />
                <float name="candidate_background_corner" value="60.0" />
                <boolean name="disable_hot_update" value="true" />
                <long name="host_sync_revision" value="1789468984736" />
                <set name="unrelated"><string>ignored</string></set>
            </map>
        """.trimIndent())
        assertEquals(mapOf(
            "light_color" to -1110125356,
            "candidate_background_corner" to 60f,
            "disable_hot_update" to true
        ), HostPreferencesFile(file).read())
    }

    @Test
    fun readsStringValuesFromElementText() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("""
            <map>
                <string name="custom_icon_path">M0 0h10v10H0z</string>
                <string name="settings_write_token">3f2a-ignored</string>
            </map>
        """.trimIndent())
        assertEquals(mapOf(
            "custom_icon_path" to "M0 0h10v10H0z",
            "settings_write_token" to "3f2a-ignored"
        ), HostPreferencesFile(file).read())
    }

    @Test
    fun readsStringValuesAfterOtherEntriesAndUnescapesEntities() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("""
            <map>
                <int name="blur_radius" value="60" />
                <string name="custom_icon_path">M0 0h10v10H0z M20 0h5v5H20z</string>
                <boolean name="disable_hot_update" value="false" />
                <string name="empty_path"></string>
            </map>
        """.trimIndent())
        assertEquals(mapOf(
            "blur_radius" to 60,
            "custom_icon_path" to "M0 0h10v10H0z M20 0h5v5H20z",
            "disable_hot_update" to false,
            "empty_path" to ""
        ), HostPreferencesFile(file).read())
    }

    @Test
    fun readsAnotherWritersReplacementWithTheSameReader() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        val reader = HostPreferencesFile(file)
        file.writeText("""<map><int name="blur_radius" value="60" /></map>""")
        assertEquals(60, reader.read()["blur_radius"])
        val replacement = temporaryFolder.newFile("replacement.xml")
        replacement.writeText("""<map><int name="blur_radius" value="20" /></map>""")
        check(replacement.renameTo(file))
        assertEquals(20, reader.read()["blur_radius"])
    }

    @Test
    fun usesTheCommittedBackupDuringAnInterruptedWrite() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        val reader = HostPreferencesFile(file)
        val backup = File("${file.path}.bak")
        backup.writeText("""<map><int name="blur_radius" value="60" /></map>""")
        file.writeText("<map><int")
        assertEquals(60, reader.read()["blur_radius"])
        file.writeText("""<map><int name="blur_radius" value="20" /></map>""")
        check(backup.delete())
        assertEquals(20, reader.read()["blur_radius"])
    }

    @Test
    fun rejectsPartialXmlInsteadOfPublishingAPartialSnapshot() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("""<map><int name="blur_radius" value="20" />""")
        assertThrows(Exception::class.java) { HostPreferencesFile(file).read() }
    }

    @Test
    fun missingFileHasNoSavedSettings() {
        assertEquals(emptyMap<String, Any>(), HostPreferencesFile(
            File(temporaryFolder.root, "wetype_settings.xml")
        ).read())
    }
}
