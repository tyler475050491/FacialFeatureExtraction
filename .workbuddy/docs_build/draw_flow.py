# -*- coding: utf-8 -*-
"""生成接口对接流程图 PNG（设备侧完整运行流程）"""
from PIL import Image, ImageDraw, ImageFont
import os

W, H = 1500, 2380
BG = (255, 255, 255)
C_MAIN = (30, 120, 200)        # 主流程蓝
C_MAIN_BG = (232, 242, 252)
C_DEC = (230, 145, 20)         # 判断橙
C_DEC_BG = (255, 246, 230)
C_ALT = (120, 125, 135)        # 分支灰
C_ALT_BG = (243, 244, 246)
C_OK = (36, 150, 90)           # 结束绿
C_OK_BG = (232, 246, 238)
C_TEXT = (28, 32, 38)
C_LINE = (90, 100, 112)

FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
FONT_R = "C:/Windows/Fonts/msyh.ttc"


def f(bold, size):
    return ImageFont.truetype(FONT_B if bold else FONT_R, size)


img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)


def text_center(cx, cy, lines, font, color=C_TEXT, gap=8):
    hs = []
    for ln in lines:
        bb = d.textbbox((0, 0), ln, font=font)
        hs.append((bb[2] - bb[0], bb[3] - bb[1]))
    total = sum(h for _, h in hs) + gap * (len(lines) - 1)
    y = cy - total / 2
    for ln, (tw, th) in zip(lines, hs):
        d.text((cx - tw / 2, y - 2), ln, font=font, fill=color)
        y += th + gap


def box(cx, cy, w, h, lines, fill, border, bold=False, size=23, radius=12, lw=2, color=C_TEXT, small=None):
    d.rounded_rectangle([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2],
                        radius=radius, fill=fill, outline=border, width=lw)
    if small:
        # 第一行大标题，其余小字
        t1, rest = lines[0], lines[1:]
        f1 = f(True, size)
        f2 = f(False, small)
        bb1 = d.textbbox((0, 0), t1, font=f1)
        bb2 = [d.textbbox((0, 0), r, font=f2) for r in rest]
        h1 = bb1[3] - bb1[1]
        h2 = sum(b[3] - b[1] for b in bb2) + 6 * max(0, len(rest) - 1)
        gap = 10
        total = h1 + (gap + h2 if rest else 0)
        y = cy - total / 2
        d.text((cx - (bb1[2] - bb1[0]) / 2, y - 3), t1, font=f1, fill=color)
        y += h1 + gap
        for r, b in zip(rest, bb2):
            d.text((cx - (b[2] - b[0]) / 2, y), r, font=f2, fill=(70, 78, 88))
            y += (b[3] - b[1]) + 6
    else:
        text_center(cx, cy, lines, f(bold, size), color)


def diamond(cx, cy, w, h, lines, size=22):
    d.polygon([(cx, cy - h / 2), (cx + w / 2, cy), (cx, cy + h / 2), (cx - w / 2, cy)],
              fill=C_DEC_BG, outline=C_DEC, width=2)
    text_center(cx, cy, lines, f(True, size), C_TEXT)


def arrow(pts, color=C_LINE, width=3, head=13, dash=False):
    for i in range(len(pts) - 2):
        (x1, y1), (x2, y2) = pts[i], pts[i + 1]
        if dash:
            # 手绘虚线
            import math
            L = math.hypot(x2 - x1, y2 - y1)
            step = 12
            n = int(L // step)
            for k in range(n):
                if k % 2 == 0:
                    t1, t2 = k / n, min((k + 1) / n, 1)
                    d.line([(x1 + (x2 - x1) * t1, y1 + (y2 - y1) * t1),
                            (x1 + (x2 - x1) * t2, y1 + (y2 - y1) * t2)],
                           fill=color, width=width)
        else:
            d.line([(x1, y1), (x2, y2)], fill=color, width=width)
    # 最后一段实线 + 箭头
    (x1, y1), (x2, y2) = pts[-2], pts[-1]
    d.line([(x1, y1), (x2, y2)], fill=color, width=width)
    import math
    ang = math.atan2(y2 - y1, x2 - x1)
    p1 = (x2 - head * math.cos(ang - 0.42), y2 - head * math.sin(ang - 0.42))
    p2 = (x2 - head * math.cos(ang + 0.42), y2 - head * math.sin(ang + 0.42))
    d.polygon([(x2, y2), p1, p2], fill=color)


def tag(x, y, s, color=C_LINE, size=19, bg=BG):
    ft = f(True, size)
    bb = d.textbbox((x, y), s, font=ft)
    d.rectangle([bb[0] - 4, bb[1] - 2, bb[2] + 4, bb[3] + 2], fill=bg)
    d.text((x, y), s, font=ft, fill=color)


# ---------------- 标题 ----------------
d.text((60, 34), "人脸特征提取 APP  接口对接流程图", font=f(True, 38), fill=(20, 40, 70))
d.text((62, 92), "设备侧完整运行流程（心跳驱动，不做定时轮询）｜版本 v1.0", font=f(False, 21), fill=(110, 118, 128))

CX = 600           # 主流程中心
BW = 660           # 主框宽
SIDE = 1230        # 右侧分支中心

# ---------------- 1. 启动 ----------------
y = 190
box(CX, y, 560, 72, ["设备上电", "APP 开机自启（平台签名 / system uid）"], C_OK_BG, C_OK, small=22)
arrow([(CX, y + 36), (CX, 280)])

# ---------------- 2. 心跳 ----------------
y1 = 348
box(CX, y1, BW, 136,
    ["① 心跳　POST /hpt/v1/CurrentTime",
     "间隔 N 秒（默认 15，可配最小 5）｜Body: {\"ICCID\":\"\",\"ICCID2\":\"\"}",
     "Header: Client-ID / sn / mac"],
    C_MAIN_BG, C_MAIN, small=20)
arrow([(CX, y1 + 68), (CX, 452)])

# ---------------- 3. 判断 Status ----------------
y2 = 520
diamond(CX, y2, 470, 136, ["Status == 100 ?"], 23)
arrow([(CX, y2 + 68), (CX, 625)])
tag(CX + 14, y2 + 74, "是", C_OK)
# 否 → 右侧
arrow([(CX + 235, y2), (SIDE - 175, y2)])
tag(CX + 250, y2 - 34, "否 / 超时 / 网络异常", C_ALT, 18)
box(SIDE, y2, 350, 82, ["设备判定离线", "界面通讯圆点转红"], C_ALT_BG, C_ALT, small=20)

# ---------------- 4. 在线 ----------------
y3 = 700
box(CX, y3, BW, 78, ["设备在线（通讯圆点转绿）｜按 Time 校时：|偏差| > 10s 才写系统时间"],
    C_MAIN_BG, C_MAIN, size=21)
arrow([(CX, y3 + 39), (CX, 800)])

# ---------------- 5. 判断 UploadImage ----------------
y4 = 872
diamond(CX, y4, 470, 144, ["UploadImage == 是 ?"], 23)
arrow([(CX, y4 + 72), (CX, 985)])
tag(CX + 14, y4 + 78, "是", C_OK)
arrow([(CX + 235, y4), (SIDE - 175, y4)])
tag(CX + 250, y4 - 34, "否（字段缺失也按否处理）", C_ALT, 18)
box(SIDE, y4, 350, 82, ["静默等待", "不发任何同步请求"], C_ALT_BG, C_ALT, small=20)

# ---------------- 6. 判断处理中 ----------------
y5 = 1060
diamond(CX, y5, 470, 148, ["当前有任务正在处理 ?"], 22)
arrow([(CX, y5 + 74), (CX, 1175)])
tag(CX + 14, y5 + 80, "否", C_OK)
arrow([(CX + 235, y5), (SIDE - 175, y5)])
tag(CX + 250, y5 - 34, "是", C_ALT, 18)
box(SIDE, y5, 350, 96, ["本次触发跳过", "等本批完成后的", "下一次心跳再判断"], C_ALT_BG, C_ALT, small=19)

# ---------------- 7. GET ----------------
y6 = 1290
box(CX, y6, BW, 132,
    ["② GET /hpt/v2/Accounts/FaceSyncTasks",
     "?clientNos={机号}&size={单次条数，默认 200}",
     "Header: Client-ID / sn / mac"],
    C_MAIN_BG, C_MAIN, small=20)
arrow([(CX, y6 + 66), (CX, 1395)])

# ---------------- 8. 判断列表为空 ----------------
y7 = 1462
diamond(CX, y7, 470, 136, ["返回照片列表为空 ?"], 22)
arrow([(CX, y7 + 68), (CX, 1570)])
tag(CX + 14, y7 + 74, "否", C_OK)
arrow([(CX + 235, y7), (SIDE - 175, y7)])
tag(CX + 250, y7 - 34, "是", C_ALT, 18)
box(SIDE, y7, 350, 82, ["本次结束", "等待下一次心跳"], C_ALT_BG, C_ALT, small=20)

# ---------------- 9. 处理照片 ----------------
y8 = 1672
box(CX, y8, BW + 40, 150,
    ["③ 4 线程并发：下载 → 解码 → 检测 → 提特征",
     "逐张下载 faceDataUrl（相对路径自动拼接）→ 解码限制最大边 1000px",
     "→ 多人脸 / 人脸分数阈值判断 → 旷视提取特征（照片与特征均不落盘）"],
    C_MAIN_BG, C_MAIN, small=19)
arrow([(CX, y8 + 75), (CX, 1785)])

# ---------------- 10. POST ----------------
y9 = 1888
box(CX, y9, BW + 40, 136,
    ["④ POST /hpt/v2/Accounts/FaceSyncTasks（整批一次上报）",
     "根层：clientID、TaskIdentifier（原样回传）、result、models[]",
     "每项：accountID / clientID / result / status / message / updateTime / faceFeature"],
    C_MAIN_BG, C_MAIN, small=19)
arrow([(CX, y9 + 68), (CX, 1995)])

# ---------------- 11. 落库 ----------------
y10 = 2060
box(CX, y10, BW, 88,
    ["⑤ 写入本地 SQLite 历史 + 界面刷新",
     "记录：处理时间 / 任务编号 / 照片数 / 成功 / 失败 / 整体耗时"],
    C_OK_BG, C_OK, small=20)
arrow([(CX, y10 + 44), (CX, 2160)])

# ---------------- 12. 结束（循环回心跳） ----------------
y11 = 2226
box(CX, y11, 560, 76, ["等待下一次心跳（循环往复）"], C_OK_BG, C_OK, bold=True, size=24)

# 末段回环箭头：从结束框回到心跳框（左侧）
arrow([(CX - 280, y11), (150, y11), (150, y1), (CX - BW / 2 - 2, y1)], color=(150, 160, 175), width=3, dash=True)
tag(CX - 640, 1210, "循环", (150, 160, 175), 19)

# 右侧三个分支统一回收虚线（回到心跳）
for yy in (y2, y4, y5, y7):
    pass
arrow([(SIDE + 175, y2), (1420, y2), (1420, 1740)], color=(185, 195, 205), width=2, dash=True)
arrow([(1420, 1740), (1420, 760)], color=(185, 195, 205), width=2, dash=True)
arrow([(1420, 760), (930, 760)], color=(185, 195, 205), width=2, dash=True)
tag(1330, 1760, "以上分支均回到心跳", (150, 160, 175), 17)

# 图例
lg_y = 2320
d.rectangle([60, lg_y - 14, 84, lg_y + 10], fill=C_MAIN_BG, outline=C_MAIN, width=2)
d.text((94, lg_y - 12), "请求/处理步骤", font=f(False, 18), fill=(70, 78, 88))
d.polygon([(300, lg_y - 4), (322, lg_y - 18), (344, lg_y - 4), (322, lg_y + 10)], fill=C_DEC_BG, outline=C_DEC, width=2)
d.text((354, lg_y - 12), "服务端返回的判定条件", font=f(False, 18), fill=(70, 78, 88))
d.line([(620, lg_y - 4), (700, lg_y - 4)], fill=(185, 195, 205), width=2)
d.text((712, lg_y - 12), "跳过/回到心跳的分支", font=f(False, 18), fill=(70, 78, 88))

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "api_flow.png")
img.save(out, "PNG")
print("saved", out, img.size)
