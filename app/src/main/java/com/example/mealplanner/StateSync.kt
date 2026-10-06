package com.example.mealplanner

internal fun <T, K> syncListByKey(
    target: MutableList<T>,
    incoming: List<T>,
    key: (T) -> K
) {
    incoming.forEachIndexed { index, item ->
        val itemKey = key(item)

        if (
            index < target.size &&
            key(target[index]) == itemKey
        ) {
            if (target[index] != item) {
                target[index] = item
            }
            return@forEachIndexed
        }

        val existingIndex = target
            .indices
            .drop(index + 1)
            .firstOrNull { key(target[it]) == itemKey }

        if (existingIndex != null) {
            target.removeAt(existingIndex)
        }

        target.add(index, item)
    }

    while (target.size > incoming.size) {
        target.removeAt(target.lastIndex)
    }
}

internal fun <K, V> syncMap(
    target: MutableMap<K, V>,
    incoming: Map<K, V>
) {
    val removedKeys = target.keys.filter { it !in incoming }
    removedKeys.forEach(target::remove)

    incoming.forEach { (key, value) ->
        if (target[key] != value || key !in target) {
            target[key] = value
        }
    }
}

internal fun <T> syncSet(
    target: MutableSet<T>,
    incoming: Set<T>
) {
    target.retainAll(incoming)
    target.addAll(incoming)
}
