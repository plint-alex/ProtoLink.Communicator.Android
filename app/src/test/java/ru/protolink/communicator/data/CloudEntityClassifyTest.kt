package ru.protolink.communicator.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudEntityClassifyTest {
    @Test
    fun codeName_withTypeCloudFolder_isFolder() {
        assertTrue(CloudEntityClassify.isFolder(CloudEntityClassify.TYPE_CLOUD_FOLDER_ID, "User_Test_1"))
        assertFalse(CloudEntityClassify.isFile(CloudEntityClassify.TYPE_CLOUD_FOLDER_ID, "User_Test_1"))
    }

    @Test
    fun codeName_withTypeCloudFile_isFile() {
        assertTrue(CloudEntityClassify.isFile(CloudEntityClassify.TYPE_CLOUD_FILE_ID, "note.txt"))
        assertFalse(CloudEntityClassify.isFolder(CloudEntityClassify.TYPE_CLOUD_FILE_ID, "note.txt"))
    }

    @Test
    fun legacyCode_cloudFolder_withoutType_isFolder() {
        assertTrue(CloudEntityClassify.isFolder(null, CloudCodes.CLOUD_FOLDER))
        assertTrue(CloudEntityClassify.isFolder(null, CloudCodes.CLOUD_ROOT))
    }

    @Test
    fun legacyCode_cloudFile_withoutType_isFile() {
        assertTrue(CloudEntityClassify.isFile(null, CloudCodes.CLOUD_FILE))
        assertFalse(CloudEntityClassify.isFolder(null, CloudCodes.CLOUD_FILE))
    }

    @Test
    fun typeWins_over_legacyCodeMismatch() {
        assertTrue(CloudEntityClassify.isFolder(CloudEntityClassify.TYPE_CLOUD_FOLDER_ID, CloudCodes.CLOUD_FILE))
        assertTrue(CloudEntityClassify.isFile(CloudEntityClassify.TYPE_CLOUD_FILE_ID, CloudCodes.CLOUD_FOLDER))
    }
}
