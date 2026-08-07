package net.azisaba.azisync.util

import org.bukkit.advancement.Advancement
import org.bukkit.entity.Player
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant
import java.util.Date
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Restores a criterion directly in the native progress object. This deliberately
 * bypasses PlayerAdvancements#award/grantCriteria, which would execute rewards,
 * fire completion events, and make the client display a fresh advancement toast.
 */
object SilentAdvancementRestorer {
    fun restore(player: Player, advancement: Advancement, criterion: String): Boolean {
        val craftProgress = player.getAdvancementProgress(advancement)
        if (craftProgress.awardedCriteria.contains(criterion)) return true

        return try {
            val fields = allFields(craftProgress.javaClass)
            val managerField = fields.firstOrNull { field ->
                val name = field.type.simpleName.lowercase()
                name.contains("advancementdataplayer") || name.contains("playeradvancement")
            } ?: return false
            val advancementField = fields.firstOrNull { field ->
                val name = field.type.simpleName.lowercase()
                name == "advancement" || name.contains("advancementholder")
            } ?: return false

            managerField.isAccessible = true
            advancementField.isAccessible = true
            val manager = managerField.get(craftProgress) ?: return false
            val nativeAdvancement = advancementField.get(craftProgress) ?: return false
            val nativeProgress = findProgress(manager, nativeAdvancement) ?: return false
            val criterionProgress = findCriterionProgress(nativeProgress, criterion) ?: return false
            if (!grantCriterion(criterionProgress)) return false

            markProgressChanged(manager, nativeAdvancement)
            craftProgress.awardedCriteria.contains(criterion)
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun findProgress(manager: Any, advancement: Any): Any? {
        val method = allMethods(manager.javaClass).firstOrNull {
            it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(advancement.javaClass) &&
                it.returnType.simpleName.lowercase().contains("advancementprogress")
        } ?: return null
        method.isAccessible = true
        return method.invoke(manager, advancement)
    }

    private fun findCriterionProgress(progress: Any, criterion: String): Any? {
        val method = allMethods(progress.javaClass).firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                it.returnType.simpleName.lowercase().contains("criterionprogress")
        } ?: return null
        method.isAccessible = true
        return method.invoke(progress, criterion)
    }

    private fun grantCriterion(criterionProgress: Any): Boolean {
        // 1.12-1.19 store a java.util.Date; newer mappings use Instant. Writing
        // the timestamp directly avoids the manager's normal award path entirely.
        val obtainedField = allFields(criterionProgress.javaClass).firstOrNull {
            it.type == Date::class.java || it.type == Instant::class.java
        } ?: return false
        obtainedField.isAccessible = true
        obtainedField.set(
            criterionProgress,
            if (obtainedField.type == Date::class.java) Date() else Instant.now()
        )
        return true
    }

    private fun markProgressChanged(manager: Any, advancement: Any) {
        // PlayerAdvancements has version-dependent obfuscated names for its
        // visibility/progress-changed sets. During pre-login they are all safe to
        // invalidate; the first normal sync rebuilds visibility before sending it.
        allFields(manager.javaClass)
            .filter { java.util.Set::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                runCatching {
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (field.get(manager) as? MutableSet<Any>)?.add(advancement)
                }
            }
    }

    private fun allFields(type: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            result.addAll(current.declaredFields)
            current = current.superclass
        }
        return result
    }

    private fun allMethods(type: Class<*>): List<Method> {
        val unique = Collections.newSetFromMap(IdentityHashMap<Method, Boolean>())
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            unique.addAll(current.declaredMethods)
            current = current.superclass
        }
        unique.addAll(type.methods)
        return unique.toList()
    }
}
