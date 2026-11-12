# -*- coding: utf-8 -*-
"""生成《人脸特征提取 APP 接口对接文档》docx"""
import os
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = r"E:\WorkBuddy\FacialFeatureExtraction\docs"
os.makedirs(OUT_DIR, exist_ok=True)
OUT = os.path.join(OUT_DIR, "人脸特征提取APP接口对接文档v1.1.docx")

doc = Document()

# ---------- 页面与默认字体 ----------
sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21), Cm(29.7)
sec.left_margin = sec.right_margin = Cm(2.2)
sec.top_margin = sec.bottom_margin = Cm(2.0)

style = doc.styles["Normal"]
style.font.name = "Calibri"
style.font.size = Pt(10.5)
style.element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
style.paragraph_format.space_after = Pt(4)
style.paragraph_format.line_spacing = 1.25


def set_cn(run, size=10.5, bold=False, color=None, east="微软雅黑"):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.name = "Calibri"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), east)
    if color:
        run.font.color.rgb = RGBColor(*color)
    return run


def h1(text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(14)
    p.paragraph_format.space_after = Pt(6)
    set_cn(p.add_run(text), 15, True, (16, 60, 110))
    return p


def h2(text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(8)
    p.paragraph_format.space_after = Pt(4)
    set_cn(p.add_run(text), 12, True, (30, 90, 150))
    return p


def para(text, size=10.5, bold=False, color=None, indent=0):
    p = doc.add_paragraph()
    if indent:
        p.paragraph_format.left_indent = Cm(indent)
    set_cn(p.add_run(text), size, bold, color)
    return p


def bullet(text, bold_head=None):
    p = doc.add_paragraph(style="List Bullet")
    if bold_head:
        set_cn(p.add_run(bold_head), 10.5, True)
    set_cn(p.add_run(text), 10.5)
    return p


def shade(cell, hexcolor):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), hexcolor)
    tcPr.append(shd)


def table(headers, rows, widths=None, header_fill="1F6FB5", mono_cols=()):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, htxt in enumerate(headers):
        c = t.rows[0].cells[i]
        c.text = ""
        p = c.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        set_cn(p.add_run(htxt), 10, True, (255, 255, 255))
        shade(c, header_fill)
    for r in rows:
        cells = t.add_row().cells
        for i, v in enumerate(r):
            cells[i].text = ""
            p = cells[i].paragraphs[0]
            run = p.add_run(str(v))
            if i in mono_cols:
                run.font.name = "Consolas"
                run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
                run.font.size = Pt(9.5)
            else:
                set_cn(run, 9.5)
    if widths:
        for row in t.rows:
            for i, w in enumerate(widths):
                row.cells[i].width = Cm(w)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return t


def code(lines):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(0.6)
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(6)
    pPr = p._p.get_or_add_pPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), "F2F5F8")
    pPr.append(shd)
    for i, ln in enumerate(lines):
        r = p.add_run(ln)
        r.font.name = "Consolas"
        r._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
        r.font.size = Pt(9)
        if i < len(lines) - 1:
            r.add_break()
    return p


# ================= 封面标题 =================
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_before = Pt(10)
set_cn(p.add_run("人脸特征提取 APP 接口对接文档"), 20, True, (16, 60, 110))
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_cn(p.add_run("（供服务端开发按协议对接 API 使用）"), 12, False, (90, 98, 108))
info = table(["项目", "内容"],
             [["文档版本", "v1.0（2026-10-28）"],
              ["适用对象", "人脸特征提取 APP（Android，包名 com.hpt.faceextraction）的服务端开发"],
              ["通信方式", "HTTP + JSON（UTF-8），内网部署（本文示例：http://192.168.0.147:8088）"],
              ["接口总数", "3 个：心跳 1 个 + 同步任务拉取/上报共用路径 2 个"]],
             widths=[3.5, 13], mono_cols=())

# ================= 1 概述 =================
h1("1  概述与整体机制")
para("APP 部署在人脸识别设备上，负责从服务端拉取待同步的人脸照片、在设备本地用旷视算法提取人脸特征，"
     "并把提取结果（成功/失败原因 + 特征值）回报给服务端。照片与特征均只在内存中处理，不在设备本地落盘。")
bullet("APP 不会定时轮询同步接口。同步任务由心跳响应中的 UploadImage 标志触发，服务端不下发该标志 APP 绝不会主动拉取。", "触发机制：")
bullet("每 N 秒（默认 15，可配置，最小 5）一次。服务端据此判断设备在线状态（建议超过 3 倍间隔无心跳即判定离线）。", "心跳节奏：")
bullet("每批照片由 4 个工作线程并发处理，单批拉取条数 size 可配置（默认 200）。", "处理能力：")
bullet("所有请求/上报/照片下载统一携带协议头 Client-ID、sn、mac（见 1.3）。", "设备身份：")

h2("1.1  接口清单")
table(["#", "方法", "路径", "用途", "调用方向"],
      [["1", "POST", "/hpt/v1/CurrentTime", "心跳 + 校时 + 同步触发标志", "设备 → 服务端"],
       ["2", "GET", "/hpt/v2/Accounts/FaceSyncTasks", "拉取待同步照片列表", "设备 → 服务端"],
       ["3", "POST", "/hpt/v2/Accounts/FaceSyncTasks", "批量上报提取结果", "设备 → 服务端"]],
      widths=[0.9, 1.8, 6.3, 5.2, 2.6], mono_cols=(2,))

h2("1.2  URL 拼接规则")
para("设备端配置一个服务器根地址（如 http://192.168.0.147:8088/，自动保证以 / 结尾）。接口路径按以下规则拼接：")
table(["路径写法", "拼接结果（示例）", "说明"],
      [["/hpt/v2/xxx", "http://192.168.0.147:8088/hpt/v2/xxx", "以 / 开头拼到根（推荐，本文统一用这种）"],
       ["hpt/v1/xxx", "http://192.168.0.147:8088/hpt/v1/xxx", "不以 / 开头，追加在根地址后"],
       ["http(s):// 完整地址", "原样使用", "一般不用"]],
      widths=[3.6, 8.2, 5.0], mono_cols=(0, 1))

h2("1.3  统一协议头（所有请求必须校验）")
para("APP 通过 OkHttp 全局拦截器为所有请求（含照片下载）附加以下 3 个协议头，服务端可用于设备身份识别与对账：")
table(["Header", "示例值", "说明"],
      [["Client-ID", "0001", "设备机号，与拉取接口的 query 参数 clientNos 一致"],
       ["sn", "632101048645E58ECB24", "设备 SN 码（系统序列号），设备唯一标识，建议作为对账主键之一"],
       ["mac", "86:45:E5:8E:CB:24", "设备网卡地址，冒号分隔大写；优先以太网 eth0，其次 WiFi"]],
      widths=[2.6, 5.6, 8.6], mono_cols=(0, 1))
para("注意：3 个头在取不到值时不会发送（正常设备均有值）。服务端如需拒绝非法设备，直接返回非 200 即可，"
     "APP 视为通讯失败并显示离线。", 9.5, color=(120, 60, 20))

h2("1.4  通用约定")
table(["项", "约定"],
      [["编码 / Content-Type", "UTF-8；POST 请求 Content-Type: application/json; charset=utf-8"],
       ["超时", "连接 10 秒 / 读取 30 秒；超时视为通讯失败，APP 显示离线，等待下一轮心跳重试"],
       ["时间格式", "① 上报字段 updateTime：yyyy-MM-dd HH:mm:ss（如 2026-10-28 11:20:08）；"
                    "② 心跳响应 Time：yyyyMMddHHmmss + 1 位校验位共 15 位（如 202109281205422，APP 只取前 14 位）"],
       ["响应要求", "HTTP 200 即成功；APP 仅解析约定字段，多余字段忽略（向下兼容）"],
       ["照片文件", "建议单张 ≤ 1MB，jpg/png；超过 1000px 的大图 APP 会等比缩小到 1000px 内再提取（影响精度，建议服务端控制）"]],
      widths=[3.6, 13.2])

# ================= 2 心跳 =================
h1("2  接口一：心跳　POST /hpt/v1/CurrentTime")
para("作用：① 维持设备在线状态；② 下发服务器时间供设备校时；③ 下发是否需要同步人脸的标志（本协议的核心触发点）。", bold=True)

h2("2.1  请求")
code(["POST /hpt/v1/CurrentTime HTTP/1.1",
      "Host: 192.168.0.147:8088",
      "Content-Type: application/json; charset=utf-8",
      "Client-ID: 0001",
      "sn: 632101048645E58ECB24",
      "mac: 86:45:E5:8E:CB:24",
      "",
      "{\"ICCID\":\"\",\"ICCID2\":\"\"}"])
table(["字段", "类型", "必填", "说明"],
      [["ICCID", "string", "是", "固定空字符串（兼容消费机协议，保留字段）"],
       ["ICCID2", "string", "是", "固定空字符串（同上）"]],
      widths=[2.6, 1.8, 1.5, 10.9], mono_cols=(0,))

h2("2.2  响应")
code(["HTTP/1.1 200 OK",
      "Content-Type: application/json",
      "",
      "{\"Status\":\"100\",\"Msg\":\"\",\"Time\":\"202610281120082\",\"UploadImage\":true}"])
table(["字段", "类型", "必返", "说明"],
      [["Status", "string/int", "是", "设备在线判定依据。必须为 \"100\"（数字 100 亦可）才判定在线；"
                                    "其他值、字段缺失或请求超时均判定离线"],
       ["Msg", "string", "否", "附加信息，APP 仅在离线时打日志，可空"],
       ["Time", "string", "建议", "服务器当前时间，yyyyMMddHHmmss + 1 位校验位（共 15 位）；"
                                "APP 取前 14 位、按东八区解析；与设备时差超过 10 秒时自动校时（设备有系统权限）"],
       ["UploadImage", "bool/string", "否", "是否需要同步人脸（见 2.3）。字段缺失 = 不需要"]],
      widths=[2.6, 2.2, 1.5, 10.5], mono_cols=(0,))

h2("2.3  UploadImage 取值与触发行为（重点）")
table(["服务端返回", "APP 行为"],
      [["true / \"true\" / \"1\" / \"是\" / \"yes\"（任一）", "立即调用 GET /hpt/v2/Accounts/FaceSyncTasks 拉取并处理"],
       ["false / \"0\" / \"否\" 或 字段缺失", "静默等待下一次心跳，不发任何同步请求"],
       ["返回\"是\"但上一批任务正在处理中", "本次触发自动跳过，等本批完成后的下一次心跳再判断（不会并发处理）"]],
      widths=[7.2, 9.6])
para("兼容性说明：主字段为 UploadImage；APP 同时兼容别名 SyncFace / IsSyncFace / NeedSyncFace / SyncFaceTask / FaceSync，"
     "字段名大小写敏感，建议统一使用 UploadImage。", 9.5, color=(120, 60, 20))

h2("2.4  服务端实现要点")
bullet("最简实现：固定返回 {\"Status\":\"100\",\"Msg\":\"\",\"Time\":\"<服务器时间15位>\"} 即可维持在线与校时；")
bullet("需要设备拉取照片时，把 UploadImage 置为 true 返回一次即可；设备拉取后无需复位，APP 每次心跳都会重新判断；")
bullet("Time 必须真实，否则设备时间漂移会影响显示与记录；校验位为第 15 位，APP 不校验其算法，任意字符均可。")

# ================= 3 GET =================
h1("3  接口二：拉取任务　GET /hpt/v2/Accounts/FaceSyncTasks")
para("触发时机：仅两种 —— ① 心跳返回 UploadImage=是；② 用户在设备界面点击「立即同步」按钮（调试用）。APP 不会定时轮询。", bold=True)

h2("3.1  请求（Query 参数）")
code(["GET /hpt/v2/Accounts/FaceSyncTasks?clientNos=0001&size=200 HTTP/1.1",
      "Client-ID: 0001",
      "sn: 632101048645E58ECB24",
      "mac: 86:45:E5:8E:CB:24"])
table(["参数", "类型", "必填", "说明"],
      [["clientNos", "string", "是", "设备机号（与协议头 Client-ID 一致）。服务端按机号区分设备下发不同任务"],
       ["size", "int", "是", "单次最大返回条数。APP 侧默认 200、可配置（最小 1）。"
                            "服务端返回条数建议 ≤ size；返回空数组表示无待同步任务"]],
      widths=[2.6, 1.6, 1.5, 11.1], mono_cols=(0,))

h2("3.2  响应（推荐格式）")
code(["{",
      "  \"clientID\": 8,",
      "  \"TaskIdentifier\": \"1793157608810\",        // 可选：任务编号",
      "  \"accountInfos\": [",
      "    { \"accountID\": \"1001\", \"faceDataUrl\": \"Accounts/face/1001.jpg\" },",
      "    { \"accountID\": \"1002\", \"faceDataUrl\": \"http://192.168.0.147:8088/hpt/v2/face/1002.jpg\" }",
      "  ]",
      "}"])
para("APP 对响应结构做了多格式兼容（任意层级递归解析），以下写法均可用：", bold=True)
code(["// 简化格式（推荐，只需 faceDataUrl）",
      "{ \"models\": [ { \"accountID\": \"1001\", \"faceDataUrl\": \"Accounts/face/1001.jpg\" } ] }",
      "",
      "// 完整格式（消费机同款）",
      "{ \"models\": [ { \"clientID\": 1, \"accountInfos\": [ { \"accountID\": \"1001\",",
      "                 \"faceDataUrl\": \"Accounts/face/1001.jpg\" } ] } ] }",
      "",
      "// 极简格式",
      "{ \"data\": [ { \"faceDataUrl\": \"face/1001.jpg\" } ] }",
      "[ { \"faceDataUrl\": \"face/1001.jpg\" } ]",
      "[ \"face/1001.jpg\", \"face/1002.jpg\" ]        // 纯 URL 数组（无 accountID）"])

h2("3.3  响应字段说明")
table(["字段", "类型", "必返", "说明"],
      [["clientID", "string/int", "建议", "设备/客户端 ID。APP 会取出并在上报时回传（models[].clientID）"],
       ["TaskIdentifier", "string", "否", "任务编号：与 clientID/accountInfos 同级。"
                                        "APP 原样记录（界面可按编号查询历史）并在 POST 上报时原样回传；"
                                        "不带该字段时上报也不带。建议每次下发任务使用唯一编号，便于服务端对账"],
       ["models / accountInfos / data", "array", "是", "照片列表容器（任一名称均可，递归解析）"],
       ["accountID", "string", "建议", "人员/账号 ID。APP 在上报结果时原样带回，服务端据此对账入库"],
       ["faceDataUrl", "string", "是", "照片地址。拼接规则见 3.4"]],
      widths=[3.4, 1.9, 1.4, 10.1], mono_cols=(0,))

h2("3.4  faceDataUrl 拼接规则")
table(["faceDataUrl 写法", "实际下载 URL", "说明"],
      [["Accounts/face/1001.jpg", "{host}/hpt/v2/Accounts/face/1001.jpg", "相对路径默认拼在 {host}/hpt/v2/ 下（兼容消费机习惯）"],
       ["/face/1001.jpg", "{host}/face/1001.jpg", "以 / 开头拼到服务器根"],
       ["http(s):// 完整地址", "原样使用", "绝对地址直接下载"]],
      widths=[4.4, 6.8, 5.6], mono_cols=(0, 1))
para("照片下载请求同样携带协议头（Client-ID / sn / mac），照片文件必须无需登录鉴权即可匿名 GET，"
     "否则将上报 status=1（下载失败）。", 9.5, color=(120, 60, 20))

h2("3.5  服务端注意事项")
bullet("返回空列表（或空对象）时 APP 静默结束本次，不报错；")
bullet("务必避免循环下发：某账号上报成功（status=0）后应标记已同步，不得再次下发（参考项目曾出现同批 50 张循环下发）；")
bullet("每次任务建议分配唯一 TaskIdentifier 并留存，APP 回传后可用于端到端对账；")
bullet("建议照片 ≤1MB；APP 解码时超过 1000px 会等比缩小，过大的原图精度受损。")

# ================= 4 POST =================
h1("4  接口三：结果上报　POST /hpt/v2/Accounts/FaceSyncTasks")
para("每批照片全部处理完后，APP 一次性整批上报（不是逐条）。服务端只需返回 HTTP 200，APP 不解析响应内容。", bold=True)

h2("4.1  请求")
code(["POST /hpt/v2/Accounts/FaceSyncTasks HTTP/1.1",
      "Content-Type: application/json; charset=utf-8",
      "Client-ID: 0001",
      "sn: 632101048645E58ECB24",
      "mac: 86:45:E5:8E:CB:24",
      "",
      "{",
      "  \"clientID\": \"8\",",
      "  \"TaskIdentifier\": \"1793157608810\",",
      "  \"algoVersion\": \"KS V1.3.3.1\",",
      "  \"result\": \"true\",",
      "  \"models\": [",
      "    { \"accountID\": \"1001\", \"clientID\": \"8\", \"result\": \"true\", \"status\": 0,",
      "      \"message\": \"\", \"updateTime\": \"2026-10-28 11:20:08\",",
      "      \"faceFeature\": \"<Base64特征值，无换行>\", \"featureMD5\": \"<32位小写MD5>\" },",
      "    { \"accountID\": \"1002\", \"clientID\": \"8\", \"result\": \"false\", \"status\": 4,",
      "      \"message\": \"照片出现多个人脸(2张)\", \"updateTime\": \"2026-10-28 11:20:09\" }",
      "  ]",
      "}"])

h2("4.2  请求体字段说明（根层）")
table(["字段", "类型", "必带", "说明"],
      [["clientID", "string", "是", "取自 GET 响应中的 clientID"],
       ["TaskIdentifier", "string", "否", "任务编号原样回传（GET 响应带了才有）。服务端据此关联任务对账"],
       ["algoVersion", "string", "是", "算法版本号（整批相同），如 \"KS V1.3.3.1\"。服务端可据此区分不同批次设备使用的算法版本"],
       ["result", "string", "是", "固定 \"true\"，表示本次上报动作有效（批级标志，单张成败看 models[].status）"],
       ["models", "array", "是", "逐张结果数组，条数 = 本批下发条数"]],
      widths=[3.0, 1.7, 1.4, 10.7], mono_cols=(0,))

h2("4.3  models[] 逐张字段说明")
table(["字段", "类型", "必带", "说明"],
      [["accountID", "string", "否", "GET 下发时带了 accountID 才会回传，原样带回"],
       ["clientID", "string", "是", "同根层 clientID"],
       ["result", "string", "是", "单张成败：\"true\" 成功 / \"false\" 失败"],
       ["status", "int", "是", "失败原因状态码，见 4.4 状态码表"],
       ["message", "string", "是", "失败原因描述（成功为空串），文案见 4.4"],
       ["updateTime", "string", "是", "该张处理完成时间，yyyy-MM-dd HH:mm:ss"],
       ["faceFeature", "string", "否", "仅成功（status=0）时携带：人脸特征值 Base64（无换行），"
                                    "供服务端存储后下发其他设备比对使用。设备本地不存储"],
       ["featureMD5", "string", "否", "仅成功时携带：faceFeature（Base64 串）的 MD5，32 位小写十六进制，"
                                    "服务端收到后可自行计算比对以校验传输完整性"]],
      widths=[2.8, 1.6, 1.4, 11.0], mono_cols=(0,))

h2("4.4  status 状态码与 message 实际文案")
table(["status", "含义", "message 示例（实际文案）"],
      [["0", "成功", "\"\"（空）"],
       ["1", "下载失败", "\"下载失败:http://192.168.0.147:8088/hpt/v2/face/1001.jpg\""],
       ["2", "未检测到人脸", "\"未检测到人脸\"（可能附带算法信息）"],
       ["3", "照片不合格", "\"照片不合格-人脸分数 62 低于阈值 70\" / \"照片不合格-特征无效\" / \"照片不合格-特征提取异常\""],
       ["4", "照片出现多个人脸", "\"照片出现多个人脸(2张)\""],
       ["5", "图片解码失败", "\"图片解码失败\"（文件损坏或非图片格式）"],
       ["6", "照片地址为空", "\"照片地址为空\"（faceDataUrl 为空串/null）"],
       ["9", "其他异常", "处理过程异常时的兜底"]],
      widths=[1.4, 3.4, 12.0], mono_cols=(0,))
para("人脸分数阈值默认 70（0-100），可在设备端配置；分数低于阈值即报 status=3。阈值设为 0 可关闭分数判断。", 9.5,
     color=(120, 60, 20))

h2("4.5  服务端处理建议")
bullet("HTTP 200 + 空 body 即可（如 {\"code\":0}），APP 不解析；")
bullet("按 TaskIdentifier + accountID（缺失时按 sn + faceDataUrl）更新账号同步状态；")
bullet("status=0 时保存 faceFeature 并可用 featureMD5 校验完整性（对 Base64 串算 MD5 比对）；记录 algoVersion 以便追溯批次算法版本；"
       "status≠0 时记录失败原因，可人工重传或自动重新下发（注意避免无限循环）；")

# ================= 5 流程图 =================
doc.add_page_break()
h1("5  对接流程图")
para("设备侧完整运行流程如下（蓝色=请求/处理步骤，橙色=依据服务端返回做的判定，虚线=回到心跳）：", bold=True)
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = p.add_run()
run.add_picture(os.path.join(HERE, "api_flow.png"), width=Cm(15.5))

# ================= 6 联调 =================
h1("6  联调步骤与验证清单")
h2("6.1  联调步骤")
table(["步骤", "操作", "预期"],
      [["1", "服务端实现心跳接口（最简：Status=100 + Time）", "设备界面通讯圆点变绿，时间与服务器一致"],
       ["2", "curl 验证协议头（见 6.2）", "能看到 Client-ID / sn / mac 三个头"],
       ["3", "服务端下发 1~2 张测试照片，心跳返回 UploadImage=true", "设备收到 GET 请求，处理后收到 POST 上报"],
       ["4", "核对 POST 上报字段（status / faceFeature / TaskIdentifier）", "与服务端记录一一对应"],
       ["5", "正式批量下发（建议首批评次 ≤50 张观察性能）", "设备界面显示进度、成功/失败数与耗时"]],
      widths=[1.2, 8.6, 7.0])

h2("6.2  协议头抓包验证（服务端自行 curl 模拟即可忽略本步）")
para("服务端可直接在响应日志中打印收到的 Header。设备请求示例：", 10)
code(["Client-ID: 0001", "sn: 632101048645E58ECB24", "mac: 86:45:E5:8E:CB:24"])

h2("6.3  验收检查清单")
table(["检查项", "通过标准"],
      [["心跳在线", "设备界面通讯圆点绿色；停止服务端 30 秒后转红"],
       ["同步触发", "仅当心跳返回 UploadImage=是 时发生；返回否/缺失时设备零同步请求"],
       ["处理中保护", "大批次处理期间连续返回 UploadImage=是，不会并发重复拉取"],
       ["上报完整性", "POST models 条数 = GET 下发条数；每条有 status；成功条有 faceFeature"],
       ["任务编号", "GET 带 TaskIdentifier 时，POST 原样回传且设备历史列表可查"],
       ["照片兼容", "相对路径/绝对路径/带中文文件名（URL 编码）均可下载"],
       ["失败原因", "构造坏图/多人脸图/无脸图，分别收到对应 status=5/4/2"]],
      widths=[4.2, 12.6])

# ================= 7 FAQ =================
h1("7  常见问题（FAQ）")
table(["现象", "原因与处理"],
      [["设备一直离线（红点）", "心跳未返回 Status=\"100\"，或服务地址/端口不通。检查设备配置的根地址与防火墙"],
       ["设备时间不准", "心跳响应未带 Time 字段或格式不对（需 yyyyMMddHHmmss 开头的 14~15 位）"],
       ["服务端没下发 UploadImage 但收到同步请求", "是现场人员在设备界面点了「立即同步」按钮（调试用），并非心跳触发"],
       ["上报大量 status=1", "照片 404/403/需鉴权。确保 faceDataUrl 匿名可下载、文件真实存在"],
       ["上报大量 status=2/3", "照片本身无人脸或质量差；可下调设备端人脸分数阈值（默认 70）后重试"],
       ["同一批照片反复下发", "服务端未按上报结果对账标记。收到 status=0 后必须停止下发该 accountID"],
       ["设备响应慢", "单批过大。建议 size ≤200 且照片 ≤1MB；设备 4 线程处理 200 张约 1~2 分钟（视照片大小）"]],
      widths=[5.4, 11.4])

# ================= 8 附录 =================
h1("8  附录")
h2("8.1  设备端配置项（设备 Web 配置端 ip:8090 或界面「参数设置」）")
table(["配置项", "默认值", "说明"],
      [["服务器地址", "—", "服务端根地址，如 http://192.168.0.147:8088/"],
       ["设备机号 (clientNos)", "0001", "按机号拉取任务，与 Client-ID 头一致"],
       ["心跳间隔（秒）", "15", "最小 5 秒"],
       ["单次拉取条数 (size)", "200", "照片限制 1M 以内"],
       ["人脸分数阈值", "70", "0-100，低于阈值判不合格；0 = 关闭分数判断"]],
      widths=[4.6, 2.6, 9.6])
h2("8.2  设备信息参考")
table(["项", "获取方式", "示例"],
      [["SN", "系统序列号（ro.serialno 等）", "632101048645E58ECB24"],
       ["MAC", "优先 eth0，其次 wlan0（/sys/class/net/*/address）", "86:45:E5:8E:CB:24"],
       ["算法版本", "旷视 FaceCore", "KS V1.3.3.1"],
       ["开机自启", "平台签名 + BOOT_COMPLETED 广播，上电自动进入 APP", "—"]],
      widths=[2.8, 9.0, 5.0])

doc.save(OUT)
print("saved:", OUT)
