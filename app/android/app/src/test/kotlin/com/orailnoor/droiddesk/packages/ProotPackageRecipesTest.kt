package com.orailnoor.droiddesk.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotPackageRecipesTest {

    @Test
    fun supportedRecipesUseExactDebianBookwormPackages() {
        val packagesByEntry = ProotPackageRecipes.all
            .filter { it.supportLevel == PackageSupportLevel.SUPPORTED }
            .associate {
            it.entryId to it.packageNames
        }

        assertEquals(
            mapOf(
                "app.firefox" to listOf("firefox-esr"),
                "app.libreoffice" to listOf("libreoffice"),
                "app.gimp" to listOf("gimp"),
                "app.vlc" to listOf("vlc"),
                "tool.nodejs" to listOf("nodejs", "npm"),
                "tool.python" to listOf("python3", "python3-pip", "python3-venv"),
            ),
            packagesByEntry,
        )
    }

    @Test
    fun recipesHaveUniqueOpaqueIdsAndValidatedMetadata() {
        val recipes = ProotPackageRecipes.all

        assertEquals(recipes.size, recipes.map(PackageRecipe::entryId).toSet().size)
        assertTrue(recipes.all { it.entryId.matches(Regex("(app|tool)\\.[a-z0-9-]+")) })
        assertTrue(recipes.all { recipe ->
            recipe.packageNames.isNotEmpty() &&
                recipe.packageNames.all { it.matches(Regex("[a-z0-9][a-z0-9+.-]*")) } &&
                recipe.primaryPackage in recipe.packageNames
        })
        assertTrue(
            recipes.filter { it.supportLevel == PackageSupportLevel.SUPPORTED }
                .all { it.source == PackageRecipeSource.DebianBookworm },
        )
        assertTrue(recipes.all { it.iconNames.isNotEmpty() })
        assertTrue(recipes.all { it.aliases.isNotEmpty() })
    }

    @Test
    fun launchPoliciesAreFixedInsideRecipes() {
        val firefox = ProotPackageRecipes.require("app.firefox")
        val node = ProotPackageRecipes.require("tool.nodejs")

        assertEquals("firefox-esr", firefox.launchPolicy!!.executable)
        assertTrue(firefox.launchPolicy!!.arguments.isEmpty())
        assertEquals(null, node.launchPolicy)
    }
}
