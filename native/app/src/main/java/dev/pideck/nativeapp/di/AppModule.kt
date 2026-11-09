package dev.pideck.nativeapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.OkHttpPideckApi
import dev.pideck.nativeapp.data.defaultOkHttpClient
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = defaultOkHttpClient()

    /** 真实实现放这里，接口绑定在 [AppBindings]（测试模块可以整体换掉那一条）。 */
    @Provides
    @Singleton
    fun okHttpPideckApi(client: OkHttpClient): OkHttpPideckApi = OkHttpPideckApi(client)

    @Provides
    @Singleton
    fun deviceRepository(@ApplicationContext context: Context): DeviceRepository = DeviceRepository(context)
}
