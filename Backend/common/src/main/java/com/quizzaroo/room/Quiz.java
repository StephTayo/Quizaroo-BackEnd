package com.quizzaroo.room;

import java.util.List;

/**
 * The quiz being played. Loaded once when the room is created and never
 * mutated during a game.
 *
 * This is the only place correctIndex lives. It is deliberately not part of
 * any protocol message except Reveal.
 */
public record Quiz(String title, List<Item> items) {

    public Quiz {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A quiz needs at least one question");
        }
        items = List.copyOf(items);
    }

    public int size() {
        return items.size();
    }

    public Item item(int index) {
        return items.get(index);
    }

    /**
     * One question. basePoints is the maximum a player can earn before the
     * speed factor and streak bonus are applied.
     */
    public record Item(
            String text,
            String imageUrl,
            List<String> choices,
            int correctIndex,
            long timeLimitMs,
            int basePoints
    ) {
        public Item {
            if (choices == null || choices.size() < 2) {
                throw new IllegalArgumentException("A question needs at least two choices");
            }
            if (correctIndex < 0 || correctIndex >= choices.size()) {
                throw new IllegalArgumentException("correctIndex is outside the choice list");
            }
            if (timeLimitMs < 1000) {
                throw new IllegalArgumentException("timeLimitMs must be at least 1000");
            }
            choices = List.copyOf(choices);
        }

        public static Item of(String text, List<String> choices, int correctIndex) {
            return new Item(text, null, choices, correctIndex, 20_000L, 1_000);
        }
    }
}
