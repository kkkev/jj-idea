package `in`.kkkev.jjidea.contract

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Annotation to skip contract tests when jj is not installed.
 */
@Target(AnnotationTarget.CLASS)
@ExtendWith(JjAvailableCondition::class)
annotation class RequiresJj

class JjAvailableCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (JjCli.isAvailable()) {
            ConditionEvaluationResult.enabled("jj is available")
        } else {
            ConditionEvaluationResult.disabled("jj is not installed or not on PATH")
        }
}
