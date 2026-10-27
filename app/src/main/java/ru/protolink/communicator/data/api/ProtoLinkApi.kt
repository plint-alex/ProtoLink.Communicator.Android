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
    @SerializedName("login") val login: String?,
    @SerializedName("error") val error: String? = null
)

data class RegisterRequest(val email: String, val password: String)
data class RegisterResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("userUpdated") val userUpdated: Boolean? = null,
    @SerializedName("emailError") val emailError: String? = null,
    @SerializedName("error") val error: String? = null
)

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

data class ChangePasswordResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("error") val error: String? = null
)

data class GetEntitiesRequest(
    val ids: List<String>? = null,
    val parentIds: List<String>? = null,
    /** Immediate parents of these ids (API IdsToFindParents). */
    val idsToFindParents: List<String>? = null,
    val skip: Int = 0,
    val take: Int? = 200,
    val includeValues: Boolean = true,
    val showHidden: Boolean = false,
    /** 1 id = self-chat; 2 ids = pair. Server filters sender:/receiver:, newest-first. */
    val conversationUserIds: List<String>? = null,
    /** S6 Shared with me: list caller's direct share roots (no parentIds). */
    val sharedWithMe: Boolean = false
)

data class EntityDto(
    val id: String,
    val code: String?,
    val version: Int = 0,
    val creationTime: String? = null,
    val updateTime: String? = null,
    val values: List<EntityValueDto>? = null,
    val userWhoCreatedId: String? = null,
    /** Populated for Shared with me rows — effective CanWrite from the caller's direct grant(s). */
    val canWrite: Boolean? = null
)

/** Minimal GetEntity payload (share paste + MainParent axis AT-PC5). */
data class GetEntityCardDto(
    val id: String?,
    val name: String? = null,
    val code: String? = null,
    val mainParentId: String? = null
)

data class EntityValueDto(
    val value: JsonElement?,
    val parents: List<String>? = null
)

data class AddEntityRequest(
    val code: String,
    val parentIds: List<String>,
    val values: List<AddValueRequest>? = null,
    /** Peer read (CanWrite=false) so the other user can load the message via GetEntities. */
    val permissions: List<AddPermissionRequest>? = null
)

data class AddPermissionRequest(
    val permissionForId: String,
    val canWrite: Boolean = false,
    /** Used by standalone AddPermission; ignored when nested under AddEntity. */
    val id: String? = null
)

/** api/Entities/GetPermissions row (M3 shareability). */
data class EntityPermissionDto(
    val id: String? = null,
    val permissionForId: String? = null,
    val permissionForCode: String? = null,
    val canWrite: Boolean = false,
    val isUser: Boolean = false,
    val principalKind: String? = null,
    val displayName: String? = null,
    val inheritedFromEntityId: String? = null
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

    @POST("api/Authentication/register")
    suspend fun register(
        @Body body: RegisterRequest,
        @Query("lang") lang: String
    ): RegisterResponse

    @POST("api/Authentication/changePassword")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<ChangePasswordResponse>

    @GET("api/Version")
    suspend fun getVersion(): ServerVersionResponseDto

    @GET("api/Authentication/GetUserById")
    suspend fun getUserById(@Query("userId") userId: String): UserDto

    @POST("api/Entities/GetEntities")
    suspend fun getEntities(@Body body: GetEntitiesRequest): List<EntityDto>

    /** GetEntity card — name + mainParentId (AT-PC5). */
    @GET("api/Entities/GetEntity/{id}")
    suspend fun getEntity(
        @Path("id") id: String,
        @Query("fromCache") fromCache: Boolean = true
    ): GetEntityCardDto

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
    suspend fun addPermission(@Body body: AddPermissionRequest): ResponseBody

    @GET("api/Entities/GetPermissions")
    suspend fun getPermissions(@Query("entityId") entityId: String): List<EntityPermissionDto>

    @Streaming
    @GET("api/Files/getFile/{id}")
    suspend fun getFile(@Path("id") id: String): Response<ResponseBody>

    /** Resized preview (M6). Mime is not trusted — caller must verify image content-type/decode. */
    @Streaming
    @GET("api/Files/getImage/{id}")
    suspend fun getImage(
        @Path("id") id: String,
        @Query("width") width: Int,
        @Query("height") height: Int
    ): Response<ResponseBody>

    @Multipart
    @POST("api/Files/addFile")
    suspend fun addFile(
        @Part("EntityId") entityId: RequestBody,
        @Part file: MultipartBody.Part
    ): ResponseBody

    /** Cycle-2 atomic messenger media send (client-contract §9). */
    @Multipart
    @POST("api/Messenger/sendWithAttachments")
    suspend fun sendWithAttachments(
        @Part("partnerUserId") partnerUserId: RequestBody,
        @Part("chatFolderId") chatFolderId: RequestBody?,
        @Part("bodyText") bodyText: RequestBody?,
        @Part("existingAttachmentIds") existingAttachmentIds: RequestBody,
        @Part files: List<MultipartBody.Part>
    ): SendWithAttachmentsResponse

    @POST("api/commands/send")
    suspend fun sendCommand(@Body body: SendCommandRequest): ResponseBody

    /** Share sheet (AT-S41) — guest links + grants. */
    @POST("api/Share/Create")
    suspend fun createShareLink(@Body body: CreateShareLinkRequest): CreateShareLinkResult

    @GET("api/Share/List")
    suspend fun listShareLinks(@Query("entityId") entityId: String): List<ShareLinkListItemDto>

    @POST("api/Share/Update")
    suspend fun updateShareLink(@Body body: UpdateShareLinkRequest): ResponseBody

    @POST("api/Share/Revoke")
    suspend fun revokeShareLink(@Body body: RevokeShareLinkRequest): ResponseBody

    @GET("api/Share/ListGrants")
    suspend fun listShareGrants(@Query("entityId") entityId: String): List<ShareGrantDto>

    /** UX §7b / AT-S46: remove caller's direct personal grant (Can view OK). Idempotent. */
    @POST("api/Share/LeaveSharedWithMe")
    suspend fun leaveSharedWithMe(@Body body: LeaveSharedWithMeRequest): LeaveSharedWithMeResult

    /** Shared by me outbound inventory (AT-S51 / AT-S54). Signed-in user JWT only. */
    @GET("api/Share/ListSharedByMe")
    suspend fun listSharedByMe(
        @Query("skip") skip: Int = 0,
        @Query("take") take: Int? = 100
    ): List<SharedByMeRootDto>

    @POST("api/Entities/RemovePermission")
    suspend fun removePermission(@Body body: RemovePermissionRequest): ResponseBody
}

data class SendWithAttachmentsResponse(
    @SerializedName("messageId") val messageId: String?,
    @SerializedName("attachmentIds") val attachmentIds: List<String>? = null
)

data class CreateShareLinkRequest(
    val entityId: String,
    val label: String? = null,
    val expiresAt: String? = null,
    val neverExpires: Boolean = false,
    val includeSubtree: Boolean = false,
    val canWrite: Boolean = false
)

data class CreateShareLinkResult(
    val id: String? = null,
    val url: String? = null,
    val tokenPrefix: String? = null,
    val label: String? = null,
    val expiresAt: String? = null,
    val includeSubtree: Boolean = false,
    val canWrite: Boolean = false,
    val principalEntityId: String? = null,
    val createdByUserId: String? = null,
    val createdAt: String? = null,
    val useCount: Int = 0,
    val lastUsedAt: String? = null
)

data class ShareLinkListItemDto(
    val id: String? = null,
    val tokenPrefix: String? = null,
    val label: String? = null,
    val expiresAt: String? = null,
    val revokedAt: String? = null,
    val includeSubtree: Boolean = false,
    val canWrite: Boolean = false,
    val principalEntityId: String? = null,
    val createdByUserId: String? = null,
    val createdByDisplayName: String? = null,
    val createdAt: String? = null,
    val useCount: Int = 0,
    val lastUsedAt: String? = null,
    val isLive: Boolean = false
)

data class UpdateShareLinkRequest(
    val shareLinkId: String,
    val label: String? = null,
    val expiresAt: String? = null,
    val neverExpires: Boolean = false
)

data class RevokeShareLinkRequest(val shareLinkId: String)

data class ShareGrantDto(
    val permissionId: String? = null,
    val permissionForId: String? = null,
    val principalKind: String? = null,
    val displayName: String? = null,
    val canWrite: Boolean = false,
    val shareLinkId: String? = null,
    val tokenPrefix: String? = null,
    val label: String? = null
)

data class RemovePermissionRequest(
    val entityId: String,
    val permissionForId: String
)

/** Grantee Leave Shared with me (UX §7b / AT-S46). */
data class LeaveSharedWithMeRequest(val entityId: String)

data class LeaveSharedWithMeResult(
    val entityId: String? = null,
    /** True when a direct personal grant was removed; false when already absent (idempotent). */
    val left: Boolean = true
)

/** Shared by me root (AT-S51 / AT-S52). No raw tokens. */
data class SharedByMeRootDto(
    val entityId: String? = null,
    val entityName: String? = null,
    val peoplePreview: List<SharedByMePrincipalPreviewDto>? = null,
    val peopleMoreCount: Int = 0,
    val liveGuestLinkCount: Int = 0,
    val hasPublicAccess: Boolean = false
)

data class SharedByMePrincipalPreviewDto(
    val principalKind: String? = null,
    val permissionForId: String? = null,
    val displayName: String? = null
)
