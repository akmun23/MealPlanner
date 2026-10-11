package com.example.mealplanner

/** Step labels and editing rules are independent of Compose for repeatable tests. */
internal object RecipeInstructionRules {

    /**
     * Save should never drop a draft typed into the quick-add box, even if the
     * user presses the main Save button before pressing Add step.
     */
    fun withPendingText(
        steps: List<RecipeInstructionStep>,
        pendingMain: String,
        pendingSub: String,
    ): List<RecipeInstructionStep> = buildList {
        addAll(steps)
        pendingMain.trim().takeIf { it.isNotEmpty() }?.let {
            add(RecipeInstructionStep(text = it, level = 0))
        }
        pendingSub.trim().takeIf { it.isNotEmpty() && any { s -> s.level == 0 } }?.let {
            add(RecipeInstructionStep(text = it, level = 1))
        }
    }

    fun numbered(steps: List<RecipeInstructionStep>): List<Pair<String, RecipeInstructionStep>> {
        var main = 0
        var sub = 0
        return steps.map { step ->
            if (step.level == 1 && main > 0) {
                sub++
                val suffix = if (sub <= 26) ('a' + sub - 1).toString() else sub.toString()
                "$main.$suffix" to step
            } else {
                main++
                sub = 0
                "$main." to step
            }
        }
    }

    fun remove(steps: List<RecipeInstructionStep>, index: Int): List<RecipeInstructionStep> {
        val step = steps.getOrNull(index) ?: return steps
        if (step.level == 1) return steps.filterIndexed { i, _ -> i != index }
        val end = ((index + 1) until steps.size)
            .firstOrNull { steps[it].level == 0 } ?: steps.size
        return steps.filterIndexed { i, _ -> i < index || i >= end }
    }

    /** Move the entire main-step block or a single substep within its parent. */
    fun move(
        steps: List<RecipeInstructionStep>,
        index: Int,
        direction: Int
    ): List<RecipeInstructionStep> {
        if (direction !in listOf(-1, 1)) return steps
        val step = steps.getOrNull(index) ?: return steps
        if (step.level == 1) {
            val other = index + direction
            if (other !in steps.indices || steps[other].level != 1) return steps
            return steps.toMutableList().apply {
                val temp = this[index]
                this[index] = this[other]
                this[other] = temp
            }
        }

        val starts = steps.indices.filter { steps[it].level == 0 }
        val mainIndex = starts.indexOf(index)
        val neighborIndex = mainIndex + direction
        if (mainIndex < 0 || neighborIndex !in starts.indices) return steps

        val groups = starts.mapIndexed { i, begin ->
            steps.subList(begin, starts.getOrNull(i + 1) ?: steps.size)
        }.toMutableList()
        val group = groups[mainIndex]
        groups[mainIndex] = groups[neighborIndex]
        groups[neighborIndex] = group
        return groups.flatten()
    }
}
