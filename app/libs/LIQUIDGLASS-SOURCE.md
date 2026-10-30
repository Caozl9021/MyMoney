# liquidglass-1.1.0.aar — 来源与本地修改说明

## 上游

| 项 | 值 |
| --- | --- |
| 项目 | AndroidLiquidGlassView |
| 作者 | Donny Yang（QmDeve） |
| 源码 | https://github.com/QmDeve/AndroidLiquidGlassView |
| 文档 | https://liquidglass.qmdeve.com/ |
| 版本 | 1.1.0 |
| Maven 坐标 | `com.qmdeve.liquidglass:core:1.1.0`（Maven Central） |
| 许可证 | MIT，全文见同目录 `LIQUIDGLASS-LICENSE.txt` |

## 为什么仓库里放 AAR 而不是直接依赖 Maven

上游 1.1.0 的 `aar-metadata.properties` 声明 `minCompileSdk=37`，
而本项目当前 `compileSdk=35`（AGP 8.7 的上限）。直接走 Maven 依赖时，
AGP 会在依赖校验阶段直接拒绝构建。

库本身 `minSdk 24`，实际只用到 API 33 的 `RuntimeShader` / `RenderNode`，
并不依赖 API 34–37 的任何接口。因此把元数据里的 `minCompileSdk`
从 37 降到 35 是安全的。

## 本地修改

相对上游 1.1.0 的发布产物，本项目只改了一个文件的一行：

```
META-INF/com/android/build/gradle/aar-metadata.properties
- minCompileSdk=37
+ minCompileSdk=35
```

`classes.jar`、`AndroidManifest.xml`、`R.txt`、`res/raw/liquidglass_effect.agsl`
与上游一致，未作任何改动。

## 什么时候可以删掉这个 AAR

满足以下任一条件后，可以改为 Maven 依赖并删除本地 AAR：

- 项目升级到 `compileSdk >= 37`（需要同时升级 AGP），或
- 上游发布 `minCompileSdk <= 35` 的新版本。

改法：删掉 `app/build.gradle.kts` 里的 `implementation(files("libs/liquidglass-1.1.0.aar"))`，
换成 `implementation("com.qmdeve.liquidglass:core:<版本>")`。
