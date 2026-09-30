# MemeEmoji — 项目笔记

## 概览

Minecraft 1.21.1 Fabric mod。把图片丢进 config/memeemoji/emoji/，聊天用 :名字: 发出来。

## 关键设计决策

### 资源包注入
- 生成的图集在 config/memeemoji/generated/，通过 PackRepositoryMixin.discoverAvailable RETURN 注入
- 不走 Fabric registerBuiltinResourcePack（那个只能指向 mod jar 内部的路径）

### 字体渲染
- PUA 码位从 U+E000 开始，图集第 i 格固定为 U+E000 + i
- 格大小由自动检测决定：扫描所有图片取最大边 × scale（默认 1.0 = 原尺寸）
- glyphHeight = cell()（否则 ModernUI mScaleFactor<1 → advance 偏小→重叠），ascent ≈ cell×0.625
- StringDecomposerMixin 的 3-arg iterate 和 5-arg iterateFormatted 覆盖全部文本链路
- ModernUI 的 TextLayoutProcessor、ModernStringSplitter、FormattedTextWrapper 全部走 5-arg hook

### WebP 支持
- 手动 SPI 注册 WebpSupport.ensureRegistered()，不从 spi 服务文件加载
- jar 合并时排除 META-INF/services/* 防冲突

### 服务端同步
- 分片传输（每片 768KB），Start / Chunk / End 三段式
- 单人模式不走同步，直接读本地文件夹

### EmojiPickerScreen
- 纯 Screen 实现，不依赖 AbstractWidget，用 render/mouseClicked/mouseScrolled 手动绘制
- 按 ESC 关闭，点击表情后回调通知调用方导航

## 配置
- 文件: config/memeemoji/config.json
- 字段: enabled, maxNameLength, sendToClients, scale
- scale: 取所有图片最大边 × scale = 统一格大小。1.0 = 原尺寸，0.5 = 一半。范围 0.1~5.0
- 文件不存在时自动生成默认配置；已有文件缺字段时自动补全

## 踩过的坑

1. **MemeEmojiConfig.DEFAULT 循环引用**: 字段初始值不能引用 DEFAULT 自身，否则 <clinit> 时 NPE。改为字段默认值（无参构造）。
2. **jar 合并 SPI 冲突**: webp-imageio 的 META-INF/services/javax.imageio.spi.ImageReaderSpi 在合并进 mod jar 后与 ImageIO 内置 SPI 冲突。已在 jar task 中 exclude.
3. **ModernUI 3.13.0.1 依赖 ForgeConfigAPIPort >= 21.0.5**: 开发测试环境已放好两个 jar.
4. **不需 modmenu**: ForgeConfigAPIPort 的 modmenu 建议只是版本建议，不影响运行.
5. **ChatScreen 按钮不能用 @Shadow addRenderableWidget**: ModernUI 环境下该 Shadow 不可靠，改为纯手工绘制。
6. **TextLayoutProcessorMixin createVanillaLayout 需 CallbackInfoReturnable**: 因为 createVanillaLayout 是非 void 方法，用 CallbackInfo 会导致 mixin 应用失败。
7. **PowerShell -replace 拼接整行问题**: -replace 在读取为 Raw 字符串时可能导致换行问题，需注意格式。
8. **ModernUI BitmapFont advance 重叠**: font.json 的 height 必须等于纹理 cell 大小，否则 mScaleFactor<1 导致 glyph advance 偏小、重叠。已修复为 glyphHeight()=cell()。
9. **emojiSize 预设→自动检测**: 原 small/medium/large 切换已移除。改为扫图片实际尺寸 × scale 设 cell。ImageTiles 新增 readDimension() 只读头不解码全文。
