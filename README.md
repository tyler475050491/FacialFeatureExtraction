# 人脸特征提取 APP（FacialFeatureExtraction）

参考 `E:\WorkBuddy\ConsumerMachineProject`（人脸识别消费机）的**旷视算法内核**与**接口协议**实现的独立 APP。
定时从服务器拉取同步照片，多线程提取人脸特征（照片与特征均不落盘），将提取结果回报服务器，并在界面上展示设备状态与历史任务。

## 零、文档索引（二次开发请从这里进入）

| 文档 | 位置 |
| --- | --- |
| **二次开发文档（换电脑必读）** | `doc/二次开发文档.md` |
| **待办与续接** | `doc/待办与续接.md` |
| 历史会话记录 | `doc/会话记录/` |
| 接口对接文档（发服务端，v1.1） | `docs/人脸特征提取APP接口对接文档v1.1.docx` |
| 对接流程图 | `docs/接口对接流程图.png` |
| 服务端下发模式说明 | `docs/消费服务端说明.txt` |

## 一、工程结构

```
FacialFeatureExtraction/
├── app/
│   ├── libs/pipeline-orig.jar            # 旷视 FaceCore 算法封装（取自消费机项目，含 com.hpt.facecore/facepass、mcv.facepass）
│   ├── src/main/jniLibs/armeabi-v7a/     # 算法原生库（libfacepassandroid/libfacepasspro/libfplicense/librknnrt 等）
│   └── src/main/java/com/hpt/faceextraction/
│       ├── App.java                      # 初始化旷视 FaceCore（setFaceConfig → sdkInit → certification）
│       ├── Constants.java                # 设备 SN 读取
│       ├── Config.java                   # 服务器地址/机号/轮询间隔（SharedPreferences）
│       ├── TimeSyncTask.java             # 心跳 + 校时（POST hpt/v1/CurrentTime）
│       ├── FaceSyncTask.java             # 同步任务调度（GET/POST /hpt/v2/Accounts/FaceSyncTasks）
│       ├── FaceExtractor.java            # 单张照片：下载→解码→多人脸检测→旷视特征提取
│       ├── NetMonitor.java               # 以太网/WiFi 状态、设备 IP
│       ├── TaskHistoryDb.java            # 历史任务 SQLite 存储
│       └── MainActivity.java             # 界面
```

## 二、接口协议（与消费机项目一致）

### 统一协议头（所有接口请求/上报/照片下载均携带）

| Header | 值 | 说明 |
| --- | --- | --- |
| `Client-ID` | 机号 | 与 URL 参数 clientNos 一致 |
| `sn` | 设备SN码 | 取 ro.serialno 等系统序列号 |
| `mac` | 设备网卡地址 | 优先以太网 eth0，其次 wlan0，格式 `AA:BB:CC:DD:EE:FF` |

### 1. GET `/hpt/v2/Accounts/FaceSyncTasks?clientNos={机号}&size=200（默认，可在配置中修改）`

定时（默认 60 秒，可设置）拉取待同步照片。服务端**只需返回照片地址 faceDataUrl**（相对路径或绝对路径均可）。
可选返回**任务编号 `TaskIdentifier`**（任意层级均可识别，响应不带时历史记录该列显示 "-"）：
APP 会将其写入历史记录，并支持在界面上按任务编号查询。
APP 解析时做了多格式兼容：

```json
// 简化格式（推荐，只需 faceDataUrl）
{ "models": [ { "faceDataUrl": "Accounts/face/1001.jpg" } ] }
// 或带 accountID 便于服务端对账
{ "models": [ { "accountID": "1001", "faceDataUrl": "http://192.168.1.1/face/1001.jpg" } ] }
// 也兼容消费机完整格式
{ "models": [ { "clientID": 1, "accountInfos": [ { "accountID": "1001", "faceDataUrl": "..." } ] } ] }
```

相对路径默认按 `{服务器地址}/hpt/v2/{faceDataUrl}` 拼接（与消费机项目一致）；以 `/` 开头拼到服务器根；`http(s)://` 开头直接使用。

### 2. POST `/hpt/v2/Accounts/FaceSyncTasks`（上报提取结果）

每批任务处理完后批量上报，body 结构与消费机 `FaceLoadResultBean` 兼容：

```json
{
  "clientID": "1",
  "TaskIdentifier": "1793157608810",
  "algoVersion": "KS V1.3.3.1",
  "result": "true",
  "models": [
    { "accountID": "1001", "clientID": "1", "result": "true",  "status": 0, "message": "", "updateTime": "2026-10-28 11:20:08",
      "faceFeature": "<Base64特征值>", "featureMD5": "<特征值Base64串的MD5，32位小写>" },
    { "accountID": "1002", "clientID": "1", "result": "false", "status": 4, "message": "照片出现多个人脸(2张)", "updateTime": "2026-10-28 11:20:09" }
  ]
}
```

**status 状态码约定：**

| status | 含义 |
|--------|------|
| 0 | 成功 |
| 1 | 下载失败 |
| 2 | 未检测到人脸 |
| 3 | 照片不合格（特征无效/质量差/提取异常） |
| 4 | 照片出现多个人脸 |
| 5 | 图片解码失败 |
| 6 | 照片地址为空 |
| 9 | 其他异常 |

成功时附带 `faceFeature`（Base64 特征值，供服务端下发其他设备复用）与 `featureMD5`（该 Base64 串的 MD5，32 位小写，可校验完整性），
根层附带 `algoVersion`（算法版本号）；**本地不存储照片、不存储特征**。

### 3. POST `/hpt/v1/CurrentTime`（心跳 + 同步触发，APP 不再定时轮询同步接口）

心跳按可配置间隔（默认 15 秒，最小 5 秒）上报，请求体 `{"ICCID":"","ICCID2":""}`（与消费机一致）。响应：

```json
{ "Status": "100", "Msg": "", "UploadImage": true, "Time": "202109281205422" }
```

- `Status=100`：服务器接收并允许连接 → 设备**在线**（界面绿点）；请求失败或非 100 → **离线**（红点）。
- `Time` 为服务器时间 `yyyyMMddHHmmss`+1 位校验位；设备时间偏差 >10 秒时调用 `SystemClock.setCurrentTimeMillis` 校时
  （需平台签名/系统权限，无权限时自动改为「偏差修正显示」）。
- **同步触发**：响应中的同步标志（主字段 `UploadImage`，兼容 `SyncFace`/`IsSyncFace` 等；
  接受布尔 true 或字符串 "true"/"1"/"是"）：
  - 返回**是** → 立即调用 GET `/hpt/v2/Accounts/FaceSyncTasks?clientNos={机号}&size=200（默认，可在配置中修改）` 并处理；
  - 返回**否** → 静默等待下一次心跳；
  - 若上一批任务**正在处理中** → 本次触发静默跳过，等任务完成后的下一次心跳再判断。

## 三、界面说明

- **顶部**：实时时间（按服务器时间修正）、设备 SN、机号、算法版本（旷视 FaceCore）、APP 版本、设备 IP、
  通讯状态圆点（绿=通讯正常 / 红=异常）、网络标签 + 图标（以太网 ⇄ WiFi 随连接模式切换；无任何网络时显示红色以太网图标 + 红叉）；
  另有「立即同步」「参数设置」（服务器地址 / 机号 / 心跳间隔 / 人脸分数阈值 / 修改 Web 登录密码）按钮。

### Web 配置端（端口 8090）

参考消费机项目的 web 端配置方式：浏览器访问 **http://设备IP:8090** 进入登录界面，
输入密码（默认 `123456`，可修改）登录后即可配置参数：

| 配置项 | 说明 |
| --- | --- |
| 服务器地址 | 同步/上报/心跳的服务器根地址 |
| 设备机号 (clientNos) | 同步任务按机号拉取 |
| 同步轮询间隔（秒） | 最小 5 秒 |
| **人脸分数阈值 (0-100)** | 旷视 detectFaces 检测的人脸分数（lmkScore×100）低于该值判定「照片不合格」，默认 70；设为 0 可关闭分数判断 |
| 登录密码 | 留空不修改 |

保存结果以**弹窗提示**（成功/失败），页面不跳转；保存后立即生效，同步任务自动重启。登录态通过 Cookie token 保持，设备重启后需重新登录。

### 人脸分数判断

旷视算法提取特征前，先用 `detectFaces` 接口对照片做人脸检测：
1. 人脸数量 > 1 → 上报「照片出现多个人脸」；
2. 最高人脸分数 < 阈值（可在参数设置/网页端配置，默认 70）→ 上报「照片不合格-人脸分数 XX 低于阈值 YY」。
- **中部**：当前任务状态（照片数量、处理进度、成功数、失败数；无任务显示"空闲"）、上次任务状态（处理时间、照片数、成功数、失败数、耗时）；
  其下为设备资源状态行（每 2 秒刷新）：CPU 占用率、内存占用（已用/总量）、存储剩余（data 分区）。
- **底部**：历史任务列表（序号、处理时间、照片数量、成功数量、失败数量、**整体耗时**），数据存 SQLite（`task_history.db`），
  支持开始/结束时间（日期选择）+ 查询按钮按时间范围检索。

## 四、构建与安装

```bash
# 本机已配置：Gradle 8.7（E:\WorkBuddy\FaceConsumption\reverse_tools\gradle-8.7）
#             JDK 17（local.properties 中 org.gradle.java.home 已指向）
cd E:\WorkBuddy\FacialFeatureExtraction
E:\WorkBuddy\FaceConsumption\reverse_tools\gradle-8.7\bin\gradle assembleDebug
# APK 输出：app\build\outputs\apk\debug\app-debug.apk
```

- `minSdk 28 / targetSdk 30 / armeabi-v7a`，与消费机设备（RK 板卡）一致。
- 运行期依赖：RxJava3 + kotlin-stdlib（FacePassCore 需要，已在 build.gradle 中）；
  `assets/models/` 下 8 个旷视模型文件；`jniLibs` 下全部旷视 so（含 libacendpoint.so）；
  `pipeline-orig.jar` 已移除 `com/hpt/facepass/R*` 类，资源 ID 由源码
  `com/hpt/facepass/R.java` 提供（与 APK 实际 ID 对齐，勿随意增删 strings.xml 中 mcv_* 之前的资源）。
- Manifest 声明了 `sharedUserId="android.uid.system"`，使用消费机同款平台签名
  （`FaceConsumption/签名文件/platformrk.jks`），否则无法校时/读取 SN。
  若在普通设备调试请去掉 sharedUserId 并改用普通签名。
- 首次运行进入「参数设置」配置正确的服务器地址与机号；也可在电脑浏览器打开 `http://设备IP:8090` 配置。

## 五、运行时行为

1. 心跳每 N 秒一次（默认 15，可配置）→ 决定通讯状态并校时。
2. 同步由心跳触发：心跳响应同步标志为"是"时立即 GET 同步任务；为"否"静默等待；
   上一批任务处理中时跳过本次触发，等任务完成后的下一次心跳再判断（APP 不再定时轮询同步接口）。
3. 每批照片由 4 个工作线程并发「下载 → 解码（≤1000px）→ 多人脸检测 → detectBitmap 提特征」。
4. 批次完成后批量 POST 结果并写入 SQLite 历史；照片与特征全程只在内存中。
