package com.memeemoji;

import com.memeemoji.registry.ShapingTable;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前生效的表情表。图集第 i 格固定对应码位 PUA_BASE + i，所以本地扫描和服务端下发都用同一份列表顺序，
 * 不需要额外传码位映射；渲染时再把 :名字: 替换成对应的私用区码位。
 */
public final class EmojiRegistry {
    public static final EmojiRegistry INSTANCE = new EmojiRegistry();

    private volatile IntSet puaCodepoints = IntSets.emptySet();
    private volatile ShapingTable table = ShapingTable.EMPTY;
    private volatile int size;
    private volatile List<EmojiTile> currentTiles = List.of();

    private EmojiRegistry() {
    }

    /** 按图集顺序返回当前所有表情；表情选择界面用它列表情。 */
    public List<EmojiTile> tiles() {
        return currentTiles;
    }

    public void apply(List<EmojiTile> tiles, boolean enabled) {
        if (!enabled || tiles.isEmpty()) {
            puaCodepoints = IntSets.emptySet();
            table = ShapingTable.EMPTY;
            size = 0;
            currentTiles = List.of();
            return;
        }
        currentTiles = tiles;
        IntSet nextPua = new IntOpenHashSet(Math.max(16, tiles.size()));
        Map<String, Integer> matchKeys = new HashMap<>(tiles.size() * 2);
        for (int i = 0; i < tiles.size(); i++) {
            int codepoint = MemeEmoji.PUA_BASE + i;
            nextPua.add(codepoint);
            matchKeys.put(matchKey(tiles.get(i).name()), codepoint);
        }
        puaCodepoints = nextPua;
        table = ShapingTable.build(matchKeys);
        size = tiles.size();
    }

    public int size() {
        return size;
    }

    /** 渲染热路径，只有文本里出现冒号时才值得进入 Trie 匹配。 */
    public boolean needsShaping(String text) {
        return size > 0 && text.indexOf(':') >= 0;
    }

    public ShapingTable shapingTable() {
        return table;
    }

    /** 已经是私用区码位的字符（服务端下发的文本、玩家输入过的表情）要继续用表情字体渲染。 */
    public boolean rendersInEmojiFont(int codepoint) {
        return puaCodepoints.contains(codepoint);
    }

    /** 匹配键是 :名字:，逐码位小写化，与 ShapingSink 匹配时的小写化方式保持一致。 */
    private static String matchKey(String name) {
        StringBuilder key = new StringBuilder(name.length() + 2).append(':');
        for (int i = 0; i < name.length(); ) {
            int codepoint = name.codePointAt(i);
            key.appendCodePoint(Character.toLowerCase(codepoint));
            i += Character.charCount(codepoint);
        }
        return key.append(':').toString();
    }
}
