package ru.protolink.communicator.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.protolink.communicator.BuildConfig
import ru.protolink.communicator.data.SettingsStore
import ru.protolink.communicator.data.TokenAuthenticator
import ru.protolink.communicator.data.TokenStore
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.data.db.AppDatabase
import ru.protolink.communicator.data.db.MIGRATION_1_2
import ru.protolink.communicator.data.db.RoomMetadataStore
import ru.protolink.communicator.data.db.SyncMetaDao
import ru.protolink.communicator.sync.ports.MetadataStore
import ru.protolink.communicator.syncadapt.ApiRemoteCloud
import ru.protolink.communicator.syncadapt.SafLocalFileSystem
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "protolink.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun dao(db: AppDatabase): SyncMetaDao = db.syncMetaDao()

    @Provides @Singleton
    fun metadataStore(dao: SyncMetaDao): MetadataStore = RoomMetadataStore(dao)

    @Provides @Singleton
    fun okHttp(
        tokenStore: TokenStore,
        authenticator: TokenAuthenticator
    ): OkHttpClient {
        val auth = Interceptor { chain ->
            val token = tokenStore.load()?.accessToken
            val req = if (token.isNullOrBlank()) chain.request()
            else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            chain.proceed(req)
        }
        val messengerLog = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val watch = path.contains("AddEntity", true) ||
                path.contains("AddPermission", true) ||
                path.contains("commands", true) ||
                (path.contains("GetEntities", true) && req.method.equals("POST", true))
            if (watch) {
                val copy = req.newBuilder().build()
                val buffer = okio.Buffer()
                copy.body?.writeTo(buffer)
                val reqBody = buffer.readUtf8().take(2000)
                android.util.Log.i("ProtoLinkHttp", "--> ${req.method} $path body=$reqBody")
            }
            val resp = chain.proceed(req)
            if (watch) {
                val peek = resp.peekBody(2000)
                android.util.Log.i("ProtoLinkHttp", "<-- ${resp.code} $path body=${peek.string()}")
            }
            resp
        }
        return OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(messengerLog)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .authenticator(authenticator)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton
    fun api(client: OkHttpClient, settingsStore: SettingsStore): ProtoLinkApi {
        val base = settingsStore.load().apiBaseAddress.ifBlank { BuildConfig.API_BASE_URL }
            .let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ProtoLinkApi::class.java)
    }

    @Provides @Singleton
    fun remoteCloud(api: ProtoLinkApi) = ApiRemoteCloud(api)

    @Provides @Singleton
    fun localFs(@ApplicationContext ctx: Context) = SafLocalFileSystem(ctx)
}
