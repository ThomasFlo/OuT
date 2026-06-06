package com.homestock.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.homestock.data.local.EmplacementDao
import com.homestock.data.local.HomeStockDatabase
import com.homestock.data.local.ObjetDao
import com.homestock.data.local.ZoneDao
import com.homestock.data.remote.ApiService
import com.homestock.data.remote.HostSelectionInterceptor
import com.homestock.data.remote.RealtimeClient
import com.homestock.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideHostInterceptor(): HostSelectionInterceptor = HostSelectionInterceptor()

    @Provides
    @Singleton
    fun provideOkHttp(host: HostSelectionInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(host)
            .addInterceptor(logging)
            // OkHttp defaults to a 10 s read timeout, which kills slow
            // endpoints from underneath us. Wine enrichment via Ollama on
            // a cold model can take 60-120 s (prompt eval + generation),
            // and the server itself only gives up at 180 s, so the client
            // must wait longer. 200 s leaves a small buffer over server.
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(200, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(210, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            // Placeholder base URL; the host interceptor rewrites every request.
            .baseUrl("http://localhost:8080/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)

    @Provides
    @Singleton
    fun provideRealtime(scope: CoroutineScope, gson: Gson): RealtimeClient =
        RealtimeClient(scope, gson)

    @Provides
    @Singleton
    fun provideSettings(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HomeStockDatabase =
        Room.databaseBuilder(context, HomeStockDatabase::class.java, "homestock.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideZoneDao(db: HomeStockDatabase): ZoneDao = db.zoneDao()

    @Provides
    fun provideEmplacementDao(db: HomeStockDatabase): EmplacementDao = db.emplacementDao()

    @Provides
    fun provideObjetDao(db: HomeStockDatabase): ObjetDao = db.objetDao()
}
