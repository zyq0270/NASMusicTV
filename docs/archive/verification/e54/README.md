# E54「街机合集」定稿证据留档（2026-11-10）

> 从 `logs_temp/`（不进仓）**复制**留档，原件仍在原位；用途是让
> `docs/technical-overview.md` §10.374–§10.376 的结论可复现、可校对。

## 离线 bot 诊断（JVM 直跑已编译 Kotlin class，避开每轮 8 分钟 Gradle）

| 脚本 | 用途 |
|---|---|
| `E54Diag.java` / `E54Diag2.java` | 第三轮卡关定位（第 7 关反复死亡） |
| `E54Diag3.java` | 20 组开关矩阵定位「过不了关」三处耦合根因；第四轮通关/死亡验证 |
| `E54Diag4.java` | 死亡画像：每关死亡数 / 死因推断 / 行为帧占比 / boss 战时长 |
| `E54Diag5.java` | 第 5 关死亡明细（实锤「跳跃中枪口抬高、水平弹从贴地敌人头顶飞过」） |

运行（仓库根；kstdlib.jar 必须拷到相对路径，Windows 绝对路径的盘符冒号会破坏 classpath）：

```bash
JBR="C:/Program Files/Android/Android Studio/jbr/bin"
CP="app/build/tmp/kotlin-classes/debug;logs_temp/diag/kstdlib.jar"
"$JBR/javac" -cp "$CP" -d logs_temp/diag docs/archive/verification/e54/E54Diag3.java
"$JBR/java" -cp "logs_temp/diag;$CP" E54Diag3 600000
```

## 一次性补丁 / 文档同步脚本（已全部落盘，留档备查）

`e54_r3_patch_sim.py`（第三轮：散弹收敛 / 上瞄补平射 / 蹲射半速）、
`e54_r3_sync_docs.py`（第三轮口径 38→40）、
`e54_r4_fullbleed_patch.py`（第四轮：画面横纵铺满）、
`e54_r4_bot_patch.py` / `e54_r4_bot_patch2.py`（第四轮调参 + 贴脸禁跳 + 回退扩窗）、
`e54_r4_docs_gates.py`（第四轮门禁 +2 条与四份文档口径）。

## APK 校验

`e54_verify_apk.py <apk路径>`：指纹 / 图集 / 散装 PNG / 双语标签 / dex 引用；
⛔ versionCode 在 AXML 里是二进制 int，须用 `aapt2 dump badging` 单独核对。
