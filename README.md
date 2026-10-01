# MemeEmoji 🎭

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?logo=minecraft)
![Fabric](https://img.shields.io/badge/Fabric-Fabric_0.16.0-orange?logo=fabric)
![ModernUI](https://img.shields.io/badge/ModernUI-3.13.0-green)
![License](https://img.shields.io/badge/License-All_Rights_Reserved-lightgrey)

**Minecraft 1.21.1 Fabric 模组** — 把图片/GIF 丢进文件夹，聊天打 :名字: 就能发出来。兼容 ModernUI 现代文本引擎。

---

## 功能速览

- 🖼️ **即丢即用** — 支持 PNG、JPG、WebP、GIF、BMP，不用转格式，丢进 config/memeemoji/emoji/ 直接用
- 📝 **:名字: 发送** — 支持中文、英文、数字，不区分大小写
- 🔄 **服务端自动同步** — 服务端装了这个 mod，客户端的表情自动下发，无需每个玩家手动安装
- 🖱️ **表情选择界面** — 聊天框旁边有 😊 按钮，点开选表情
- ✨ **ModernUI 兼容** — 与现代文本引擎无缝集成，清晰度不打折
- 📐 **三档尺寸预设** — Small / Medium / Large，丢多大的图就保持多大
- 💨 **热加载** — 丢新图进文件夹、改配置都不用重启游戏
- 🏖️ **服务端可不装** — 只装客户端也能用，图片本地生效

## 安装

1. 需要 **Fabric Loader >= 0.16.0** + **Fabric API**
2. 把 MemeEmoji jar 放进 mods/ 文件夹
3. 可选：装 [ModernUI](https://github.com/BloCamLimb/ModernUI-MC) 获得更好的文本渲染

## 使用

### 添加表情

把图片文件直接丢进 .minecraft/config/memeemoji/emoji/ 目录：

`
config/memeemoji/emoji/
├── 猫猫.webp
├── 狗头.jpg
├── happy.png
└── 草.gif
`

文件名（不含扩展名）就是在聊天里用的 :名字:。不需要任何配置文件或资源包。

### 在聊天里使用

发送：

`
:猫猫: 今天天气真不错 :dog:
`

:猫猫: 和 :dog: 会自动替换成对应的图片/GIF。支持在同一句话里混用多个表情。

### 表情选择界面

聊天框输入时，输入框右侧有个 😊 按钮，点击打开表情选择面板。

## 配置

文件位置：.minecraft/config/memeemoji/config.json

`json
{
  "enabled": true,
  "maxNameLength": 32,
  "sendToClients": true,
  "sizePreset": "medium",
  "smallCellSize": 24,
  "mediumCellSize": 48,
  "largeCellSize": 96,
  "maxCellSize": 128
}
`

| 字段 | 默认值 | 说明 |
|------|--------|------|
| nabled | 	rue | 是否启用 |
| maxNameLength | 32 | 表情名字最大字符数 |
| sendToClients | 	rue | 是否向客户端同步表情（服务端有效） |
| sizePreset | "medium" | 大小预设："small" / "medium" / "large" |
| smallCellSize | 24 | 小号最大尺寸 |
| mediumCellSize | 48 | 中号最大尺寸 |
| largeCellSize | 96 | 大号最大尺寸 |
| maxCellSize | 128 | 上限，兜底防意外超大值 |

配置文件不存在时自动生成，缺字段自动补全。

## 热加载

改配置文件或往 moji/ 丢新图，等 **500ms** 自动生效，不用重启游戏。

## 构建

`ash
git clone https://github.com/HuaJiY/MemeEmoji.git
cd MemeEmoji
./gradlew.bat build
`

构建产物：uild/libs/memeemoji-*.jar

## 技术原理

- **独立纹理渲染**：每个表情拥有独立的 PNG 纹理 + 自定义 BakedGlyph 直接引用原始纹理渲染，绕过 ModernUI 的位图图集管道，保持原始清晰度
- **PUA 码位分配**：从 BMP 私用区 U+E000 开始，每张表情占用一个固定码位
- **文本替换**：通过 Mixin 注入 StringDecomposer.iterate/iterateFormatted，将 :名字: 匹配为对应的 PUA 码位
- **ModernUI 集成**：配合 TextLayoutProcessor.createVanillaLayout 适配 ModernUI 布局计算路径；StandardFontSetMixin 拦截 ModernUI 的字形查找，返回自定义高清晰度 BakedGlyph
- **资源包注入**：生成的资源包写入 config/memeemoji/generated/，通过 PackRepositoryMixin.discoverAvailable 挂进客户端资源包列表，不走 egisterBuiltinResourcePack（后者只能指向 mod jar 内部路径）
- **服务端同步**：分片传输（每片 768KB），Start / Chunk / End 三段式协议
- **WebP 支持**：通过 webp-imageio 解码 WebP 格式，手动注册 SPI 避免服务文件冲突

## 鸣谢

- [Twemoji](https://github.com/Leclowndu93150/Twemoji) — 原始灵感来源
- [ModernUI](https://github.com/BloCamLimb/ModernUI-MC) — 现代文本引擎
- [Emogg](https://github.com/aratakileo/emogg) — 参考实现