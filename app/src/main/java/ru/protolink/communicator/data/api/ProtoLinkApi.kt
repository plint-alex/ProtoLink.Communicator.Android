package ru.protolink.communicator.data.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

data class LoginRequest(val login: String, val password: String)
data class LoginResponse(
    @SerializedName("accessToken") val accessToken: String?,
    @SerializedName("token") val token: String?,
    @SerializedName("refreshToken") val refreshToken: String?,
    @SerializedName("expirationTime") val expirationTime: String?,
    @SerializedName("userId") val userId: String?,
    @SerializedName("id") val id: String?,
    @SerializedName("login") val login: String?
)

data class GetEntitiesRequest(
    val ids: List<String>? = null,
    val parentIds: List<String>? = null,
    val skip: Int = 0,
    val take: Int? = 200,
    val includeValues: Boolean = true,
    val showHidden: Boolean = false
)

data class EntityDto(
    val id: String,
    val code: String?,
    val version: Int = 0,
    val creationTime: String? = null,
    val updateTime: String? = null,
    val values: List<EntityValueDto>? = null
)

data class EntityValueDto(
    val value: JsonElement?,
    val parents: List<String>? = null
)

data class AddEntityRequest(
    val code: String,
    val parentIds: List<String>,
    val values: List<AddValueRequest>? = null
)

data class AddValueRequest(
    /** Matches API TypeOfValue: String=0, Int=1, Double=2, DateTime=3, File=4 */
    val type: Int = TYPE_STRING,
    val value: Any,
    val parentIds: List<String> = emptyList()
) {
    companion object {
        const val TYPE_STRING = 0
        const val TYPE_DATETIME = 3
    }
}

data class DeleteEntityRequest(val id: String)
data class AddParentRequest(val id: String, val parentId: String)
data class RemoveParentRequest(val id: String, val parentId: String)
data class IdResponse(val id: String?)

data class SendCommandRequest(
    val commandType: String,
    val targetUserId: String,
    val parameters: Map<String, Any?>
)

data class UserDto(
    val id: String?,
    val login: String?,
    val name: String?
)

data class ApiVersionInfoDto(
    val version: String?,
    val buildDate: String?,
    val framework: String?
)

data class ServerVersionResponseDto(
    val api: ApiVersionInfoDto?
)

interface ProtoLinkApi {
    @POST("api/Authentication/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/Version")
    suspend fun getVersion(): ServerVersionResponseDto

    @GET("api/Authentication/GetUserById")
    suspend fun getUserById(@Query("userId") userId: String): UserDto

    @POST("api/Entities/GetEntities")
    suspend fun getEntities(@Body body: GetEntitiesRequest): List<EntityDto>

    @POST("api/Entities/AddEntity")
    suspend fun addEntity(@Body body: AddEntityRequest): IdResponse

    @POST("api/Entities/AddValue/{entityId}")
    suspend fun addValue(@Path("entityId") entityId: String, @Body body: AddValueRequest): ResponseBody

    @POST("api/Entities/DeleteEntity")
    suspend fun deleteEntity(@Body body: DeleteEntityRequest): ResponseBody

    @POST("api/Entities/AddParent")
    suspend fun addParent(@Body body: AddParentRequest): ResponseBody

    @POST("api/Entities/RemoveParent")
    suspend fun removeParent(@Body body: RemoveParentRequest): ResponseBody

    @POST("api/Entities/AddPermission")
    suspend fun addPermission(@Body body: Map<String, Any?>): ResponseBody

    @Streaming
    @GET("api/Files/getFile/{id}")
    suspend fun getFile(@Path("id") id: String): Response<ResponseBody>

    @Multipart
    @POST("api/Files/addFile")
    suspend fun addFile(
        @Part("EntityId") entityId: RequestBody,
        @Part file: MultipartBody.Part
    ): ResponseBody

    @POST("api/commands/send")
    suspend fun sendCommand(@Body body: SendCommandRequest): ResponseBody
}
