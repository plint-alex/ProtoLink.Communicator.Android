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
import ru.protolink.communicator.data.TokenStore
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.data.db.AppDatabase
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
        Room.databaseBuilder(ctx, AppDatabase::class.java, "protolink.db").build()

    @Provides fun dao(db: AppDatabase): SyncMetaDao = db.syncMetaDao()

    @Provides @Singleton
    fun metadataStore(dao: SyncMetaDao): MetadataStore = RoomMetadataStore(dao)

    @Provides @Singleton
    fun okHttp(tokenStore: TokenStore, settingsStore: SettingsStore): OkHttpClient {
        val auth = Interceptor { chain ->
            val token = tokenStore.load()?.accessToken
            val req = if (token.isNullOrBlank()) chain.request()
            else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            val resp = chain.proceed(req)
            // Do not clear the session on 401 — expired JWTs and multi-device refresh races
            // are common; clearing here logs the user out of Android when Windows is active too.
            resp
        }
        return OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
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
