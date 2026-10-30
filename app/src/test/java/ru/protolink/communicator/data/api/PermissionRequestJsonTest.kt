package ru.protolink.communicator.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pins JSON field names against Api RemovePermissionContract / AddPermissionContract. */
class PermissionRequestJsonTest {

    private val gson = Gson()

    private fun toJson(body: Any): JsonObject = gson.toJsonTree(body).asJsonObject

    @Test
    fun removePermission_serializesEntityIdAndPermissionForId() {
        val json = toJson(RemovePermissionRequest(entityId = "e1", permissionForId = "u1"))

        assertEquals(setOf("entityId", "permissionForId"), json.keySet())
        assertEquals("e1", json["entityId"].asString)
        assertEquals("u1", json["permissionForId"].asString)
    }

    @Test
    fun addPermission_standalone_serializesId() {
        val json = toJson(AddPermissionRequest(permissionForId = "u1", canWrite = true, id = "e1"))

        assertEquals("e1", json["id"].asString)
        assertEquals("u1", json["permissionForId"].asString)
        assertEquals(true, json["canWrite"].asBoolean)
        assertEquals(false, json["includeSubtree"].asBoolean)
        assertFalse(json.has("entityId"))
    }

    @Test
    fun addPermission_folderPeopleGrant_serializesIncludeSubtree() {
        val json = toJson(
            AddPermissionRequest(
                permissionForId = "u1",
                canWrite = false,
                id = "folder1",
                includeSubtree = true
            )
        )

        assertEquals(true, json["includeSubtree"].asBoolean)
        assertEquals("folder1", json["id"].asString)
    }
}
