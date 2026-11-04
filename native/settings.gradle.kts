// 阿里云镜像只在本机用（国内快）。GitHub 的 runner 走国际网络，镜像对它反而会
// 返回 200 的 HTML 错误页 / 403，导致 plugin marker 解析失败（CI 上 KSP 就是这么挂的：
// "could not resolve plugin artifact 'com.google.devtools.ksp...'"）。所以 CI 只走官方源（判断直接内联，settings 里 pluginManagement 必须是第一个语句块）。
pluginManagement {
    repositories {
        if (System.getenv("CI") != "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "PiDeckNative"
include(":app")
