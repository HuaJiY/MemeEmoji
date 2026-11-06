package com.memeemoji.gui;

record PickerLayout(int x, int y, int width, int height, int gridX, int gridY,
                    int gridWidth, int gridHeight, int columns, int cell) {
    static PickerLayout calculate(int screenWidth, int screenHeight) {
        int width = Math.min(600, Math.max(1, screenWidth - 16));
        int height = Math.min(410, Math.max(1, screenHeight - 16));
        int x = (screenWidth - width) / 2;
        int y = (screenHeight - height) / 2;
        int gridWidth = Math.max(1, width - 28);
        int columns = Math.max(1, gridWidth / 76);
        int cell = Math.max(1, gridWidth / columns);
        return new PickerLayout(x, y, width, height, x + 10, y + 90,
                gridWidth, Math.max(1, height - 112), columns, cell);
    }

    boolean inGrid(double mouseX, double mouseY) {
        return mouseX >= gridX && mouseX < gridX + gridWidth
                && mouseY >= gridY && mouseY < gridY + gridHeight;
    }
}
