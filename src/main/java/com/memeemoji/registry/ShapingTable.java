package com.memeemoji.registry;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Map;

/**
 * 表情名字的前缀树，键是 :名字: 的码位序列。渲染时逐字符往下走，走到带终点的节点就说明匹配到了一个表情；
 * 匹配失败要把已经吃掉的字符原样吐回去，所以调用方必须自己维护字符缓冲区。
 */
public final class ShapingTable {
    public static final ShapingTable EMPTY = new ShapingTable(new Node(), 0);

    private final Node root;
    private final int maxDepth;

    private ShapingTable(Node root, int maxDepth) {
        this.root = root;
        this.maxDepth = maxDepth;
    }

    public static ShapingTable build(Map<String, Integer> matchKeys) {
        Node root = new Node();
        int maxDepth = 0;
        for (Map.Entry<String, Integer> entry : matchKeys.entrySet()) {
            String sequence = entry.getKey();
            Node node = root;
            int depth = 0;
            for (int i = 0; i < sequence.length(); ) {
                int codepoint = sequence.codePointAt(i);
                node = node.child(codepoint);
                i += Character.charCount(codepoint);
                depth++;
            }
            node.terminal = entry.getValue();
            maxDepth = Math.max(maxDepth, depth);
        }
        return new ShapingTable(root, maxDepth);
    }

    public Node root() {
        return root;
    }

    /** 最长键的码位个数，调用方按它准备缓冲区，名字很长的表情也能整段匹配。 */
    public int maxDepth() {
        return maxDepth;
    }

    public static final class Node {
        private Int2ObjectMap<Node> children;
        private Integer terminal;

        Node child(int codepoint) {
            if (children == null) {
                children = new Int2ObjectOpenHashMap<>(2);
            }
            Node existing = children.get(codepoint);
            if (existing != null) {
                return existing;
            }
            Node fresh = new Node();
            children.put(codepoint, fresh);
            return fresh;
        }

        public Node next(int codepoint) {
            return children == null ? null : children.get(codepoint);
        }

        public Integer terminal() {
            return terminal;
        }
    }
}
