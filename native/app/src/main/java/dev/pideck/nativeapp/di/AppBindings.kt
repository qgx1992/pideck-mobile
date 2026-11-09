package dev.pideck.nativeapp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pideck.nativeapp.data.DeviceDirectory
import dev.pideck.nativeapp.data.DeviceRepository
import dev.pideck.nativeapp.data.OkHttpPideckApi
import dev.pideck.nativeapp.data.PideckApi
import javax.inject.Singleton

/**
 * 接口绑实现（0.5.41 起）。
 *
 * 之前 `AppModule` 直接 `fun pideckApi(client): PideckApi = OkHttpPideckApi(client)`，
 * 于是「谁是真实实现」被写死在装配里，单测想塞一个假的只能拿到 `OkHttpClient` 去搭 mock server。
 * 现在真实实现自己 `@Provides`、这里只做绑定：测试模块用 `@TestInstallIn` 换掉这个 Module，
 * 就能给 ViewModel 注入假 `PideckApi` / 假 `DeviceDirectory`。
 *
 * 同理 `DeviceDirectory` 是「设备清单 + 令牌」的最小面（`ConnectionResolver` 只吃它），
 * 它的实现要 `android.content.Context`，JVM 单测里造不出来 —— 所以绑接口，不绑具体类。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {

    @Binds
    @Singleton
    abstract fun pideckApi(impl: OkHttpPideckApi): PideckApi

    @Binds
    @Singleton
    abstract fun deviceDirectory(impl: DeviceRepository): DeviceDirectory
}
