package com.example.mealplanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

class LocalStorage(context: Context) {

    private val prefs = context.getSharedPreferences("meal_planner_data_v5", Context.MODE_PRIVATE)

    data class Snapshot(
        val ingredients: List<Ingredient>,
        val recipes: List<Recipe>,
        val pantry: Map<String, Double>,
        val dayModes: Map<DayOfWeek, DayMode>,
        val plan: Map<DayOfWeek, String?>,
        val weeklySandwichRecipeId: String?,
        val shoppingChecked: Map<String, Boolean>
    )

    fun save(snapshot: Snapshot) {
        val root = JSONObject()

        root.put("ingredients", JSONArray().apply {
            snapshot.ingredients.forEach { ingredient ->
                put(JSONObject().apply {
                    put("id", ingredient.id)
                    put("name", ingredient.name)
                    put("quantityType", ingredient.quantityType.name)
                    put("preferredUnit", ingredient.preferredUnit.name)
                    put("imageUri", ingredient.imageUri ?: JSONObject.NULL)
                })
            }
        })

        root.put("recipes", JSONArray().apply {
            snapshot.recipes.forEach { recipe ->
                put(JSONObject().apply {
                    put("id", recipe.id)
                    put("name", recipe.name)
                    put("mealType", recipe.mealType.name)
                    put("proteinTag", recipe.proteinTag)
                    put("categoryTag", recipe.categoryTag)
                    put("notes", recipe.notes)
                    put("imageUri", recipe.imageUri ?: JSONObject.NULL)
                    put("ingredients", JSONArray().apply {
                        recipe.ingredients.forEach { req ->
                            put(JSONObject().apply {
                                put("ingredientId", req.ingredientId)
                                put("amountBase", req.amountBase)
                            })
                        }
                    })
                })
            }
        })

        root.put("pantry", JSONObject().apply {
            snapshot.pantry.forEach { (id, amount) -> put(id, amount) }
        })

        root.put("dayModes", JSONObject().apply {
            snapshot.dayModes.forEach { (day, mode) -> put(day.name, mode.name) }
        })

        root.put("plan", JSONObject().apply {
            snapshot.plan.forEach { (day, recipeId) ->
                if (recipeId == null) put(day.name, JSONObject.NULL)
                else put(day.name, recipeId)
            }
        })

        root.put("weeklySandwichRecipeId", snapshot.weeklySandwichRecipeId ?: JSONObject.NULL)

        root.put("shoppingChecked", JSONObject().apply {
            snapshot.shoppingChecked.forEach { (ingredientId, checked) ->
                put(ingredientId, checked)
            }
        })

        prefs.edit().putString("snapshot", root.toString()).apply()
    }

    fun load(): Snapshot? {
        val raw = prefs.getString("snapshot", null) ?: return null

        return try {
            val root = JSONObject(raw)

            val ingredients = buildList {
                val array = root.optJSONArray("ingredients") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        Ingredient(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            quantityType = QuantityType.valueOf(obj.getString("quantityType")),
                            preferredUnit = MeasurementUnit.valueOf(obj.getString("preferredUnit")),
                            imageUri = obj.nullableString("imageUri")
                        )
                    )
                }
            }

            val recipes = buildList {
                val array = root.optJSONArray("recipes") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val reqArray = obj.optJSONArray("ingredients") ?: JSONArray()

                    val requirements = buildList {
                        for (j in 0 until reqArray.length()) {
                            val req = reqArray.getJSONObject(j)
                            add(
                                RecipeIngredient(
                                    ingredientId = req.getString("ingredientId"),
                                    amountBase = req.getDouble("amountBase")
                                )
                            )
                        }
                    }

                    add(
                        Recipe(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            mealType = runCatching {
                                MealType.valueOf(
                                    obj.optString("mealType", MealType.DINNER.name)
                                )
                            }.getOrDefault(MealType.DINNER),
                            proteinTag = obj.optString("proteinTag", ""),
                            categoryTag = obj
                                .optString("categoryTag", RecipeCategory.OTHER.name)
                                .toRecipeCategory()
                                .name,
                            notes = obj.optString("notes", ""),
                            imageUri = obj.nullableString("imageUri"),
                            ingredients = requirements
                        )
                    )
                }
            }

            val pantryObj = root.optJSONObject("pantry") ?: JSONObject()
            val pantry = pantryObj.keys().asSequence()
                .associateWith { pantryObj.getDouble(it) }

            val modesObj = root.optJSONObject("dayModes") ?: JSONObject()
            val dayModes = modesObj.keys().asSequence().associate { key ->
                val rawMode = modesObj.optString(key, "AUTO")
                val mode = if (rawMode == "AWAY") DayMode.AWAY else DayMode.AUTO
                DayOfWeek.valueOf(key) to mode
            }

            val planObj = root.optJSONObject("plan") ?: JSONObject()
            val plan = planObj.keys().asSequence().associate { key ->
                DayOfWeek.valueOf(key) to
                        if (planObj.isNull(key)) null else planObj.getString(key)
            }

            val sandwichId = if (
                root.has("weeklySandwichRecipeId") &&
                !root.isNull("weeklySandwichRecipeId")
            ) {
                root.getString("weeklySandwichRecipeId")
            } else {
                null
            }

            val checkedObj = root.optJSONObject("shoppingChecked") ?: JSONObject()
            val checked = checkedObj.keys().asSequence()
                .associateWith { checkedObj.optBoolean(it, false) }

            Snapshot(
                ingredients = ingredients,
                recipes = recipes,
                pantry = pantry,
                dayModes = dayModes,
                plan = plan,
                weeklySandwichRecipeId = sandwichId,
                shoppingChecked = checked
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
