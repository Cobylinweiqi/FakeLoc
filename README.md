# FakeLoc

一个基于 **LSPosed / libxposed API 101** 的虚拟定位模块。不需要开启开发者选项里的「模拟位置」，
也不需要 root 权限去改系统设置——直接 hook 掉 `android.location` 的读取路径，把坐标换成你指定的值。
选点用一个真正的**第三方地图**页面（腾讯地图）：拖图钉、搜地址、点图反查。

参考了 [noobexon1/XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation)（定位注入 + 现代
libxposed 架构）与 [auag0/HideMockLocation](https://github.com/auag0/HideMockLocation)（反检测）两个开源
项目的思路，重新实现并合并成一个应用。

> ⚠️ **仅供合法用途，风险自负。** 这是装在**你自己设备**上做定位测试的工具，不是打卡作弊器：
> 拿它欺骗应用、绕过风控或骗取利益，后果由使用者承担 —— 完整条款见文末[免责声明](#免责声明)。
> 前提是设备已 root 并装有 LSPosed；它 hook 的是真实的定位读取路径，触发目标 App 的反作弊检测时
> 轻则功能异常、重则**账号被封**，在银行、支付、证券类 App 上尤其不建议使用。

---

## 下载与安装

**走 LSPosed 在线仓库（推荐 —— 装完能自动更新）**

已按上游流程提交收录申请：在 `Xposed-Modules-Repo/submission` 开一条标题为
`[submission] io.github.cobylinweiqi.fakeloc` 的 issue。bot 受理后会在该组织下建一个**同名仓库**
（`Xposed-Modules-Repo/io.github.cobylinweiqi.fakeloc`）、把作者加成 maintainer，并自动同步它的
Release。通过之后：LSPosed 管理器 → 「在线仓库」→ 搜索 **FakeLoc**，或直接开模块页
<https://modules.lsposed.org/module/io.github.cobylinweiqi.fakeloc>。
**审核期间请走下面这条路。**

**从 Release 拿 APK**

到 [最新 Release](https://github.com/Cobylinweiqi/FakeLoc/releases/latest) 下载 `FakeLoc-<版本>-release.apk`。

装完还**必须**在 LSPosed 里启用本模块并勾选作用域，否则一点效果都没有 —— 完整步骤在第六节。

> ⚠️ **自己编译出来的 APK 与 Release 的签名不同，两者不能互相覆盖安装。**
> Release 用的是本项目的专用密钥（`CN=FakeLoc`，SHA-1
> `28a3a1d6f8a0d9b00af1a33a70586cc0c20a04b0`），而 `./gradlew assembleRelease` 在**没有**
> `keystore.properties` 时回落到 Android 默认 debug 签名（`CN=Android Debug`，SHA-1
> `cce419e399302d402f2378a57b3c7985e98b66e2`）—— 所以直接克隆编译得到的包装得上、能自用，
> 但**不能覆盖官方 Release**。签名必须一致才能升级，换签名等于换一个 App，旧版要先卸载。
> App 设置页显示的包名与 SHA-1 是当前构建的真实值，申请地图 Key 时以那里为准。
>
> 想用自己的密钥发版：在仓库根目录建 `keystore.properties`（已在 `.gitignore` 里）填
> `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项，构建就会自动改用它。
> **这个文件和 `.jks` 密钥绝不能进仓库** —— 拿到密钥的人可以签出 App 会接受的更新。

---

## 一、它和参考项目有什么不同

| | XposedFakeLocation | HideMockLocation | FakeLoc |
|---|---|---|---|
| 定位注入 | ✅ | — | ✅ |
| 反检测 | 部分 | ✅ 完整 | ✅ 合并进来 |
| 地图选点 | ✅ osmDroid | — | ✅ **腾讯地图 SDK**（拖动选点 + 地址搜索 + 逆地理编码） |
| 坐标输入 | — | — | ✅ 手输框 / 城市预设 / 剪贴板解析 都保留 |
| 平台 | 仅 Android 11+ | Android 6–16 | Android 10+（minSdk 29） |
| 配置通道 | LSPosed 远程偏好 | 无配置 | LSPosed 远程偏好 + 本地镜像兜底 |
| 系统级 hook | 开关 + 复杂的按注册项拆分 | — | 开关 + **原地覆写**（见下） |
| 轨迹模拟 | 静态随机偏移 | — | 步行漂移（随机游走 + 向心回归） |
| 地图 SDK 通道 | — | — | ✅ 高德 / 百度 / 腾讯三通道 + 动态 ClassLoader 探测 + 主动派发 |

> **1.5.0 起只内置腾讯一家底图。** 上一行的「三通道」指的是**注入通道**（hook 目标 App 里的 SDK），
> 那三个一直在、也都还在；去掉的是**渲染器** —— 百度与高德的底图库。两者不是一回事，别被行文的相似骗了：
> 注入通道全程靠反射操作目标 App 的类，不链接我们自己打包的任何 SDK 类，所以删掉依赖对它没有任何影响。
> 这也正是这次能在不损失任何虚拟定位能力的前提下把包从 36 MB 压到 5 MB 的原因。详见第五节。

三处我自己做的取舍，都是有意为之：

**1. 系统级 hook 不再拆分注册项。** XposedFakeLocation 在
`LocationProviderManager.onReportLocation` 里反射拿 `mRegistrations`，按调用包名把注册项拆成
「发假位置」和「透传真位置」两组，再重新装回去。逻辑很精彩，但任何一步对不上就会让
`system_server` 的注册表处于半改写状态——那是重启级别的崩溃。这里改成直接原地覆写 payload：
代价是开启系统级作用域时**所有** App 都会收到假位置，收益是这套 hook 不可能破坏框架状态。
开了系统级就等于「全系统伪造」，这个语义本来也是自洽的。

**2. 监听器回调不需要包装 Proxy。** 常见做法是 hook `requestLocationUpdates` 的七个重载，把用户传进来的
`LocationListener` 用动态代理包一层。问题在于代理是**另一个对象**，而框架的 `removeUpdates(listener)`
是按相等性匹配的——代理会把注销打断，留下一条真实的定位数据流。这里改成 hook 框架内部唯一的下发点
`LocationManager$LocationListenerTransport#onLocationChanged`，一个 hook 覆盖所有重载，且不改变对象标识。

> 例外是 `getCurrentLocation(provider, signal, executor, consumer)`：它是**一次性**回调，
> 不存在注销匹配问题，所以这里确实用了 Proxy 包装 Consumer。

**3. 精度/海拔/速度等字段各自独立开关**，而不是一个总开关。只改经纬度、保留真实的
`accuracy`/`altitude`，在很多场景下比全套伪造更自然。

---

## 二、地图选点与坐标系（这一节请务必读）

界面上的「地图选点」会打开一个内置地图页，底图由**腾讯地图 SDK**绘制（见第四节）：

- **拖动地图**，屏幕正中的图钉就是选中的点，停下后自动反查地址
- **搜索框**输入地址/地标，走**系统 `Geocoder`**（与底图供应商无关，也不需要 Key），点结果即飞过去
- **「使用此位置」**把当前图钉写回坐标框

### 坐标系是这个功能里唯一容易出错的地方

三个坐标系在中国并存：

| | 谁在用 |
|---|---|
| **WGS-84** | `android.location.Location` 里装的就是它，也是 GNSS 芯片的原始输出 |
| **GCJ-02** | 国境内公共地图强制使用的偏移坐标系。**腾讯地图 SDK 返回的是它** |
| **BD-09** | 百度在 GCJ-02 上又叠了一层偏移。**百度地图 SDK 返回的一切都是它** |

所以地图上拖出来的点是 **GCJ-02**，而模块最终要写进 `Location` 的是 **WGS-84**。
直接拿 GCJ-02 去填，伪造出来的位置会**偏离你点的位置几百米**——这是个肉眼可见的错误，
而且是那种「用起来感觉怪怪的、但说不上哪错了」的错误。

BD-09 这一层现在**只剩注入侧在用**：目标 App 若走百度 SDK，我们派发进去的 `BDLocation`
必须按 BD-09 填（见第八节）。渲染侧与注入侧各换各的，别把两件事当成一件。

`core/Coords.kt` 两向都留着：GCJ-02 ↔ WGS-84 与 BD-09 ↔ WGS-84。
GCJ-02 → WGS-84 这一向没有解析解，代码用三次不动点迭代反解，精度优于厘米级；
网上常见的「直接减去偏移量」写法会残留几米误差，而且往返回去会放大。

**手输框里的坐标不做任何换算**——它就是 WGS-84，和地图页底部显示的数字逐位一致。
地图页底部也专门写了一行小字提醒这件事，免得你拿另一个工具对比时对不上。

---

## 三、工作方式

```
┌────────────────────────┐         ┌──────────────────────────────────────┐
│  FakeLoc 管理界面       │         │  目标 App 进程（被 LSPosed 注入）      │
│  Compose + Material3   │         │                                      │
│                        │         │  ModuleEntry                         │
│  MainViewModel         │         │    └─ HookState（配置快照 + 漂移引擎） │
│    └─ RemoteStore      │         │         ├─ LocationHooks              │
│         ↓ 写入          │         │         ├─ ListenerHooks              │
│    XposedService       │         │         ├─ PlayServicesHooks          │
│    .getRemotePreferences│        │         └─ AntiMockHooks               │
│         ↓              │         │                                      │
│   LSPosed 远程偏好      │────────▶│  同一个 SharedPreferences            │
│   key = "config"       │  推送   │  监听变化 → 重新解码 → 立即生效         │
└────────────────────────┘         └──────────────────────────────────────┘
        │                                     ▲
        │ 地图 SDK（仅管理界面内）                 │ system 在作用域内时
        ▼                            ┌────────┴─────────┐
  底图瓦片 / POI / 逆地理编码           │ system_server     │
  （各家自有坐标系）                   │ FrameworkLocation │
                                      │ Hooks + AppOps    │
                                      └───────────────────┘
```

**配置通道**用的是 libxposed API 101 的 `getRemotePreferences(group)`——这是 `XSharedPreferences` +
`MODE_WORLD_READABLE` 那套做法的官方替代品，跨进程、跨用户可用，且不需要 root。整个配置序列化成
**一个 key 里的一份 JSON**：hook 侧只要一个监听器和一次解码，以后加字段也不用改两处代码。

写入时同时落一份**本地镜像**。原因很实际：绑定服务不一定在（LSPosed 还没起来、模块刚装、管理器在升级），
没有镜像的话用户打开 App 只会看到一堆默认值，连配置都没法预先准备好。

**生效时机**：hook 侧在每个拦截点判断 `HookState.active()`，而不是安装时判断。所以在管理界面里改坐标
或拨开关，**正在运行的目标 App 立刻生效**，不用重启（首次加入作用域仍需重启一次让模块加载进去）。

**地图 SDK 只活在管理界面进程里**，和 hook 侧没有任何关系。SDK 初始化失败（Key 无效、缺 `.so`、
没网）只会让选点页显示一条错误，不会影响模块本身——`MapSdkBootstrap.ensure` 把每次启动都包在
`runCatching` 里，视图构造另有 `buildMapView` 兜底（见第十三节 1.4.0）。

---

## 四、地图 Key（腾讯，要你自己申请）

选点页的底图由**腾讯地图 SDK**绘制。1.5.0 起只内置这一家 —— 百度与高德被移除，理由见第五节。
它的 Key 绑定「包名 + 签名证书」，所以换了签名就要重配。这个 Key **只影响选点页**，
模块本体（伪造定位）不需要任何 Key。

**本仓库与本安装包都不带任何 Key**，一律由你在 App 里填。这不是尚未做完的事，而是刻意的
设计：一个 Key 只对注册时填的那个包名与签名有效，烘进包里对别人毫无用处，而它一旦被平台停用，
代码里没有任何东西能把它救回来。更要紧的是，包裹里带一个 Key 会掩盖掉唯一有价值的信号 ——
**一个已经失效的 Key 和一个正常的 Key，在发出请求之前长得一模一样**。

### 4.1 在 App 里填（唯一入口）

打开 App → 右上角**设置图标** → 在「底图与 Key」一栏里粘贴 Key。

设置页同时给出控制台会索要的两个值（**包名**与**签名 SHA-1**），各带一个复制按钮，
申请步骤也在同一页。**这两个值必须逐字符一致**——差一个字符的 Key 与没注册过的 Key，
报错完全一样。

> **改 Key 后必须重启本应用。** 这个 SDK 只在启动时读一次 Key，进程内再改无效。

### 4.2 腾讯位置服务

1. 打开 https://lbs.qq.com → 控制台 → 应用管理 → 我的应用
2. 创建应用，再在其下「添加 Key」，勾选 **Android SDK**
3. 填「PackageName」与「SHA1」——**用 App 设置页显示的那两个值**，不要手抄：

   | 字段 | 值 |
   |---|---|
   | 包名 | `io.github.cobylinweiqi.fakeloc` |
   | 签名 SHA-1 | 见 App 设置页「签名 SHA-1」，带复制按钮 |

   > **Key 绑定的是包名 + 签名 SHA-1 这两项，而签名取决于你装的是哪个包。**
   > 官方 Release 用的是项目专用密钥（SHA-1 `28a3a1d6f8a0d9b00af1a33a70586cc0c20a04b0`），
   > 自己编译默认用 debug 签名（`cce419e399302d402f2378a57b3c7985e98b66e2`）—— 两者**不是**
   > 同一个 SHA-1，在腾讯控制台登记时要按你实际要装的那个包填。只有一个 Key 的话，
   > 建议登记你打算长期使用的那一个；不确定就用 App 设置页显示的值（那是当前运行中的真实值）。

4. 把 Key 粘贴到 App 设置页的「腾讯地图 Key」一栏
5. 重启 App 生效

腾讯的 Key 在**运行时**按视图传入（`TencentMapOptions.setMapKey`），
manifest 里没有任何 Key 条目 —— 自 1.4.1 起百度那条 `com.baidu.lbsapi.API_KEY` 也删掉了。

**一个 Key 都没填也能编译、也能装**：选点页会显示一张明确的「未配置 Key」卡
（而不是一片灰），其余功能照常可用。

> 地址查询（搜索与逆地理）走**系统 `Geocoder`**，与地图 Key 无关，也不需要额外申请 ——
> 这是 1.3.1 就定下的做法：百度 AK 的「搜索/逆地理」服务常被默认关着，而地图瓦片照常渲染，
> 于是「坐标跟着走、城市名不跟」成了最难查的一类故障。系统 Geocoder 没有这个坑。

---

## 五、编译

### 前置

- JDK 17
- Android SDK：`platforms;android-36`、`build-tools;35.0.0`、`platform-tools`
  （`build-tools 34` 也行，AGP 8.7.3 默认就要它——但**必须预装**，见第九节）
- 已 root 的设备 + 支持 **API 101** 的 LSPosed（官方 Telegram 频道 `t.me/LSPosed` 的最新版）

### 步骤

```bash
# 1. 指向你的 SDK —— local.properties 只需要这一行，没有 Key 要配
echo "sdk.dir=$HOME/android-sdk" > local.properties

# 2. 编译（wrapper 已随仓库提供，指向腾讯云镜像）
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
# 或 ./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

`gradle/wrapper/gradle-wrapper.jar` 与 `gradlew` 都已生成，clone 下来直接就能跑，
不需要先手动 `gradle wrapper`。

签名：仓库根目录有 `keystore.properties` 时用它指向的正式密钥，没有则回落到 debug 签名 ——
所以 clone 下来 `assembleRelease` 开箱可用，**但那个包与官方 Release 签名不同、不能互相覆盖**
（详见开头那条警告）。要发自己的版本就在根目录建那个文件，四个字段：
`storeFile` / `storePassword` / `keyAlias` / `keyPassword`。

**R8 打开（1.5.0 起），但有一条不能松的规则。** R8 之前关着，理由是地图 SDK 按名字解析自己的类，
混淆或裁掉任何一个，表现都是「底图一片空白」或「AK 无效」，**和 Key 填错了长得一模一样**，
而且只在设备上才暴露。这个理由本身没错，1.5.0 改变的是**它的代价第一次被量了出来**：
dex 里 28 726 个类有 21 224 个是 androidx/Compose，因为没人裁剪它们而全量保留，
我们自己的代码只有 391 个类。打开 R8 后类数降到 4 900。

**规则：凡靠名字解析的，就必须靠名字保留。**

- 供应商 SDK：`-keep class com.tencent.** { *; }` + `-keep class com.qq.** { *; }`
  （两个包根是**从产物的 dex 里读出来的**，不是抄的：mapsdk / tencentmap / map / tmsbeacon /
  lbssearch / tmsqmsp + 一个 `com.qq.taf`）。同理还有 `-keepclasseswithmembernames` 保 native 方法名。
- 模块自己：`META-INF/xposed/java_init.list` 里写的 `io.github.cobylinweiqi.fakeloc.xposed.ModuleEntry` 由框架反射实例化，
  丢了不会有任何报错，只是这个 App 悄悄不再是模块。整个 `io.github.cobylinweiqi.fakeloc.xposed.**` 都按原名保留。
- 手工序列化的模型：`core/SpoofConfig` 保留（它被手写成 JSON）。

产物断言里专门核这几条，见第十三节 1.5.0。

### 版本组合（这一组是实测跑通的）

| 组件 | 版本 | 说明 |
|---|---|---|
| AGP / Gradle | 8.7.3 / 8.9 | |
| Kotlin | **2.2.10** | 不能低于此版本，原因见第九节 |
| Compose | BOM 2024.10.01 | |
| compileSdk / targetSdk / minSdk | **36** / 35 / 29 | compileSdk 被 libxposed 顶着，见第九节 |
| buildToolsVersion | 35.0.0 | 显式钉住 |
| `io.github.libxposed:api` | 101.0.0 | `compileOnly`，运行时由框架提供 |
| `io.github.libxposed:service` | 101.0.0 | 打进 APK，供管理界面对接 LSPosed |
| `com.tencent.map:tencent-map-vector-sdk` | **5.9.0** | **不能用 6.x**，原因见下；1.5.0 起是唯一的地图依赖 |

> 腾讯必须钉 **5.9.0**。6.x（含最新的 6.13.0）的构件里 `mapsdk.maps.model.LatLng`
> **整个缺失**——`CameraUpdateFactory`、`CameraPosition` 都引用它，而它既不在构件里、
> 也不在任何传递依赖里（该 POM 声明 0 个依赖）。结果不是运行时出错，是链接期就过不去。
> 5.9.0 完整。顺带一提，5.9.0 与 6.x 的初始化 API 也不同：5.9.0 只有
> `TencentMapInitializer.setAgreePrivacy(boolean)`，没有 `Context` 重载，也没有 `start`。

### 产物大小

**v1.5.0 实测：release 4.97 MB。** 三版一路看下来：v1.3.2 是 82.7 MB → v1.4.0 是 36.2 MB
→ 现在 4.97 MB。相对 v1.4.0 又砍掉 86%。

#### 本轮（1.5.0）—— 两刀

| 项 | v1.4.1 | v1.5.0 | 怎么来的 |
|---|---|---|---|
| `lib/` 原生库 | 17.45 MB | **1.93 MB** | 百度（7.40）+ 高德（7.67）的 `.so` 全部移除 |
| `assets/` | 7.28 MB | **0.64 MB** | 高德的 `cfg`（3.73）+ `map_assets`（2.04）等一并移除 |
| `dex` | 12.62 MB | **1.83 MB** | 打开 R8：类数 28 726 → 4 900 |
| 资源（`res/` + `arsc`） | 0.51 MB | 0.50 MB | 未动 |
| **合计** | **37.9 MB** | **4.97 MB** | |

**第一刀：删掉两个底图渲染器。** 百度与高德只服务于「地图选点」这一个界面。
这一点值得说清，因为它不是显然的：`xposed/hooks/MapSdkHooks.kt` 里那 700 多行 hook 确实
要跟三家 SDK 打交道，但它**全程通过反射操作目标 App 里的类**，不链接我们自己打包的任何一个。
所以删依赖对虚拟定位能力零影响 —— 上面那条三通道全都在（真机日志里三条 `channel armed` 一次不少）。
代价只有选点页少两家可选底图，换来的是 24 MB。

**第二刀：打开 R8。** 之前关着，是因为三家 SDK 都按名字解析自己的类，混淆或裁剪的后果是
「底图空白 / AK 无效」，与 Key 填错完全无法区分。现在是这个取舍第二次被重新算账：

- 需要「整包保留」的供应商从三家变成一家，`proguard-rules.pro` 里一条
  `-keep class com.tencent.** { *; }` 就够（`com.qq.**` 另列，是另一个包根）；
- 而关掉 R8 的代价第一次被量了出来：**28 726 个类里有 21 224 个是 androidx/Compose**，
  因为没有任何东西裁剪它们而全量保留，我们自己的代码只有 391 个类。
  打开 R8 后总类数 4 900，其中腾讯 SDK 2 167 个（原样保留），
  androidx/Compose 从 21 224 掉到约 250。

**必须同时守住的规则：凡是靠名字解析的东西，就必须靠名字保留。** 这包括
`META-INF/xposed/java_init.list` 里写的入口类 —— 它丢了，这个 App 就不再是模块，
而且不会有任何报错。产物断言里专门核了它（见第十三节 1.5.0）。

> `isShrinkResources` 仍然**关闭**：本项目自己的 `res/` 只有 31 KB、`resources.arsc` 479 KB，
> 没有可省的东西，而第三方 SDK 是有可能按名字查自己的资源的。收益为零、风险非零，不做。

#### 上一轮（1.4.0）—— 只动打包方式

| 项 | 优化前 | 优化后 | 怎么来的 |
|---|---|---|---|
| dex（3 个） | 44.1 MB | **12.0 MB** | `packaging.dex.useLegacyPackaging = true` |
| `lib/armeabi-v7a/` | 14.4 MB | **0** | `abiFilters` 只留 `arm64-v8a` |
| `lib/arm64-v8a/` | 16.6 MB | 16.6 MB | 三家地图引擎，当时砍不掉 |
| `assets/` | 7.0 MB | 7.0 MB | 三家地图的样式与图标资源，当时砍不掉 |
| **合计** | **82.7 MB** | **36.2 MB** | |

两条改动都只动**打包方式**，不碰一行业务代码，也不改变运行时行为：

1. **`packaging.dex.useLegacyPackaging = true`** —— AGP 8 的默认值是 `false`，会把每个
   `classes*.dex` **不压缩**存进 APK，好让 ART 直接 mmap。这个取舍对大厂 App（天天冷启）成立；
   放在这里，就是 44 MB 的 dex 原样躺在下载包里，代价只剩安装时多解压一次。
   > 属性位置要写对：AGP 8.7.3 里它叫 `packaging.dex.useLegacyPackaging`。在
   > `packaging { }` 块里直接写 `dexUseLegacyPackaging = …` 会**脚本编译失败**
   > （`Unresolved reference`）——那是更老的 `packagingOptions` 的形状。

2. **`abiFilters` 只留 `arm64-v8a`** —— 每家 SDK 都同时发 32/64 位 ARM `.so`，
   32 位那一套当时是 14.4 MB。而 LSPosed 需要 root，今天还在服役的 32 位机型基本不存在。
   要给老设备留门，把 `armeabi-v7a` 加回 `app/build.gradle.kts` 的 `abiFilters` 即可，只此一行。

#### 现在的下限在哪

4.97 MB 里，腾讯地图自己占掉约 2.6 MB（`libtxmapengine.so` 1.56 + `libtxmapvis.so` 0.36
+ `assets/tencentmap` 0.64），其余是 dex 1.83 MB 与资源约 0.5 MB。
**再想往下走，就只剩「连腾讯渲染器也去掉」这一条路** —— 那时选点页得换成 WebView 里的 H5 地图
（要用户另填一个 Web 端 Key），预估能到 2.6 MB 上下。本次没有做，因为那会把一张能拖能缩的原生地图
换成一个网页，代价落在体验上而不是代码上。

---

## 六、安装与激活

1. 安装 APK —— 从 [Release](https://github.com/Cobylinweiqi/FakeLoc/releases/latest) 下载，或在 LSPosed
   在线仓库里直接装（见开头「下载与安装」）。
2. 在 LSPosed 里启用 **FakeLoc**。
3. **配置作用域**（这一步决定了它到底管不管用）：
   - 默认 `scope.list` 已经给了 `android`（系统框架）和 `com.android.providers.settings`（设置存储），
     这两个负责反检测的全局生效，建议保留。
   - 再把你想要伪造定位的 App 逐个勾上。
4. 重启设备（或至少重启目标 App）。
5. 打开 FakeLoc，界面顶部不再显示「未连接到 LSPosed 服务」即表示握手成功。

> **HyperOS / MIUI 上 `adb install -r` 会被拦下。** 报
> `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` —— 开发者选项里的「USB 安装」开关关着，
> 与签名、APK 本身无关。两条路：去「开发者选项 → USB 安装」打开；或者设备已 root 时绕过 adb：
> ```bash
> adb push FakeLoc-<版本>-release.apk /data/local/tmp/f.apk
> adb shell "su -c 'pm install -r -d /data/local/tmp/f.apk'"     # root 的 pm 不受该限制约束
> adb shell "su -c 'rm -f /data/local/tmp/f.apk'"
> ```
> 核验安装结果**只认 `dumpsys package` 的 `firstInstallTime`**：它被保留 = 原地升级，
> 配置还在；它被重置 = 全新安装，App 里配的东西全丢（LSPosed 作用域按包名存，不受影响）。
> **别拿 APK 文件的 mtime 当安装时间** —— 这个坑踩过。

### 关于 Google Play Services 的融合定位（fused）

这是最容易踩的坑：**只勾目标 App 时，GMS 的 `FusedLocationProviderClient` 拿到的仍然是真坐标。**

原因在于 fused 位置是 GMS 在**自己的进程**里算出来的——它读原始 provider 数据，然后融合。你在 App 进程里
hook `android.location.Location` 拦不到这个融合结果。

两个解法，任选其一：

- **把 `com.google.android.gms` 加进作用域。** GMS 读到的原始数据就是假的，融合出来自然是假的。
  轻量，推荐先试这个。
- **打开「系统级 hook」并把 `system` 加进作用域。** 从 `system_server` 层拦截，
  覆盖面最广，代价是作用域内所有 App 都会被改写。

`App` 进程侧的 `LocationResult.getLocations()` / `getLastLocation()` 已经 hook 了，能满足一部分
场景，但它覆盖不了所有 GMS 版本。

---

## 七、使用

| 操作 | 说明 |
|---|---|
| **在地图上选点** | 打开选点页（底图为腾讯地图）：拖图钉 / 搜地址 / 点结果飞行。选完统一换算成 WGS-84 |
| 坐标输入 | 纬度/经度，六位小数；校验通过才能保存。**不做坐标系换算**，输入什么就是什么 |
| 常用坐标 | 八个内置城市，点击即设为锚点 |
| 使用真实定位 | 以当前位置为起点（需要定位权限；受 5 秒节流保护） |
| 粘贴解析 | 从剪贴板提取坐标。支持 `39.9087, 116.3975`、Google 地图 `@`/`?q=`/`!3d!4d` 链接、`geo:` URI、`?lat=&lng=` 参数 |
| 生效范围 | 「作用域内全部应用」开关；关掉后只对下方勾选的 App 生效 |
| 信号微调 | 精度、海拔、速度、步行漂移、隐藏模拟痕迹、锁定定位源。**v1.6.0 起移到设置页**，见下 |
| 启动 / 停止 | 底部主按钮，也可在运行中改参数 |

**设置页（首页右上角齿轮）分两个页签。**

| 页签 | 内容 |
|---|---|
| `地图配置` | 底图 Key、控制台会索要的包名与 SHA-1（各带复制按钮）、Key 申请步骤、隐私说明 |
| `信号微调` | 上面表里那一整组开关（8 个开关，展开后另有 4 条滑杆） |

两半的改动节奏完全不同 —— Key 填一次就不动了，开关是调试时反复拨的。堆在一个滚动里，开关会落在
三屏「怎么申请 Key」的说明下面，这也正是它们当初被放到首页的原因。拆页签后两边都在设置页，谁也不挡谁。
**首页仍在状态卡上用三个 chip（精度 / 海拔 / 漂移）显示当前值** —— 只撤走了控件，摘要留着。
每页各有一份滚动位置，来回切页签不会跳回顶部。

**步行漂移**：在锚点周围做带向心回归的随机游走。每秒最多移动一次（同一秒内的所有读取看到同一个位置，
避免抖动），速度超过上限时按比例拉回圆内并加一点随机性，免得死钉在圆周上。

**锁定定位源**（默认开）：切断目标 App 的 WiFi 扫描、基站信息与原始 GNSS。带着自研引擎的 App
（高德这类）开着它能被挡住"自己算一个真实位置"这条路——这是「地图先跳到模拟点、几秒后又跳回真实
位置」的正解。代价是那个 App 失去基于 WiFi/基站的室内定位能力；如果它因此报"定位失败"，把这个
开关关掉即可。

**关于「生效范围」的一个提醒**：这个名单只决定「谁收到假位置」。模块本身还得在 LSPosed 作用域里，
两者是**与**的关系，不是或。界面上专门写了这句提示。

---

## 八、hook 清单

### 目标 App 进程

| 类 | 方法 | 作用 |
|---|---|---|
| `android.location.Location` | `getLatitude` / `getLongitude` | 核心坐标，始终覆写 |
| | `getAccuracy` / `getAltitude` / `getSpeed` | 按各自开关 |
| | `getMslAltitudeMeters` | Android 14+，按海拔开关 |
| | `getProvider` | 非标准 provider 名归一化为 `gps` |
| | `isFromMockProvider` / `isMock` | 返回 `false` |
| | `setIsFromMockProvider` / `setMock` | 参数强制 `false` |
| | `getExtras` / `setExtras` | 剔除 `mockLocation` 等标记 |
| | `set(Location)` | 清 `mFieldsMask` 的 mock 位 |
| `android.location.LocationManager` | `getLastKnownLocation` | 整个对象替换 |
| | `getCurrentLocation` | 包装 Consumer（一次性回调，安全） |
| | `getProviders` / `getAllProviders` / `getBestProvider` | 滤掉非标准 provider 名 |
| | `getProvider` / `hasProvider` / `isProviderEnabled` | 对非标准名答 `null` / `false` |
| `LocationManager$LocationListenerTransport` | `onLocationChanged` | 订阅式回调，原地覆写（含 AOSP 14 的 `List<Location>` 批量形态） |
| `android.provider.Settings$Secure` | `getStringForUser` | `mock_location` → `"0"` |
| `android.app.AppOpsManager` | `checkOp` / `unsafeCheckOp` 系列（7 个，含 `*Raw*`） | `OP_MOCK_LOCATION` → `MODE_ERRORED` |
| `com.google.android.gms.location.LocationResult` | `getLocations` / `getLastLocation` | GMS SDK 回调 |

上表里所有"原地覆写/整个对象替换"都由 `LocationPayload.overwriteAll()` 统一实现，它认得四种
payload 形态：裸 `Location`、`List<Location>`（AOSP 14 的 `ILocationListener.onLocationChanged`
就是这种）、暴露 `getLocations()`/`asList()` 的容器、以及只有私有 `mLocations` 字段的隐藏
`android.location.LocationResult`。**只认前两种是这里最容易犯的错**——回调确实触发了，App 也确实
拿到了真坐标，但日志里什么都不会打，看起来就像模块没生效。

`overwriteAll()` 是**写真实字段**而不是替换对象，这一点是刻意的：地图 SDK 的"我的位置"蓝点由 native
渲染器绘制，它通过 JNI 直读 `mLatitude`，**根本不会走 `getLatitude()`**。只 hook getter 对它透明，
只有把字段本身改掉才能盖住它。

### 地图 / 定位 SDK 内部通道（v1.2.0）

上面整张表都在 `android.location` 这一层。**带自研引擎的定位 SDK 可以完全不走这一层**——它们在
自己的 native 库里融合 GNSS / WiFi / 基站，然后通过自有类把结果交给 App：

| SDK | 注册入口 | 回调 | payload |
|---|---|---|---|
| 高德 | `AMapLocationClient.setLocationListener` | `AMapLocationListener.onLocationChanged` | `AMapLocation` |
| 百度 | `LocationClient.registerLocationListener` | `BDLocationListener.onReceiveLocation` | `BDLocation` |
| 腾讯 | `TencentLocationManager.requestLocationUpdates` | `TencentLocationListener.onLocationChanged` | `TencentLocation` |

`BDLocation` 根本不是 `android.location.Location`，`TencentLocation` 也从不出现在 `LocationManager`
里。走这三条路的 App，在本模块眼里等于什么都没发生——**建行生活（`com.ccb.longjiLife`）就是这一种**：
它是内嵌定位 SDK 的应用，只堵平台层对它透明。

三个通道的改法各不相同，不能套用同一段代码：

| SDK | 怎么写 |
|---|---|
| 高德 | `AMapLocation extends Location`，但 `getLatitude()` 读的是**自有字段** `q`，不是继承来的 `mLatitude`。写进去之所以有效，是因为它同时重写了 `setLatitude` / `setLongitude` / `setAltitude` / `setSpeed` / `setBearing`，走虚调用能落到正确字段；`getAccuracy` 委托给 `super`，所以 getter 也要一起 hook |
| 百度 | `BDLocation` 是独立类（非 `Location` 子类），反射逐个调自己的 setter：`setLatitude` / `setLongitude` / `setRadius` / `setLocType` / `setCoorType` / `setSatelliteNumber` / `setTime` |
| 腾讯 | `TencentLocation` 是**纯接口**，没有 setter，实现类在 SDK 之外，payload 无法重建 → 第一次见到实现类时装 hook 改它的 getter |

**坐标基准必须跟着 SDK 走**：高德 / 腾讯用 GCJ-02，百度用 BD-09。给百度 SDK 喂 WGS-84 会让结果
偏出几百米——症状是"能用但是错的"，比干脆失败难查得多。

**动态 ClassLoader 探测**：SDK 常常被放在 App 自建的 `DexClassLoader` 里加载，而不是 App 自己的
loader。钩子绑在错误的 loader 上会**全部静默失效**（`loadClass` 找得到类，hook 挂上去却没人调用）。
所以额外 hook 了 `ClassLoader.loadClass`，一旦看到 `com.amap.api.location.` /
`com.baidu.location.` / `com.tencent.map.geolocation.` 前缀，就地把通道装到那个 loader 上。

**主动派发**：只改"SDK 递给 App 的那一份"还不够。App 把 listener 注册在开启伪装**之前**、或两次
订阅之间隔很久，地图就会一开局冻在真实位置。所以捕获注册进去的 listener，按 1 Hz 主动回调，把
App 拉回锚点。listener 与定时器都走弱引用，App 不注销也不会漏。

开关在高级设置里：**「接管地图 SDK」**（默认开）。日志 tag 是 `FakeLoc/MapSdk` 与 `FakeLoc/Loader`
——建行生活这类 App 是否真的命中通道，看这两个 tag 一目了然。

> **和参考项目那个「付费模式」是什么关系**：把它拆开看，付费解锁的其实是一张**按定位通道分别
> 开关的兼容性表**（高德 / 百度 / 腾讯 / Android 系统 / Google / 华为各一项）。这里做成一个总开关
> 加自动探测：命中哪个通道由 App 自己用的 SDK 决定，不需要用户去猜该勾哪一项。

**坐标被改写了，地址字段没有——这就是「周边网点变了、城市名没变」的成因。** 改写的全是坐标数值：

| 通道 | 覆盖的坐标 getter |
|---|---|
| 高德 | `getLatitude` / `getLongitude` / `getAccuracy` / `getAltitude` / `getSpeed` / `getBearing` |
| 百度 | `getLatitude` / `getLongitude` / `getRadius` / `getAltitude` / `getSpeed` / `getCoorType` |
| 腾讯 | `getLatitude` / `getLongitude` / `getAccuracy` / `getAltitude` / `getSpeed` |

而 `getCity()` / `getProvince()` / `getAddress()` / `getPoiName()` / `getAddrStr()` 这类**地址字段不是
从坐标推出来的**：三个 SDK 都是把观测数据（WiFi、基站、GNSS）发给自己的服务器，服务器把「坐标 +
地址」一起返回。所以在设备上改坐标，一个字节都碰不到这些字符串。

后果是一种很容易误判成"没生效"的现象：**同一个 App 里，按坐标查的功能（周边网点、附近商户）
完全正确，但显示当前城市/地址的地方仍是真实的**。因为前者把经纬度发给服务端算，后者直接读 SDK
已经填好的字符串——建行生活首页左上角那个城市名就是后者。

### 1.2.2 起：地址字段挂钩子观测；1.3.0 起改为返回假地址

三个通道的地址 getter 全部被 hook。1.2.2 只观测（记日志），1.3.0 起在「同步伪造城市/地址」打开时
**直接返回伪造值**：

| 行为 | 默认 | 说明 |
|---|---|---|
| 首次读取打日志 | 一直开 | `FakeLoc/MapSdk  amap address field read: getCity="北京市"`；有替换时同一行给后半截 `— answering "吉林市"`。每个字段每进程只打一次 |
| 返回伪造地址 | **默认开**（1.3.0 起） | 高级设置里的「同步伪造城市/地址」；关掉就退回 1.2.2 的纯观测模式 |

第一次读取留下的日志，回答的是**只有它能回答**的问题：这个 App 到底有没有向 SDK 要地址。

| 日志里看到 | 结论 | 下一步 |
|---|---|---|
| `address field read: getCity="北京市"` | App 读的确实是 SDK 的地址字段 | 换成一个已解析出地址的锚点（地图选点存一次），确认「同步伪造城市/地址」是开的 |
| 只有 `... address fields hooked (17 getter(s))`，一条 `read` 都没有 | App 的城市不是从 SDK 拿的 | 够不着，别再在这条路上花力气（多半是服务端按 IP 判、或账号归属地） |
| 连 `hooked` 都没有 | 那家 SDK 不在这个 App 里 | `Loader` tag 会告诉你它用了哪家 |

安装时打的那行 `amap address fields hooked (N getter(s))` 就是为这张表存在的：否则"挂钩子没装上"
和"装上了但 App 不读"在日志里长得一模一样，而这两者该走的方向完全相反。

**改地址字符串，不是把字段清空。** 1.2.2 那版实验过「返回空串」，结果更糟——很多 App 拿到空城市名
会整段逻辑不执行、沿用上一次的旧值（建行生活首页就是死守"厦门"）。1.3.0 改成返回**选点时已解析出的
真实地址串**：`getCity()` 给"吉林市"、`getProvince()` 给"吉林省"、`getAdCode()` 给行政区划码。
数据来自选点页的逆地理结果（`PickedAddress` 的 province/city/district/adcode），以前取完就丢了。
1.5.0 之前这一路走的是百度 SDK 的 `addressDetail`；百度与高德移除后改用**系统 `Geocoder`** 的
`adminArea` / `locality` / `subLocality`，字段少一点但含义一致，而对 hook 侧来说两者是等价的 ——
它要的只是「这个点属于哪个省/市」，拿去喂给目标 App 自己的查表逻辑。

只对 `String` 类型的字段改写。给一个本来返回编码的 getter 塞字符串，App 拿去解析就是一个我们
自己造成的崩溃——所以非字符串字段永远原样放行。地址未解析出时（还没选过点）同样原样放行，
不塞空串。

### `com.android.providers.settings` 进程

| 类 | 方法 | 作用 |
|---|---|---|
| `SettingsProvider` | `call` | `GET_secure` + `mock_location` 的原始回复改写 |
| | `query` | 从原始 Cursor 里剔除 `mock_location` 行 |

`call` 和 `query` 是**两条不同的路**，不是同一个东西的两种叫法：`Settings.Secure` 优先走
`call("GET_secure")`，失败才回落到 `query`；而自己构造 `ContentResolver` 调用的检测代码
**直接**落在 `query` 上。只堵 `call` 会漏掉后者。

### `system_server` 进程（需 `system` 在作用域内）

| 类 | 方法 | 作用 |
|---|---|---|
| `LocationManagerService` | `getLastLocation` | 原地覆写 |
| `LocationProviderManager` | `onReportLocation` | 原地覆写下发 payload |
| `GnssManagerService` | 六个 GNSS 注册入口 | `antiMock` 开启时阻断原始 GNSS 数据 |
| `AppOpsService` | `checkOperation` / `checkOperationImpl` / `checkOperationUnchecked` | mock op → `MODE_ERRORED` |
| `Settings$Secure` + `AppOpsManager` | 同 app 侧 | 本进程内解析 `mock_location` 的服务也拿不到真话 |
| `android.location.Location` | mock 探针 | 同 app 侧 |

**刻意没做的事**：没有 hook `AppOpsManager.noteOp` / `startOp`。伪造**查询**是无痕的，伪造**写入**
会记下一次从未发生过的操作，污染 app-op 计数器（`dumpsys` 和设置页会读到）。副作用是：如果你同时
装了真正的模拟位置 App，它仍能正常工作。

**「隐藏模拟标记」到底盖了哪几层**——一个 App 判定「这是虚拟定位」的所有读路径：

| 读路径 | 覆盖方式 |
|---|---|
| `Location.isMock()` / `isFromMockProvider()` | 直接返回 `false` |
| `Location.mFieldsMask` 的 mock 位 / `mIsFromMockProvider` / `mMock` 字段 | `MockTrace` 反射清位（在 `Location` 被覆写时顺带做） |
| `Location.getExtras()` 的 `mockLocation` 键 | 读、写、`set(Location)` 三处都剔 |
| `Settings.Secure` 的 `mock_location`（= 被选中的模拟位置 App 包名） | `getStringForUser` → `"0"` |
| `SettingsProvider.call` / `query` 原始通道 | 改写回复 / 剔除 Cursor 行 |
| `AppOpsManager` 的 `OP_MOCK_LOCATION`（7 个入口） | `MODE_ERRORED` |
| `AppOpsService`（系统服务侧权威答案） | `MODE_ERRORED` |
| `LocationManager.getProviders()` 里的测试 provider 名 | 只保留标准 provider |

最后一条值得单独说：**测试 provider 的名字是本模块唯一堵不住的"标记"以外的那个标记**。模块自己是靠
hook 注入的，不会注册测试 provider，所以本机干净；但如果你**另外**装了一个摇杆类模拟 App 并被选为
模拟位置应用，它的 provider 名（`FakeLoc`、`test`、或它自己的包名）就会出现在
`getProviders(true)` 里——这是 App 侧唯一能凭名字直接看出"装了模拟 App"的地方。所以这里做了**白名单**：
只保留 `gps` / `network` / `passive` / `fused` 以及几个厂商自有 provider（`gnss`、`nlp`、`lbs`、
`hiflp`、`indoor`）。**只过滤"列出来"，不动 `requestLocationUpdates`**——已经知道 provider 名的 App
照常工作，不会有原本能成的调用现在抛异常。

### 原始定位源锁定（`strictSources` 开启时，只在目标 App 进程内）

| 类 | 方法 | 返回 |
|---|---|---|
| `android.net.wifi.WifiManager` | `getScanResults`（含隐藏的 `(Bundle)` 重载） | 空列表 |
| `android.telephony.TelephonyManager` | `getAllCellInfo` | 空列表 |
| | `getCellLocation` | `null` |
| | `getNeighboringCellInfo`（旧 API） | 空列表 |
| | `requestCellInfoUpdate`（回调式） | 不调用，回调永不触发 |
| `android.location.LocationManager` | `addNmeaListener` | `true`，不注册 |
| | `registerGnssMeasurementsCallback` | `true`，不注册 |
| | `registerGnssNavigationMessageCallback` | `true`，不注册 |
| | `addGnssBatchingCallback` | `true`，不注册 |

为什么需要这一层：上面所有 hook 都是"改写平台递给 App 的定位结果"，这只在 **App 确实向平台要定位**时
成立。带自研引擎的定位 SDK 可以绕开它：要么拿原始 GNSS 自己解算，要么把 WiFi/基站指纹发给自己的服务器
换一个坐标回来——后者算在别人机器上，**在进程里怎么 hook 都改不了**。两种绕法的症状一模一样：地图先
跳到注入点，几秒后漂回真实位置。

`NMEA` 这句值得单独说：`GGA` 语句里**明文带着经纬度**。一个 `addNmeaListener` 没被封住的 App，
拿到的就是真实坐标，而且完全合法、看不出任何异常。

**刻意保留不动的**：`registerGnssStatusCallback` / `addGpsStatusListener`（卫星可见性不含坐标，封掉只
会让 App 判定"GPS 不可用"转而走网络定位，与目标相反）、`WifiManager.getConnectionInfo()`（扫不到热点
之后，单个已连接 AP 已经不足以定位，而伪造 `WifiInfo` 需要编造 BSSID/SSID 字符串，风险不划算）、以及
一切 IP 派生的定位（封它等于封网络，那就是 App 自己）。这一层**只在被 hook 的 App 进程里生效**，设备
上其他 App 的定位完全不受影响。

---

## 九、构建环境踩坑记录

本机原本没有 Android SDK。以下五件事各花了不少时间，换机器照着走可以跳过。

**1. `repo.maven.apache.org` 的 TLS 握手会被中断。** 症状是
`SSL peer shut down incorrectly / Remote host terminated the handshake`，而同一个域名 `curl` 却是 200。
根因是并发拉依赖时打到 Cloudflare 边缘被 RST，不是证书问题（不用调 cacerts）。
**解法**：`settings.gradle.kts` 里把阿里云镜像放在 `google()` / `mavenCentral()` 之前；
wrapper 的 `distributionUrl` 指向腾讯云。已在仓库里配好。

**2. AGP 会自己下载缺失的 SDK 组件，而且没有超时。** 第一次构建卡在
`Preparing "Install Android SDK Build-Tools 34" / Still waiting for package manifests to be fetched remotely`
整整 20 分钟，CPU 0%、日志不再增长——看起来像死锁，其实是在等一个永远不来的响应。
**解法**：用 `sdkmanager` 手动装好 `build-tools;34.0.0`（AGP 8.7.3 的默认值）和 `35.0.0`，
并在 `gradle.properties` 里设 `android.builder.sdkDownload=false`，让缺组件时**立刻报错**而不是静默卡住。

**3. libxposed 的 AAR 元数据要求 `compileSdk 36`。** `io.github.libxposed:service:101.0.0`（以及它
传递引入的 `interface`）声明了 `minCompileSdk 36`，AGP 遇到这种情况是硬失败而不是降级：
```
Dependency 'io.github.libxposed:service:101.0.0' requires ... version 36 or later of the Android APIs.
```
**解法**：`compileSdk = 36` + 装 `platforms;android-36` + 用
`android.suppressUnsupportedCompileSdk=36` 压掉 AGP 8.7.3「推荐最高 35」的告警。
`targetSdk` 保持在 35——提高 compileSdk 只是放宽可引用的 API 面，不会让 App 进入新的运行时行为。

**4. Kotlin 版本必须 ≥ 2.2.10。** libxposed 是用 Kotlin 2.2.10 编译的，会把 `kotlin-stdlib` 拉到
2.2.10；而 Kotlin 2.0.21 的编译器读不了这个版本的元数据，报的是个毫无线索的内部错误：
```
e: FileAnalysisException: While analysing App.kt:25:5: java.lang.IllegalArgumentException: source must not be null
e: ...kotlin-stdlib-2.2.10.jar!/META-INF/kotlin-stdlib.kotlin_module
```
看最后那行——**stdlib 版本和编译器版本对不上**才是线索。已把 `kotlin` 提到 2.2.10。

> 顺带一个教训：`gradle ... | tail -120` 会把真正的 `FAILURE: What went wrong` 段落截掉
> （它在堆栈**之前**输出），只剩一堆 `at org.gradle...` 看着像谜语。构建日志要写全量文件再筛。

**5. 资源字符串里的裸撇号会让资源合并失败，而报错指向别处。** 写英文文案时 `Tencent's` 里的 `'`
没转义，AGP 报的是：
```
values/strings.xml:50:4: Failed to flatten XML for resource 'map_datum_note'
  with error: Invalid unicode escape sequence in string
```
——**「invalid unicode escape」和真实原因（未转义撇号）毫无关系**，而且它报的**行号与资源名也对不上**
（`map_datum_note` 并不在第 50 行，第 50 行是另一个字符串；aapt2 指的是合并后的中间文件）。

**解法**：别信那句报错，直接用 aapt2 单独编资源目录，它给的是准的：
```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 compile --dir app/src/main/res -o /tmp/out/res.zip
# → values/strings.xml:45: error: unescaped apostrophe in string "The base map is Tencent's…"
```
两处约定，本仓库统一按这个来：

- **英文文案里的撇号一律用排版撇号 `’`（U+2019），不用 `'`。** 已有的 `Xi\'an` 这类是转义写法，
  也能用，但混着写迟早会漏。
- **`\uXXXX` 不是 aapt2 支持的转义。** 非 ASCII 字符直接写原字符（文件是 UTF-8），
  不要写 `\u2014` 这种 Python/JS 风格的转义。
  > 这一条是踩出来的另一种形态：用脚本生成文案时，Python 源码里的 `"\u2014"` 会被 Python
  > **先**解释成真字符，落盘是对的；但一旦哪次写成了字面量的反斜杠-u，aapt2 就会拒收，
  > 报错同样是上面那句。**落盘后拿 `repr()` 看一眼**比猜快。

---

## 十、已知限制（都是真的）

1. **没有在真机上跑过。** 编译和静态检查都通过了，但 hook 是否命中、地图是否渲染、LSPosed 是否
   正确握手，都需要你装上去验证。
2. **原始 NMEA / GNSS 观测数据**只在系统级作用域开启时才会被压制。只勾目标 App 时，
   `addNmeaListener` 之类仍能拿到与假坐标矛盾的真实卫星数据。用这类数据做交叉校验的 App 会识破。
3. **Wi-Fi / 基站指纹**完全没做。XposedFakeLocation 有 Wi-Fi 身份伪造，这里没有——
   如果你要对抗基于 Wi-Fi BSSID 的定位，那部分得自己加。
4. **`system` 作用域下是本机全量伪造**，包括 FakeLoc 自己的「使用真实定位」按钮。
5. **反检测不是隐身**。它只抹掉 mock 标记。任何做多源交叉验证的 App（真 GPS 与网络定位对不上、
   传感器与轨迹不符）仍可能识别出异常。
6. **功能开关只在运行中的进程生效**，新增作用域内的 App 仍需重启目标 App 一次。
7. **地图页需要联网**。腾讯底图瓦片走 HTTPS，地址查询走系统 `Geocoder`，两者都要网。
   底图**必须填 Key**；地址查询不需要。

---

## 十一、排查

LSPosed 管理器 → 日志，过滤 `FakeLoc`。所有 hook 安装结果都会打进去：

```
FakeLoc/Entry       loaded into com.example.target
FakeLoc/State       state ready for com.example.target
FakeLoc/Location    Location#getLatitude hooked (1 overload(s))
FakeLoc/Listener    no LocationListenerTransport on this ROM (...); live listeners not spoofed
```

`hooked N/M overload(s)` 表示部分重载没挂上；`not found` / `not present on this ROM` 表示这个 ROM
改了类名——大多数 hook 都带了 AOSP / 旧版本两条候选路径，但 OEM 分支（MIUI、ColorOS）偶尔需要补一条。

**全部 tag 速查**（`FakeLoc/` 后面那一段）：

| tag | 管什么 |
|---|---|
| `Entry` | 模块进了哪个进程、装了哪一套 hook |
| `State` | 远程配置读取结果——**配置没读到会在这里报 error**，字段全用默认值 |
| `Location` | `android.location` 平台层各拦截点的安装回执 |
| `Listener` | 订阅式回调（`LocationListenerTransport`） |
| `Payload` | payload 四形态识别，每种只打一次 |
| `AntiMock` | mock 标记的各条读路径 |
| `Lock` | 原始定位源切断（WiFi 扫描 / 基站 / NMEA / GNSS 观测） |
| `Framework` | `system_server` 侧（需 `system` 在作用域内） |
| `Gms` | Google Play Services 融合定位 |
| `MapSdk` | **高德 / 百度 / 腾讯 SDK 通道**（1.2.0） |
| `Loader` | **动态 ClassLoader 探测**（1.2.0） |
| `SdkPush` | 主动派发**失败时**才会响；平时完全安静 |
| `Diag` | 投递路径打点：只打「与锚点的距离差」，不含坐标 |
| `MockTrace` | mock 字段的反射清理，只在异常时出声 |

### 日志一行都看不到时，按这个顺序查

1. **日志级别**。LSPosed 管理器日志页的设置里把级别调到最低（Verbose）。本模块的 `info` 走的是
   `Log.INFO`，级别设得比它高就什么都不显示——看起来就像"模块没干活"。
2. **先清空日志，再强行停止并重开目标 App**。否则你看到的是历史记录，分不清哪些是这次跑出来的。
3. **目标 App 必须在 LSPosed 作用域里、并且重启过**。日志里连
   `FakeLoc/Entry  loaded into <包名>` 都没有，说明模块根本没进它的进程——这时跟 hook 无关，
   是作用域的问题（新加的 App 不重启不会生效）。

> 日志是通过 `XposedInterface.log()` 写出去的，所以它出现在 **LSPosed 管理器**里，不需要
> `adb`。只有模块还没绑定时才回落到 `android.util.Log`。

### 地图 SDK 通道（1.2.0）看这两个 tag

| 日志里看到 | 含义 |
|---|---|
| `FakeLoc/Loader  watching for map SDK class loaders` | 动态 loader 探测已装好（进 App 进程必然出现） |
| `FakeLoc/Loader  com.amap.api.location.AMapLocation arrived via dalvik.system.DexClassLoader; arming channels there` | 抓到了 SDK 真正所在的 loader——**这一行直接告诉你是谁家的 SDK** |
| `FakeLoc/MapSdk  AMap channel armed (N read path(s), M listener entry point(s))` | 高德通道命中（百度 / 腾讯是同样句式） |
| `FakeLoc/MapSdk  amap address fields hooked (17 getter(s))` | 地址 getter 也挂上了（1.2.2 起，安装时打一行） |
| `FakeLoc/MapSdk  amap address field read: getCity="北京市"` | **App 真的向 SDK 要过地址**——每个字段每进程只打一次 |
| 有 `Loader  watching` 但**没有任何** `armed` / `arrived via` | 这个 App 不用这三家 SDK——换方向查，见下面「跳回真实位置」表的最后一行 |

`armed` 系列是**静默缺席**的：某家 SDK 不在这条 loader 里时代码直接返回、不打任何日志，
否则每个不用高德的 App 都会刷一条无用警告。`address field read` 同理——**没有输出本身就是结论**。

### 「坐标变了、城市名没变」——地址字段是另一条完全不同的路

实测于建行生活：周边网点跟着虚拟定位走，首页左上角的城市名纹丝不动。完整分析在第八节，
排查时只看三行就够：

| 日志里看到 | 含义 | 下一步 |
|---|---|---|
| `... address fields hooked (17 getter(s))` 且 `address field read: getCity="..."` | App 读的确实是 SDK 的地址字段 | 看这行有没有 `— answering "…"` 后半截：有，说明我们替换了；没有，说明该 getter 没配到值（或 `sync_address` 关着） |
| 只有 `hooked`，一条 `read` 都没有 | App 的城市不是从 SDK 拿的 | 够不着，别在这条路上花力气 |
| 连 `hooked` 都没有 | 该家 SDK 不在这个 App 里 | 翻 `Loader` tag，看它到底用了哪家 |

### 启动自检行（1.2.1）——先看这一行

模块进入每个 App 进程时会紧跟 `state ready for ...` 打一行状态快照：

```
FakeLoc/State  inScope=true playing=true mapSdkCompat=true strictSources=true antiMock=true scopeAll=false targets=2 anchor=39.908700,116.397500
```

**为什么需要它**：前面所有 hook 都装上了、SDK 通道也 `armed` 了，仍然可能一个字都不改写——
因为 `inScope=false`（这个 App 不在「生效范围」名单里）或 `mapSdkCompat=false`（开关没开）。
这两个值在别处**完全看不出来**，`armed` 的日志照样会打，症状却和"通道根本没挂上"一模一样。
这一行就是用来把「hook 没装上」和「hook 装上了但不许开火」分开的。

- `inScope=false` 时**额外**会打一条 warn：`this process is NOT in the target list — nothing will be spoofed here`
- `anchor` 打的是**用户自己选的坐标**，不是设备真实位置，所以整行可以随手贴出去
- `targets=N` 是「生效范围」名单里的包数量；`scopeAll=true` 时该名单被忽略，全系统生效

**「先是模拟位置，过一会跳回真实位置」——按这个顺序看日志**

这是最值得先查的一类问题，因为它有三种互不相干的成因，从外面看症状完全一样，而修法彼此无效：

| 日志里看到 | 含义 | 该动哪一层 |
|---|---|---|
| `FakeLoc/Diag  Location.getLatitude() is being read` | App 走 Java getter 读坐标 | getter 路径是活的，不用动 |
| `FakeLoc/Diag  path='listener' delivered a fix 12.4 km off the anchor` | 订阅回调确实触发，且**改写前**那是个真实坐标 | 平台通道是通的、并且已被正确覆写 |
| `FakeLoc/Diag  path='listener' delivered a fix on the anchor (already ours)` | 回调拿到的已经是我们的值 | 同上，无异常 |
| 上面这些**一行都没有**，位置仍旧跳回真实坐标 | App 压根没向平台要定位 | 它用自己的引擎算的 → 靠「锁定定位源」那一层 |
| `FakeLoc/Listener  no LocationListenerTransport on this ROM` | 订阅通道没挂上，只改了 pull 路径 | 需要按你 ROM 的实际类名补一条候选 |

距离是**故意只打距离、不打坐标**的：区分"我们的值"和"设备的值"够用，而日志里不含真实位置，
才能随手贴出来。

**开了「锁定定位源」之后某个 App 直接报"定位失败"**：把这个开关关掉再试。它封的是 WiFi 扫描、
基站与原始 GNSS，只靠平台定位的 App 不受影响；少数把网络定位当唯一来源的 App 会因此无定位可用。

想看每个拦截点的进出值，把 `HookState.verbose` 改成 `true`（`xposed/HookState.kt`）。
默认关闭是有原因的：被 hook 的 `getLatitude()` 每秒可能触发几千次。

**服务连不上**：LSPosed 里启用模块后，强行停止 FakeLoc 再打开。绑定服务是在
`Application.onCreate` 注册的，模块刚启用时可能还没建立连接。

**改了配置没反应**：先确认目标 App 在 LSPosed 作用域里（这是最常见的原因），再确认它已经被重启过至少一次。

**地图一片灰 / 提示未配置 AK**：AK 没填，或包名/SHA-1 与申请时不一致。用
`apksigner verify --print-certs app-release.apk` 核对实际签名指纹。

**地图加载不出来但 AK 是对的**：检查 AK 有没有勾选「地点检索」和「逆地理编码」这两个服务——
它们和「地图 SDK」是分开授权的。

---

## 十二、项目结构

```
FakeLoc/
├── gradlew / gradlew.bat / gradle/wrapper/     ← 已生成，指向腾讯云镜像
├── local.properties                            ← 不进 git：只有 sdk.dir（Key 不入包，见第四节）
├── tools/fakeloc-gate.py                       ← 静态门禁（纯标准库），改完先跑它再构建
├── app/src/main/
│   ├── java/io/github/cobylinweiqi/fakeloc/
│   │   ├── App.kt                        Application + LSPosed 服务绑定（不再初始化地图 SDK）
│   │   ├── MainActivity.kt               单 Activity，四个页面（首页 / 地图 / 目标应用 / 设置）
│   │   ├── core/                         两侧共用的纯逻辑
│   │   │   ├── SpoofConfig.kt            配置模型 + key 常量 + 地图供应商与 Key
│   │   │   ├── MapSettings.kt            MapProvider 枚举（按 id 持久化，1.5.0 起只剩 TENCENT）
│   │   │   ├── ConfigCodec.kt            org.json 编解码（防御式，永不抛）
│   │   │   ├── Coords.kt                 WGS-84 / GCJ-02 / BD-09 三系互转
│   │   │   └── Geo.kt                    球面几何 / 漂移引擎 / 坐标解析
│   │   ├── data/
│   │   │   ├── RemoteStore.kt            远程偏好 + 本地镜像读写
│   │   │   └── InstalledApps.kt          已安装应用枚举
│   │   ├── mapsdk/
│   │   │   └── MapSdkBootstrap.kt        启动腾讯 SDK（幂等、失败不外抛）
│   │   ├── ui/                           Compose 界面
│   │   │   ├── MapPickerScreen.kt        选点页（搜索 + 图钉 + 系统 Geocoder 逆地理）
│   │   │   ├── SettingsScreen.kt         设置页：两个页签 —— 地图配置（Key / 包名与 SHA-1 / 申请步骤）
│   │   │   │                            与信号微调（v1.6.0 从首页搬来，8 开关 + 4 滑杆）
│   │   │   ├── HomeScreen.kt             首页：状态 / 选点 / 坐标 / 预设 / 生效范围（不再放信号微调控件）
│   │   │   ├── TargetAppsScreen.kt       目标应用选择
│   │   │   ├── Components.kt / Theme.kt  组件与配色
│   │   │   └── MainViewModel.kt          单一状态源
│   │   ├── ui/map/                       底图抽象（对外只讲 WGS-84）
│   │   │   ├── MapCanvas.kt              分派 + 坐标换算 + 受保护的视图构造
│   │   │   └── TencentCanvas.kt          GCJ-02，Key 逐视图传入
│   │   └── xposed/                       ← libxposed 侧（1.5.0 起被 R8 整包保留）
│   │       ├── ModuleEntry.kt            入口，按进程分派 hook
│   │       ├── HookKit.kt                反射工具 + hookAll + 日志
│   │       ├── HookState.kt              hook 侧配置快照 + 位置构建
│   │       ├── LocationPayload.kt        四种 payload 形态的原地覆写（含 native 直读字段）
│   │       ├── Diag.kt                   一次性诊断埋点（只打距离差，不打坐标）
│   │       ├── MockTrace.kt              四种 mock 标记的清除
│   │       └── hooks/                    六个 hook 安装器
│   │           ├── LocationHooks.kt      Location 字段 / LocationManager 拉取式 API
│   │           ├── ListenerHooks.kt      订阅式回调（LocationListenerTransport）
│   │           ├── PlayServicesHooks.kt  GMS LocationResult
│   │           ├── FrameworkLocationHooks.kt  system_server 侧
│   │           ├── AntiMockHooks.kt      mock 标记的全部读路径
│   │           └── SourceLockHooks.kt    WiFi / 基站 / 原始 GNSS 切断
│   ├── resources/META-INF/xposed/        module.prop / java_init.list / scope.list
│   └── res/                              strings（en + zh）/ themes / 图标
└── gradle/libs.versions.toml
```

---

## 十三、验证情况

> 倒序排列，最新的在最上面。**下面 1.4.x 及更早的条目里出现的「三家」「百度」「高德」都是当时的
> 事实**，1.5.0 已经把其中两家移除 —— 读旧条目时请对照本节第一条，不要把它们当成现状。
> 同理，**1.6.0 之前的条目里写的包名 `com.amo.fakeloc` 也是当时的真实值**，那一年它还没改名。

> **1.6.0 的签名变更：Release 改用项目专用密钥，不再用 debug 签名。**
>
> 之前 Release 一直用 Android 默认 debug 签名（`CN=Android Debug`）。那样能编译、能安装，
> 但**不能长期发布**：debug 密钥跟着机器走，换电脑或重装系统后 SHA-1 就变了，而 Android
> 只允许同签名的包互相升级 —— 已经装上的人从此收不到更新，只能卸载重装。现在改为从仓库
> 根目录的 `keystore.properties` 读专用密钥（`CN=FakeLoc`，SHA-1
> `28a3a1d6f8a0d9b00af1a33a70586cc0c20a04b0`，有效期 10 000 天）。
> **该文件与 `.jks` 都在 `.gitignore` 里，绝不进仓库** —— 拿到密钥的人可以签出 App 会接受的更新。
>
> 两条代价说清楚：
>
> - **已装的 debug 签名版本不能被覆盖**，签名必须一致才允许升级，所以要先卸载（App 内配置随之丢失）
> - **地图 Key 绑的是「包名 + SHA-1」**，腾讯控制台里按旧 SHA-1 登记过的 Key 对新 Release 不再适用，
>   换包时要按 `28a3a1d6…` 重新登记
>
> 没有 `keystore.properties` 的克隆仍回落到 debug 签名，`assembleRelease` 开箱可用 —— 只是那个包
> 与官方 Release 不能互相覆盖。门禁为此新增三组断言：仓库内不得出现 `.jks` / `.keystore`；
> `.gitignore` 必须含 `*.jks` 与 `keystore.properties`；`signingConfig` 必须走「有 properties 用它、
> 否则 debug」这条分支（这条最要紧 —— 静默回落到 debug 照样编译、照样安装，只是发出去的包
> 覆盖不了任何已装用户）。5 种破坏方式定向自证，全部被抓出。

> **1.6.0 的应用 ID 变更：`com.amo.fakeloc` → `io.github.cobylinweiqi.fakeloc`。**
>
> 起因和功能无关，是**分发**。要把模块收进 LSPosed 的在线模块仓库（`Xposed-Modules-Repo`），
> 那个仓库会校验应用 ID 的反向域名归提交者所有：要么在自有域名根上挂一条
> `lsposed-modules-repo-verification=<GitHub 用户名>` 的 TXT 记录，要么用 `io.github.<用户名>` 前缀。
> `amo.com` 不是本项目作者的域名，所以走第二条：中间段就是发布本项目的 GitHub 账号
> （`Cobylinweiqi`；包名一律小写，因此写作 `cobylinweiqi`）。**它必须与开 submission issue
> 的那个账号一致**，否则仓库不受理。
>
> **这是破坏性变更 —— 换应用 ID 等于换一个 App：**
>
> - 手机上旧包名 `com.amo.fakeloc` 要**先卸载**，新的装上去是另一条记录，不会覆盖
> - LSPosed 里的作用域勾选、以及 App 内的全部配置都按**包名**存，**都会丢**，需要重新配
> - 源码里 137 处引用一次性跟过去：包声明、import、`namespace`、`applicationId`、
>   `proguard-rules.pro` 的 keep 规则、`META-INF/xposed/java_init.list` 里的入口类全名
> - 门禁为此新增两组断言 —— 「旧包名不得出现在任何源码 / 配置里」与「`applicationId` 必须等于新值」
>   （外加 `java_init.list` 与 `module.prop` 的存在性断言，此前这三个文件**一个断言都没有**，
>   而它们的每一种失败模式都是静默的）。用 6 种破坏方式定向自证过：只改一半、删 keep 规则、
>   入口类陈旧、API 版本写错、源码残留、README 残留，全部被抓出

静态门禁脚本是 `tools/fakeloc-gate.py`（纯标准库，Python 3.9+ 可跑），在**仓库根目录**执行：

```bash
python3 tools/fakeloc-gate.py    # 只有每一项计数都对上，退出码才是 0
```

它同时查「必须存在」和「必须不存在」两半 —— 只查存在的那一半看不出「删了一半」。
改动代码或资源后先跑它、再 `./gradlew assembleRelease`。

**1.6.0：信号微调搬进设置页，与地图配置分成两个页签。**

起因是首页太长：`信号微调` 那张卡（8 个开关 + 展开后 4 条滑杆）夹在坐标编辑和底部启动按钮之间，
而这两样才是用户真正来回切的东西。设置页又只有地图 Key 那点内容，两半都挤在一个滚动里的话，
开关会落到三屏「怎么申请 Key」的说明下面 —— 这正是它们当初被放到首页的原因。页签同时解决两头。

- **首页**：撤掉 `SignalTuningCard`（768 → 609 行）。状态卡上那三个 chip（精度 / 海拔 / 漂移）
  **保留** —— 撤走的是控件，摘要留着，一眼还能看到当前值。首页的 `ToggleRow` 只剩「作用域内全部应用」
  那一个，`SliderRow` 归零。
- **设置页**：标题下加 `TabRow`，`地图配置` / `信号微调`。页签名不新增 `信号微调` 文案 ——
  直接复用 `section_advanced`，同一句话不存两份。新增两条字符串：`settings_tab_map`、
  `settings_signal_desc`（页签顶部的说明行）。
- **每个页签各一份滚动位置**（`rememberScrollState()` × 2，都在组合外无条件创建）。共用一个会把
  地图页的偏移带进信号页；写在 `when` 分支里则每次切页签都被丢弃、页面弹回顶部 —— 两种做法都不对。
  当前页签用 `rememberSaveable` 存枚举名，和外壳的页面切换同一套路（String 在任何 API 上都能过
  `Bundle`，不必手写 `Saver`）。
- **门禁的断言跟着搬了，这是本轮最有价值的一条**：它原本断言 `ui/HomeScreen.kt` 里
  `import …LocationCity` / `config.syncAddress` / `R.string.adv_sync_address` 各若干次 —— 全是这次要
  移走的东西。改完源侧断言为 0、目标侧断言为 1，两组互为镜像：**只搬到一半（留下两份活控件）
  或只改一侧（两边都没有）都会被抓出来**。另加 `"SignalTuningCard": 0` / `"ToggleRow(": 1` /
  `"R.string.adv_": 3` 三条，专门证明「控件走了、摘要没走」。
- 静态门禁 PASS（35 文件 7 898 行、括号配平 0、`R.string` 109 处全有定义、双语 114/114 对称、
  无重复、无孤儿）；`BUILD SUCCESSFUL`，产物 **4,979,120 B（4.98 MB）**，`aapt2` 断言
  `vc=15 / vn=1.6.0 / native-code: arm64-v8a`；`dump strings` 里 `地图配置` / `信号微调` /
  `Map config` / `Signal tuning` / 说明行均 PRESENT。
- **安装**：root 覆盖升级成功，`firstInstallTime` 仍是 14:13:47、`fakeloc_local.xml` 未被触碰
  → 配置保留（装前已把该文件备份出来，以防又要重填）。
- **未做真机模块回归**：本轮 `xposed/**` 一行未改、keep 规则未动，R8 模式化保留与新增的界面枚举无关，
  所以没有重复 1.5.0 那次日志验证。需要的话可以再跑一次。

**1.5.1：清掉文案里残留的百度与「三家」。**

触发点是 v1.5.0 只删了**代码**、没回头扫**文案** —— 选点按钮上还写着「在百度地图上选点」。
顺着扫出另外两处同源死文字：`map_failed_desc` 建议用户「换一家地图供应商…**若三家都不行**」、
`settings_console_desc` 说「**三家平台**都会把 Key 绑定到…」。包里只剩腾讯一家、控制台也只剩一个，
**错的引导比缺字符串更糟**：用户会照着做，然后在「换一家」这条死路上耗时间。

- 改 4 条（中英各 2）：`action_open_map` →「在地图上选点」/「Pick on map」；
  `map_failed_desc` → 改成「多半是 Key 填错，或控制台里没勾选对应产品」；
  `settings_console_desc` →「腾讯位置服务会把 Key 绑定到…」。
  `adv_map_sdk_desc` 仍写「高德 / 百度 / 腾讯三家」**而且是对的** —— 它说的是注入通道，
  不是底图，别被字面相似骗去改掉。
- 门禁新增两组断言，并做了**定向破坏自证**：非豁免字符串里出现
  `百度/Baidu/高德/Amap/三家/none of the three/Another provider/Each platform` 即失败；
  `action_open_map` 里还不许出现任何厂商名。自证：把文案改回「在百度地图上选点」→
  `GATE: FAIL (2 failing checks)`，诊断精确指到 `('zh','action_open_map','百度')`；还原后 PASS(0)。
  **不破坏一次就断言不了门禁真的在看那个方向。**
- 顺带被门禁自己拦下：它本来就断言 `app/build.gradle.kts` 的版本行，升到 vc=14 / vn=1.5.1 后
  必须先同步断言，否则 `declaration count mismatches: 2` —— 版本号本来就是产物断言的一部分。
- `BUILD SUCCESSFUL`，产物仍是 **4,968,960 B（4.97 MB 十进制 / 4.74 MiB）**，与 1.5.0 **字节数完全相同**。
  逐条目对比两个包（317 条目、条目集合一致）：**只有 `AndroidManifest.xml` 的压缩尺寸差 1 字节**
  （版本号变了），`resources.arsc` 的 raw 与 compressed 尺寸**一模一样**（字符串池的 4 字节对齐
  把几处长度增减吸收了），dex 完全未动。但其 sha256 已变：`332ba0749a0fe490…`。
- `aapt2` 断言 `vc=14 / vn=1.5.1 / native-code: arm64-v8a`；`aapt2 dump strings` 复核
  新文案 PRESENT、旧文案（含 `Pick on Baidu map`）absent。
- **未做真机安装**：这一版是纯资源改动、且安装有丢配置的风险（上一次实测被重置成全新安装，
  App 内配置全丢），所以产物只放到桌面，由你自己决定何时装。

**1.5.0：瘦身到 5 MB 以下（去掉百度 / 高德底图 + 打开 R8）。**

- 静态门禁 PASS（35 文件 7 829 行、括号配平 0、`R.string` 107 处全有定义、双语 112/112 对称、
  无重复、无孤儿）。门禁本轮新增两组**「必须不存在」**断言：已删 SDK 的 import/类名痕迹计数为 0、
  两个 canvas 源文件不存在 —— 只查存在的门禁看不出「删了一半」。
- `BUILD SUCCESSFUL`，产物 **4.97 MB**；`aapt2` 断言 `vc=13 / vn=1.5.0 / native-code: arm64-v8a`。
  zip 内实测：`lib/` 只剩 `libtxmapengine.so` + `libtxmapvis.so` + `libandroidx.graphics.path.so`，
  `assets/` 只剩 `assets/tencentmap`，全部路径里搜不到 `Baidu` / `AMap`。
- **R8 的产物断言（本轮唯一有真实风险的改动）**：dex 类数 28 726 → 4 900，dex 压缩块 1.83 MB
  （`compress_type=8`，`useLegacyPackaging` 仍有效）；`META-INF/xposed/java_init.list` 里仍写着
  `io.github.cobylinweiqi.fakeloc.xposed.ModuleEntry` 且该类**按原名**存在；清单声明的 `MainActivity` 与 `App`
  按原名存在；整个 `io.github.cobylinweiqi.fakeloc.xposed.**` 包按原名保留；`core/SpoofConfig` 按原名保留
  （它被手工序列化成 JSON）；腾讯 SDK 2 167 个类原样保留。
- **真机验证（root 读 LSPosed 日志）**：重启 `com.ccb.longjiLife` 后三个进程（主进程 /
  `:remote` / `:CMCoreService`）全部加载模块，自检行为
  `inScope=true playing=true mapSdkCompat=true strictSources=true antiMock=true scopeAll=true syncAddress=true targets=0 anchor=39.908700,116.397500`；
  三条 SDK 通道全部 armed（`AMap channel armed (6 read path(s), 1 listener entry point(s))`、
  `Baidu channel armed (6 read path(s), 2 listener entry point(s))`、腾讯走 MapView 构造）。
  本轮 168 行模块日志里**零异常** —— 没有 `ClassNotFoundException` / `NoSuchMethodError` /
  `UnsatisfiedLinkError`。这是「R8 没有把模块改坏」的直接证据，也正是它成立的原因：
  同一份配置在 R8 打开前后，自检行逐字相同。
- 安装走 root（`adb install -r` 被 HyperOS「USB 安装」拦下，见第九节），
  `firstInstallTime` 仍是 14:13:47 未变 → 是**原地升级**，LSPosed 侧配置未丢。
- **未验证**：腾讯底图的实际渲染。需要你自己填 Key 并重启 App 才能看到。

**1.4.1：移除内置 Key。** 编译通过、静态门禁通过、产物已核验并覆盖安装到测试机（vc=12 / vn=1.4.1）。

- **本包不再携带任何地图凭据。** 拆掉的是两处：构建期 `manifestPlaceholders["BAIDU_MAP_AK"]`
  与 manifest 里的 `com.baidu.lbsapi.API_KEY`；以及运行期的凭据分支 —— `MapCredential` 枚举、
  `SpoofConfig.mapCredentialId`、编解码里的 `map_credential`，连带设置页那套「内置 / 自己的」切换。
  现在 `mapKeyFor(provider)` 就是「用户填的那个或 `null`」，没有第二个来源。
- **百度现在也走 `SDKInitializer.setApiKey`。** 它原本靠 SDK 自己去 manifest 读那条 meta-data，
  而 manifest 已经没有那条了。代码里加的是 `if (!key.isNullOrBlank())` 而不是 `if (key != null)`：
  空串对这些 SDK 意味着「配了一个空 Key」，会把一个清楚的「还没配」变成一次鉴权失败。
- **产物核验**：`aapt2 dump badging` → vc=12 / vn=1.4.1 / `native-code: arm64-v8a`；
  解开 APK 读二进制 manifest，`com.baidu.lbsapi.API_KEY`、`BAIDU_MAP_AK` 与旧 AK 明文**均不存在**；
  在 `resources.arsc` + 全部 dex + `res/` 里搜那五个已删字符串名与 `map_credential`，同样为空。
  （`classes2.dex` 里仍能找到 `com.baidu.lbsapi.API_KEY` 这个**字符串常量**，那是百度 SDK 自己
  用来查 manifest 的键名，不是凭据。）
- 旧配置里遗留的 `map_credential` 字段不会被读，配置解析照常——那个字段只喂过选点页自己的开关，
  从不参与 hook 侧。
- **真机渲染仍未验证**，需要你装上后在设置页填自己的 Key 再看。

**1.4.0：三家底图可切换。** 编译通过、静态门禁通过；**真机渲染尚未验证**（需要装到设备上看图）。

- 选点页的底图从「只认百度」改为三选一：百度（BD-09）、高德（GCJ-02）、腾讯（GCJ-02）。
  三家 SDK 全部编进包，由 `MapSdkBootstrap` 在**打开选点页时**按配置启动选中那一家。
  不放在 `Application.onCreate`，是因为 Key 存在 LSPosed 远程偏好里，那次读取可能晚于首帧——
  启动时初始化会把错的 Key 烘进整个进程，而且 SDK 只在启动时读一次，改了也没救。
- **坐标只在各自的 canvas 里换算**，选点页与配置层全程 WGS-84。喂错基准的表现是
  「地图看着正常、图钉偏几百米」，不报任何错。
- **修掉一个真机踩到的崩溃**（这次会话的主要收获）：百度 8.x 的 `setAgreePrivacy` 要求
  `ApplicationContext`，传 Activity 会让初始化失败；而选点页照样按「有凭据」去建 `MapView`，
  构造函数抛出的异常落在 **Compose 的变更应用阶段**——那里没有「降级」这回事，进程被当场
  SIGKILL，没有弹窗、没有一条堆栈指向地图。现在 `MapSdkBootstrap` 内部归一化为
  application context，并且**每个 SDK 的视图构造都过 `buildMapView`**：构造失败降级成一条
  可读的错误面板 + 「打开设置」，而不是杀掉 App。
- 设置页从「三个平台一起铺开」改为**跟随所选平台**：只出现一个 Key 输入框、一家的申请步骤。
  内置凭据的切换也只在百度时出现——另外两家没有内置 Key，摆在那里是个空选择。
  （**这段描述属于 1.4.0。** 那个切换连同内置 Key 本身已在 1.4.1 整条删除，见本节开头。）
- 想验证三家里谁真的能出图，看日志 tag `FakeLoc/MapSdk` 的
  `<provider> map SDK ready (key=built-in|custom)`；失败则是 `map SDK failed to start` 带原因。

**1.3.2 相对 1.3.1 的改动**（真机实测揪出的**真正根因**）：

- **修掉地址 hook 里一个把关键路径整个排除掉的判断**。1.3.0 写的是：

  ```kotlin
  if (!cfg.syncAddress || original !is String) return original
  ```

  `original` 是 SDK 那次调用的原始返回值。建行生活的 `BDLocation` 对
  `getCity()` / `getProvince()` / `getDistrict()` / `getAddrStr()` **全部返回 `null`** ——
  而 `null !is String` 为真，于是在**最需要替换的场景**（SDK 根本没有地址）里原样放行。
  实测日志：`getCity="null"`，假城市解析了、存了、记了日志，然后被原封不动放过去，
  App 继续用自己缓存的厦门。现在条件改成「`null` 或 `String` 都可以改写」：

  ```kotlin
  val answer = if (cfg.syncAddress && (original == null || original is String)) {
      cfg.addressFor(name)
  } else null
  ```

  非 `String` 且非 `null`（int / long 那类 getter）仍然放行，否则就是自己制造崩溃。
- 地址读取日志补上后半截：`getCity="null" — answering "吉林市"`。原来只打 SDK 的原始值，
  而在这个通道存在的场景里那个值恰好是 `null`，所以旧日志读起来像「挂上了，但什么也没做」，
  完全看不出替换有没有发生。半行日志让这个 bug 多活了一版。
- 版本 versionCode 10 / versionName 1.3.2（同一签名，可直接覆盖安装）

**真机验证（建行生活，2026-09-29）**：锚点 `43.134239, 127.155965`（吉林省桦甸市）。

| 环节 | 结果 |
|---|---|
| 选点页解析地址 | `吉林省吉林市桦甸市`（`city` = `吉林市`，正是 H5 查表要的全称） |
| 模块自检行 | `syncAddress=true … fakeCity=吉林市` |
| 目标 App 读取地址 | `getCity="null" — answering "吉林市"`、`getProvince="null" — answering "吉林省"`、`getAddrStr="null" — answering "吉林省吉林市桦甸市"` |
| 目标 App 行为 | 首页弹出「定位显示您在"吉林"　[切换到吉林]」 |
| 点「切换到吉林」后 | **首页左上角「厦门 轻雾 29°C」→「吉林 晴 16°C」** |
| 每秒 JSON 异常 | 0（1.3.1 修复前为 1 次/秒） |

**1.3.1 相对 1.3.0 的改动**（真机调试时发现，都必须修）：

- **修掉百度推送通道的一个参数错误**：`BDLocation(String)` 是**解析 JSON** 的构造器，不是
  provider 名构造器（高德那个 `AMapLocation(String)` 才是）。原来传的是 `"gps"`，百度 SDK 内部
  于是执行 `new JSONObject("gps")` 并抛
  `Value gps of type java.lang.String cannot be converted to JSONObject` —— 实测在建行生活进程里
  **每秒打印一次**（SDK 自己吞掉了异常，所以推送本身还能用，但目标 App 的日志被刷、且我们的推送
  依赖对方容错）。现在优先用无参构造器，只有在没有无参构造器时才退回 JSON 构造器并传 `"{}"`；
  同时 `deliver()` 不再让任何异常穿出派发循环。
- **逆地理增加系统 `Geocoder` 兜底**：百度 SDK 的逆地理请求被**静默拒绝**时（见下条），原来界面会
  一直转圈、确认后存不下任何地址。现在给 SDK 3 s 窗口，超时未答就用平台 `Geocoder` 反查
  （字段映射：`adminArea`→省、`locality`→市、`subLocality`→区）。两条路都拿不到才报「未解析」。
  迟到的 SDK 回调不会覆盖已由兜底写入的结果。
- 版本 versionCode 9 / versionName 1.3.1（同一签名，可直接覆盖安装）

**已知的环境问题（不是代码问题）**：本机百度 AK 的**搜索/逆地理服务被禁用**，选点页拿不到地址。

```
E/baidumapsdk: Authentication Error
  sha1;package:CC:E4:19:E3:99:30:2D:40:2F:23:78:A5:7B:3C:79:85:E9:8B:66:E2;com.amo.fakeloc
  key:<你的 AK>          ← 这里原本是当时的 AK 明文，已涂掉
  errorcode: 240    msg: APP 服务被禁用
E/SearchRequest: toUrlString get authtoken failed
E/BaseSearch: The sendurl is: null
```

签名与包名和申请 AK 时完全一致，**地图瓦片也正常渲染**（能画出「车辙沟村村委会」这类细节），
所以 AK 本身有效，只是**该 AK 未被授权「搜索 / 逆地理编码」这一路服务**。两种解法：
在百度地图开放平台给这个 AK 勾上对应服务，或另申请一个 Web 服务 AK。1.3.1 的平台 `Geocoder`
兜底就是为这种情况准备的。

**1.3.0 相对 1.2.2 的改动**（针对「坐标变了、首页城市名不变」，实测于建行生活）：

- 新增 `PickedAddress`：把选点页**本来就在调**的百度逆地理结果留下来（province / city / district /
  adcode / cityCode / 一句话地址）。1.2.x 只取了 `result.address` 一个字符串就丢了其余字段
- 三家 SDK 的地址 getter 从「记日志后放行」改成「返回伪造值」：`getCity()` → 假城市、`getProvince()`
  → 假省、`getAdCode()` → 假行政区划码，由 `SpoofConfig.addressFor(getter)` 按 getter 名映射
- 新增界面开关「同步伪造城市/地址」（`sync_address`，**默认开**），关掉即退回 1.2.2 的纯观测模式
- 地址字段从 8 个扁平的 `addr*` 字段随坐标一起持久化（`ConfigCodec` 新增 9 个 key），
  换坐标（手动输入 / 剪贴板 / 定位到真实位置）时会**丢弃旧地址**，避免"坐标在吉林、城市写着北京"
- 首页 `StatusHeroCard` 直接显示已解析出的城市名，一眼就能确认地址有没有跟着走；
  未解析时显示「地址未解析（请先在地图选点）」
- 自检行新增 `syncAddress=` 与 `fakeCity=` 两列
- 版本 versionCode 8 / versionName 1.3.0（同一签名，可直接覆盖安装）

**1.2.2 相对 1.2.1 的改动**（针对「坐标变了、城市名/地址没变」，实测于建行生活）：

- `MapSdkHooks` 新增地址类 getter 的 hook：高德 17 个、百度 11 个、腾讯 11 个，覆盖
  `getCity` / `getProvince` / `getDistrict` / `getAddress` / `getAddrStr` / `getPoiName` 等
- 每个地址字段**首次被读取时打一行日志**（`amap address field read: getCity="北京市"`）。
  这行回答的是唯一无法从别处推断的问题：App 到底有没有向 SDK 要地址
- 新增界面开关「清空 SDK 地址字段」（`blank_address`，**默认关**）：打开后地址 getter 返回空串，
  赌 App 退回去用（已被改写的）坐标自己反查。只对 `String` 字段生效，非字符串字段原样放行
  （**1.3.0 已废弃**：实测空串会让 App 死守旧城市名，改为返回逆地理得到真实地址串）
- 安装时打一行 `amap address fields hooked (N getter(s))`，把"钩子没装上"与"装上了但没人读"分开
- 修掉一个一直存在的子进程匹配漏洞：`生效范围` 名单里写 `com.example.app` 时，
  `com.example.app:remote` 这类子进程此前**永远匹配不上**——框架报的是进程名而不是包名，
  而 `appliesTo` 拿进程名去查包名列表。后果是子进程被判定为"不在名单里"，静默什么都不做。
  现在启动时把包名剥离一次，匹配按「进程名 → 包名」两级查，且仍然零分配
- 版本 versionCode 7 / versionName 1.2.2（同一签名，可直接覆盖安装）

**1.2.1 相对 1.2.0 的改动**（纯诊断，不改任何行为）：

- 每个进程启动时多打一行自检：`inScope= playing= mapSdkCompat= strictSources= antiMock= scopeAll=
  targets= anchor=…`。**通道 armed 了却不开火**与**通道根本没挂上**在日志里原本长得一样
- `inScope=false` 时额外补一条 **warn**（不是 info），日志级别调到 Warn 也看得见
- 版本 versionCode 6 / versionName 1.2.1

**1.2.0 相对 1.1.2 的改动**（针对「内嵌定位 SDK 的 App 改不动」，如建行生活）：

- 新增 `MapSdkHooks`：直接接管**高德 / 百度 / 腾讯**三家定位 SDK 的内部通道
- 新增 `DynamicLoaderHooks`：hook `ClassLoader.loadClass`，SDK 在独立 `DexClassLoader` 里加载时也能把钩子装到正确的 loader 上
- 新增 `SdkDispatch`：捕获注册进去的 listener 并按 1 Hz 主动派发，解决「订阅早于伪装开启 → 地图冻在真实位置」
- 新增 `Coords.Datum` 与 `HookState.applyTo(location, datum)`：按通道分别给 GCJ-02 / BD-09 / WGS-84
- 新增界面开关「接管地图 SDK」（`map_sdk_compat`，默认开）
- 版本 versionCode 5 / versionName 1.2.0（与 1.1.x 同一签名，可直接覆盖安装）

**1.1.2 相对 1.1.1 的改动**（针对「一开始在模拟位置，过一会跳回真实位置」）：

- 新增 `SourceLockHooks`：切断 WiFi 扫描 / 基站 / 原始 GNSS（NMEA、测量值、导航电文），默认开
- 新增 `LocationPayload`：统一原地覆写四种 payload 形态，且写的是**真实字段**，覆盖 native 直读
- 新增 `Diag`：每条投递路径一次性打点，只打「与锚点的距离差」，用来区分三种成因
- 新增界面开关「锁定定位源」，可随时关掉
- 版本 versionCode 4 / versionName 1.1.2（同一签名，可直接覆盖安装）

**编译**：`assembleRelease` 在本机通过（Gradle 8.9 + AGP 8.7.3 + JDK 17），**0 错误**。

**APK 核验**（对 1.3.2 release 产物，用 `aapt2` / `apksigner` / 直接读 zip）：

- 包名 `com.amo.fakeloc`（当时的包名；**v1.6.0 起已改为 `io.github.cobylinweiqi.fakeloc`**，见第十三节），
  versionCode 10 / versionName 1.3.2
- compileSdk 36、targetSdk 35，`native-code: arm64-v8a, armeabi-v7a`
- 权限集与预期完全一致（定位 3 项 + 网络 3 项 + `QUERY_ALL_PACKAGES`）
- `com.baidu.lbsapi.API_KEY` 已在 manifest 中（v1.4.1 起整条移除，改由 `setApiKey` 运行时传入）
- `android:extractNativeLibs=true`，10 个 `.so` 全部压缩存储
- `META-INF/xposed/{module.prop,java_init.list,scope.list}` 三件套齐全，`minApiVersion=targetApiVersion=101`、`staticScope=false`
- 新资源 `adv_sync_address` / `addr_unresolved` 已打进 `resources.arsc`，旧 `adv_blank_address` 计数为 0
- 签名 `CN=Android Debug`，SHA-1 `cce419e399302d402f2378a57b3c7985e98b66e2`（与申请 AK 时填的一致）
- 产物 SHA-1 `3b72f7cd1af9addc7a7bc12ba52237af4c4f47bb`（61,530,489 字节）

**静态检查**（30 个 Kotlin 文件，6760 行）：

- 括号 / 中括号 / 圆括号配平（**剔除注释与字符串字面量后**）：**0 处不平衡**
  （朴素计数在 `Geo.kt` 上会报 2 处不平——那是 KDoc 里写的 `getCurrentLocation(provider, …)`，
  不是真问题；这类误报必须靠剔注释的版本才能排除）
- 关键声明的出现次数逐个核对（`private fun installAddressReads(` 恰好 1 次、三处调用各 1 次……）：
  **全部相符**。这一项是踩出来的——本机改文件时出现过同一处插入被落两次，而
  `mergeReleaseResources` 只会在打包阶段报 `Found item String/xxx more than one time`
- `R.string.*` 引用：76 处引用，**0 处未定义**；中英文资源 key 完全一致（各 82 条），
  且**逐条查过重复**（用 set 比对会吞掉重复项，资源合并器不会——上一版就是这样漏过去的）
- 包名声明与目录结构：**全部一致**
- 内部 import 符号可解析：**0 处悬空**
- 百度 SDK 用到的每一个类与方法签名都用 `javap` 对着 AAR 核过；高德与腾讯同样核到了真实 AAR
  （高德 11.3.000、百度 locSDK 9.7.0、腾讯 8.7.5.1），三家的 `AMapLocation` / `BDLocation` /
  `TencentLocation` 的读写路径都按反编译结果对齐
- locale 相关的格式化：全部显式锁定 `Locale.US`（否则德语/俄语环境下坐标会显示成 `39,908700`，
  而这个字符串会被喂回编辑器，`toDoubleOrNull()` 解析不了）

**设备上的情况**（用户实测，按时间顺序）：

1. 高德地图在 1.1.x 上已能正常改动定位
2. 建行生活（`com.ccb.longjiLife`）在 1.1.x 上改不动——它走的是内嵌定位 SDK 的通道，平台层 hook
   对它完全透明
3. 1.2.0 之后：**周边网点按虚拟定位出结果了**，说明坐标通道确实接管成功，多进程也没问题
   （`com.ccb.longjiLife`、`:CoreService`、`:CMCCCoreService` 三个进程都进了模块）
4. 但首页**左上角的城市名纹丝不动**，把锚点换到另一个城市也不动

第 4 点有两种成因，而且**从外面看一模一样**，这正是 1.2.2 那套地址 hook 存在的理由：

| 成因 | 日志里的样子 | 能不能修 |
|---|---|---|
| App 读的是 SDK 的地址字段（我们只改了坐标，没改字符串） | `amap address field read: getCity="…" — answering "吉林市"` | 能——1.3.0 起在选点页解析地址后，`sync_address`（默认开）直接让 getCity 返回假城市 |
| 那个城市不是设备定位给的（服务端按 IP 判 / 账号归属地 / 本地缓存） | 只有 `... address fields hooked (N)` | 修不了——不是模块够得着的范围 |

**还没有做**：hook 在真机上实际命中行的验证、地图渲染的验证。这两项需要一台 root 手机，并且要看
`FakeLoc/State`、`FakeLoc/MapSdk`、`FakeLoc/Loader` 三个 tag 的日志才能确认。

---

## 十四、许可与署名

- **本项目按 MIT 发布**（`LICENSE`，Copyright (c) 2026 Cobylinweiqi）。**代码由 AI 编写、
  人负责需求与验证** —— 评估它能否用于你的场景时，请把这一点算进去。
- 本项目代码为重新实现，**未逐字复制**任何参考项目的源码。所借鉴的是技术路径与 API 用法，
  在相关函数上已用注释标注来源。
- [XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation) 为 **MIT** 许可。
- [HideMockLocation](https://github.com/auag0/HideMockLocation) 仓库中**未发现 LICENSE 文件**，
  默认即保留全部权利。本项目只借用了其 README 中公开列出的 hook 清单这一思路
  （`isFromMockProvider` / `isMock` / AppOps / SettingsProvider 这几处），代码为独立实现。
  若要公开分发，建议自行确认该项目的授权情况。
- [libxposed](https://github.com/libxposed/api) 为 Apache-2.0。
- **腾讯位置服务**（[lbs.qq.com](https://lbs.qq.com)）的使用受其服务条款约束，包括免费额度与商用
  授权要求。地图页上的 Logo 与版权信息**不要遮挡或移除**——那是使用条款的要求，不是装饰。
  免费额度够个人使用。
- **百度地图与高德地图的 SDK 自 1.5.0 起不再随本包分发**（见第五节），因此不涉及它们的
  分发授权。但请注意：本模块的**注入通道**仍然会在运行时操作目标 App *自己* 安装的百度 / 高德
  定位 SDK 的类。那是目标 App 与那些平台之间的授权关系，与本项目无关，也不构成对本项目的授权豁免。

## 免责声明

**一、使用边界。** 本项目仅供**开发调试、自动化测试与安全研究**，且只应装在**你自己拥有、并被
授权调试的设备**上。用它做考勤打卡作弊、刷单、骗取补贴或优惠、绕过金融与支付类应用的风控、
伪造行程等，可能违反当地法律以及第三方应用的服务条款 —— **由此产生的一切后果由使用者承担**，
与作者及贡献者无关。

**二、账号与数据风险。** 本模块 hook 的是系统与第三方 App 的定位读取路径，目标 App 存在反作弊
检测的可能：**轻则功能异常，重则账号被限制或封禁**，在银行、支付、证券、保险类 App 上尤其不建议
使用。另外，伪造坐标并不改变基站、Wi-Fi、传感器等其它定位来源，某些应用据此就能判断出异常。

**三、设备风险。** 使用前提是设备已 root 并装有支持 libxposed API 101 的 LSPosed。**root 会
降低设备的整体安全等级**（部分应用会因此拒绝启动或限制功能），刷机、框架与本模块的兼容问题
也可能造成系统不稳定、数据损坏乃至无法开机。请自行做好备份。

**四、按「现状」提供。** 本项目不附带任何明示或默示的担保，包括可用性、正确性与特定用途的
适用性。**代码由 AI 编写、人负责需求与验证**（见第十四节），请在使用前自行评估。作者不对任何
设备损坏、数据丢失、账号封禁、法律纠纷或其它损失负责。

以上条款不排除或限制依法不能排除或限制的责任。
