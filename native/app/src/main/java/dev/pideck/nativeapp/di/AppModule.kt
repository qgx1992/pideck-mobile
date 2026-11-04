package dev.pideck.nativeapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.OkHttpPideckApi
import dev.pideck.nativeapp.data.PideckApi
import dev.pideck.nativeapp.data.defaultOkHttpClient
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = defaultOkHttpClient()

    @Provides
    @Singleton
    fun pideckApi(client: OkHttpClient): PideckApi = OkHttpPideckApi(client)

    @Provides
    @Singleton
    fun deviceRepository(@ApplicationContext context: Context): DeviceRepository = DeviceRepository(context)
}
